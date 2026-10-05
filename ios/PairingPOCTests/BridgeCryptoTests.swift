import CryptoKit
import XCTest
@testable import PairingPOC

/// The same vectors as wearos/protocol/src/test/.../SecureChannelTest.kt: if both suites pass, the
/// iPhone and the watch derive identical keys, codes and frames.
final class BridgeCryptoTests: XCTestCase {
    private func hex(_ s: String) -> Data {
        var d = Data()
        var i = s.startIndex
        while i < s.endIndex {
            let j = s.index(i, offsetBy: 2)
            d.append(UInt8(s[i..<j], radix: 16)!)
            i = j
        }
        return d
    }

    private lazy var privI = try! P256.KeyAgreement.PrivateKey(rawRepresentation: hex("1f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c5b6a7988"))
    private let pubW = "04515c3d6eb9e396b904d3feca7f54fdcd0cc1e997bf375dca515ad0a6c3b4035f4536be3a50f318fbf9a5475902a221502bef0d57e08c53b2cc0a56f17d9f9354"
    private let nonceI = "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
    private let nonceW = "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf"

    private func data(_ k: SymmetricKey) -> Data { k.withUnsafeBytes { Data($0) } }

    func testDerivationMatchesTheWatch() throws {
        let key = try BridgeCrypto.staticKey(privateKey: privI, watchKey: hex(pubW))
        XCTAssertEqual(data(key), hex("72a7e2f45c8da9bbb854daa696adacc54a738a34d788da5347d17afecbd2c2e6"))
        XCTAssertEqual(BridgeCrypto.pairingCode(staticKey: key, iphoneKey: privI.publicKey.x963Representation, watchKey: hex(pubW)), "874561")
        XCTAssertEqual(BridgeCrypto.helloMac(staticKey: key, nonceI: hex(nonceI)), hex("36483b7c836cf6e39d9fe2866d4fab3a"))
        XCTAssertEqual(BridgeCrypto.okMac(staticKey: key, nonceI: hex(nonceI), nonceW: hex(nonceW)), hex("bb1818177b9eef1094d227d276822ae5"))
        let (i2w, w2i) = BridgeCrypto.sessionKeys(staticKey: key, nonceI: hex(nonceI), nonceW: hex(nonceW))
        XCTAssertEqual(data(i2w), hex("8b836042d868cbe31764f4a5ea3756ff"))
        XCTAssertEqual(data(w2i), hex("d503c62d301bd9f04092a497f396e743"))
    }

    func testSealedFrameMatchesTheWatchAndRejectsReplay() throws {
        let key = try BridgeCrypto.staticKey(privateKey: privI, watchKey: hex(pubW))
        var session = SecureSession(keys: BridgeCrypto.sessionKeys(staticKey: key, nonceI: hex(nonceI), nonceW: hex(nonceW)))
        let frame = try session.seal(type: BridgeProtocol.MessageType.setMode.rawValue, [0x02])
        XCTAssertEqual(Data(frame), hex("000000014c0eaaf23adca56a973cfe7ab1293541af"))
        // Our own frame can't be opened by us (the directions use different keys).
        XCTAssertNil(session.open(type: BridgeProtocol.MessageType.setMode.rawValue, frame))
    }

    func testWatchFramesOpenOnceAndOnlyForTheirType() throws {
        let key = try BridgeCrypto.staticKey(privateKey: privI, watchKey: hex(pubW))
        let keys = BridgeCrypto.sessionKeys(staticKey: key, nonceI: hex(nonceI), nonceW: hex(nonceW))
        // Build a watch → iPhone frame by hand with the w2i key.
        var nonce = Data("w2i ".utf8)
        nonce.append(contentsOf: [0, 0, 0, 0, 0, 0, 0, 1])
        let box = try AES.GCM.seal(Data([7]), using: keys.1, nonce: AES.GCM.Nonce(data: nonce), authenticating: Data([1, 0xA0]))
        let frame = [UInt8]([0, 0, 0, 1]) + [UInt8](box.ciphertext) + [UInt8](box.tag)
        var session = SecureSession(keys: keys)
        XCTAssertNil(session.open(type: 0x81, frame), "wrong type")
        XCTAssertEqual(session.open(type: 0xA0, frame), [7])
        XCTAssertNil(session.open(type: 0xA0, frame), "replay")
    }
}

final class BridgeProtocolTests: XCTestCase {
    func testStatusDecodes() throws {
        // battery 72, charging + notifications ready, inbox 3, "SM-R895F", "16", "0.2.0"
        let payload: [UInt8] = [72, 0b011, 0, 3] + [8] + Array("SM-R895F".utf8) + [2] + Array("16".utf8) + [5] + Array("0.2.0".utf8)
        let s = try BridgeCodec.decodeStatus(payload)
        XCTAssertEqual(s.battery, 72)
        XCTAssertTrue(s.charging)
        XCTAssertTrue(s.notificationsReady)
        XCTAssertFalse(s.mediaReady)
        XCTAssertEqual(s.inboxCount, 3)
        XCTAssertEqual(s.displayName, "Galaxy Watch4 Classic")
    }

    func testUnknownBatteryAndTruncation() {
        XCTAssertNil(try BridgeCodec.decodeStatus([255, 0, 0, 0, 0, 0, 0]).battery)
        XCTAssertThrowsError(try BridgeCodec.decodeStatus([50]))
    }

    func testSnapshotPrefsTrailerIsOptional() throws {
        let base: [UInt8] = [2, 0, 0] // mode 2, zero apps
        XCTAssertNil(try SettingsCodec.decodeSnapshot(base).prefs)
        let prefs = try SettingsCodec.decodeSnapshot(base + [1, 0b11]).prefs
        XCTAssertEqual(prefs, WatchPrefs(popupStyle: .subtle, history: true, endCall: true))
        XCTAssertEqual(SettingsCodec.encodeSetPrefs(prefs!), [1, 0b11])
    }

    func testFramingSplitsAndReassembles() {
        let payload = [UInt8](0..<200)
        var r = BridgeReassembler()
        var done: BridgeReassembler.Message?
        for chunk in BridgeProtocol.split(type: .settings, messageId: 9, payload: payload, maxChunkBytes: 20) {
            if case .complete(let m) = r.accept(chunk) { done = m }
        }
        XCTAssertEqual(done?.payload, payload)
        XCTAssertEqual(done?.messageId, 9)
    }
}
