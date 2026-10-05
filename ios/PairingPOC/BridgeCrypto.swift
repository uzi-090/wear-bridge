import CryptoKit
import Foundation
import Security

/// App-layer security for the bridge service. Mirrors `wearos/protocol/SecureChannel.kt`, and both are
/// checked against the same test vectors. See docs/BLE_PROTOCOL.md "App-layer security" (Risk R-22).
enum BridgeCrypto {
    static let nonceBytes = 16
    static let macBytes = 16
    static let publicKeyBytes = 65

    enum AuthType: UInt8 {
        case hello = 0x20
        case helloOK = 0xB0
        case needsApproval = 0xB1
        case denied = 0xB2
    }

    static func staticKey(privateKey: P256.KeyAgreement.PrivateKey, watchKey: Data) throws -> SymmetricKey {
        let peer = try P256.KeyAgreement.PublicKey(x963Representation: watchKey)
        let shared = try privateKey.sharedSecretFromKeyAgreement(with: peer)
        return shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data(), sharedInfo: Data("bridge-v2 static".utf8), outputByteCount: 32)
    }

    static func pairingCode(staticKey: SymmetricKey, iphoneKey: Data, watchKey: Data) -> String {
        let h = hmac(staticKey, Data("code".utf8), iphoneKey, watchKey)
        let n = (UInt32(h[0] & 0x7F) << 24) | (UInt32(h[1]) << 16) | (UInt32(h[2]) << 8) | UInt32(h[3])
        return String(format: "%06d", n % 1_000_000)
    }

    static func helloMac(staticKey: SymmetricKey, nonceI: Data) -> Data { hmac(staticKey, Data("hello".utf8), nonceI).prefix(macBytes) }

    static func okMac(staticKey: SymmetricKey, nonceI: Data, nonceW: Data) -> Data { hmac(staticKey, Data("ok".utf8), nonceI, nonceW).prefix(macBytes) }

    /// (iPhone → watch, watch → iPhone)
    static func sessionKeys(staticKey: SymmetricKey, nonceI: Data, nonceW: Data) -> (SymmetricKey, SymmetricKey) {
        let salt = nonceI + nonceW
        func derive(_ info: String) -> SymmetricKey {
            HKDF<SHA256>.deriveKey(inputKeyMaterial: staticKey, salt: salt, info: Data(info.utf8), outputByteCount: 16)
        }
        return (derive("bridge-v2 i2w"), derive("bridge-v2 w2i"))
    }

    static func randomNonce() -> Data {
        var bytes = [UInt8](repeating: 0, count: nonceBytes)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes)
    }

    private static func hmac(_ key: SymmetricKey, _ parts: Data...) -> Data {
        var h = HMAC<SHA256>(key: key)
        parts.forEach { h.update(data: $0) }
        return Data(h.finalize())
    }
}

/// One established session. Sealed payload = seq u32 BE ‖ AES-GCM ciphertext ‖ tag; nonce = direction ‖
/// seq u64 BE; AAD = framing version ‖ message type.
struct SecureSession {
    private let sendKey: SymmetricKey
    private let receiveKey: SymmetricKey
    private let sendDirection: Data
    private let receiveDirection: Data
    private var sendSeq: UInt32 = 0
    private var lastReceived: UInt32 = 0

    static let iphoneToWatch = Data("i2w ".utf8)
    static let watchToIphone = Data("w2i ".utf8)

    /// The iPhone's side of a session.
    init(keys: (SymmetricKey, SymmetricKey)) {
        sendKey = keys.0
        receiveKey = keys.1
        sendDirection = Self.iphoneToWatch
        receiveDirection = Self.watchToIphone
    }

    mutating func seal(type: UInt8, _ plaintext: [UInt8]) throws -> [UInt8] {
        sendSeq += 1
        let box = try AES.GCM.seal(Data(plaintext), using: sendKey, nonce: Self.nonce(sendDirection, sendSeq), authenticating: Self.aad(type))
        return Self.u32(sendSeq) + [UInt8](box.ciphertext) + [UInt8](box.tag)
    }

    /// nil if forged, corrupted, replayed or out of order.
    mutating func open(type: UInt8, _ payload: [UInt8]) -> [UInt8]? {
        guard payload.count >= 4 + 16 else { return nil }
        let seq = UInt32(payload[0]) << 24 | UInt32(payload[1]) << 16 | UInt32(payload[2]) << 8 | UInt32(payload[3])
        guard seq > lastReceived else { return nil }
        let body = payload[4...]
        guard let nonce = try? Self.nonce(receiveDirection, seq),
              let box = try? AES.GCM.SealedBox(nonce: nonce, ciphertext: Data(body.dropLast(16)), tag: Data(body.suffix(16))),
              let plain = try? AES.GCM.open(box, using: receiveKey, authenticating: Self.aad(type)) else { return nil }
        lastReceived = seq
        return [UInt8](plain)
    }

    private static func nonce(_ direction: Data, _ seq: UInt32) throws -> AES.GCM.Nonce {
        var n = direction
        let s = UInt64(seq)
        for i in 0..<8 { n.append(UInt8(truncatingIfNeeded: s >> (56 - 8 * UInt64(i)))) }
        return try AES.GCM.Nonce(data: n)
    }

    private static func aad(_ type: UInt8) -> Data { Data([BridgeProtocol.version, type]) }

    private static func u32(_ v: UInt32) -> [UInt8] { [UInt8(v >> 24), UInt8(truncatingIfNeeded: v >> 16), UInt8(truncatingIfNeeded: v >> 8), UInt8(truncatingIfNeeded: v)] }
}

/// The iPhone app's identity key (Keychain, this device only, available after first unlock so background
/// relaunches work) and the watch key pinned on first use.
enum BridgeIdentity {
    private static let service = "io.wearbridge.identity"
    private static let account = "iphone-app-key"
    private static let pinnedWatchKey = "bridge.watch.publicKey"

    static func privateKey() -> P256.KeyAgreement.PrivateKey {
        if let raw = load(), let key = try? P256.KeyAgreement.PrivateKey(rawRepresentation: raw) { return key }
        let key = P256.KeyAgreement.PrivateKey()
        save(key.rawRepresentation)
        return key
    }

    static var pinnedWatch: Data? {
        get { UserDefaults.standard.data(forKey: pinnedWatchKey) }
        set { UserDefaults.standard.set(newValue, forKey: pinnedWatchKey) }
    }

    private static func load() -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    private static func save(_ data: Data) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }
}
