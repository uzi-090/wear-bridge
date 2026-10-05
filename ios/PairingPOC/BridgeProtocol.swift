import Foundation

/// Bridge protocol v1, mirroring `wearos/protocol` (Framing.kt, SettingsMessages.kt). See docs/BLE_PROTOCOL.md.
/// Chunk: | version u8 | type u8 | messageId u8 | flags u8 (bit0 = more) | data… |
enum BridgeProtocol {
    static let version: UInt8 = 1
    static let headerBytes = 4

    enum MessageType: UInt8 {
        case getSettings = 0x01
        case setAppRule = 0x02
        case setMode = 0x03
        case setAppIcon = 0x04
        case settings = 0x81
    }

    static func split(type: MessageType, messageId: UInt8, payload: [UInt8], maxChunkBytes: Int) -> [Data] {
        let perChunk = max(1, maxChunkBytes - headerBytes)
        let count = max(1, (payload.count + perChunk - 1) / perChunk)
        return (0..<count).map { i in
            let start = i * perChunk
            let end = min(payload.count, start + perChunk)
            let more: UInt8 = i < count - 1 ? 1 : 0
            return Data([version, type.rawValue, messageId, more] + payload[start..<end])
        }
    }
}

struct BridgeReassembler {
    struct Message {
        let type: UInt8
        let messageId: UInt8
        let payload: [UInt8]
    }

    enum Result {
        case incomplete
        case complete(Message)
        case error(String)
    }

    private var type: UInt8?
    private var messageId: UInt8 = 0
    private var buffer: [UInt8] = []
    private let maxBytes = 32 * 1024

    mutating func reset() {
        type = nil
        buffer.removeAll(keepingCapacity: true)
    }

    mutating func accept(_ chunk: Data) -> Result {
        let bytes = [UInt8](chunk)
        guard bytes.count >= BridgeProtocol.headerBytes else { reset(); return .error("short chunk") }
        guard bytes[0] == BridgeProtocol.version else { reset(); return .error("unsupported version \(bytes[0])") }
        // A new message before the previous one finished abandons the old one.
        if let t = type, t != bytes[1] || messageId != bytes[2] { reset() }
        type = bytes[1]
        messageId = bytes[2]
        buffer += bytes[BridgeProtocol.headerBytes...]
        guard buffer.count <= maxBytes else { reset(); return .error("message too large") }
        if bytes[3] & 0x01 != 0 { return .incomplete }
        let message = Message(type: bytes[1], messageId: bytes[2], payload: buffer)
        reset()
        return .complete(message)
    }
}

// MARK: - Settings messages

/// Wire codes are explicit (ModeCode on the watch).
enum FilterMode: UInt8, CaseIterable, Identifiable {
    case all = 0, important = 1, messagesAndCalls = 2, callsOnly = 3

    var id: UInt8 { rawValue }

    var title: String {
        switch self {
        case .all: "All notifications"
        case .important: "Important only"
        case .messagesAndCalls: "Messages & calls"
        case .callsOnly: "Calls only"
        }
    }

    var detail: String {
        switch self {
        case .all: "Every iPhone notification appears on your watch."
        case .important: "Only calls and notifications iOS marks as important. The rest wait quietly in the watch's list."
        case .messagesAndCalls: "Calls, messages and email. Everything else waits quietly in the watch's list."
        case .callsOnly: "Only calls. Everything else waits quietly in the watch's list."
        }
    }
}

struct WatchApp: Identifiable, Equatable {
    let id: String // bundle ID
    var name: String
    var enabled: Bool
    var vibrate: Bool
    /// nil = the default for this kind of app.
    var hideContent: Bool?
    var hiddenByDefault: Bool
    var color: UInt32
    var logo: String
    /** The watch already has an App Store icon for this app. */
    var hasIcon: Bool

    var hidesContent: Bool { hideContent ?? hiddenByDefault }

    /// iOS display names can carry a left-to-right mark (WhatsApp's does).
    var displayName: String { name.trimmingCharacters(in: CharacterSet(charactersIn: "\u{200E}\u{200F}").union(.whitespaces)) }
}

struct WatchSettings: Equatable {
    var mode: FilterMode
    var apps: [WatchApp]
}

enum SettingsCodec {
    struct DecodeError: Error {}

    static func decodeSnapshot(_ payload: [UInt8]) throws -> WatchSettings {
        var r = Reader(bytes: payload)
        let mode = FilterMode(rawValue: try r.u8()) ?? .all
        let count = try r.u16()
        var apps: [WatchApp] = []
        apps.reserveCapacity(count)
        for _ in 0..<count {
            let id = try r.str()
            let name = try r.str()
            let flags = try r.u8()
            let hide = try untri(r.u8())
            let color = try r.u32()
            let logo = try r.str()
            apps.append(WatchApp(id: id, name: name, enabled: flags & 1 != 0, vibrate: flags & 2 != 0,
                                 hideContent: hide, hiddenByDefault: flags & 4 != 0, color: color, logo: logo,
                                 hasIcon: flags & 8 != 0))
        }
        return WatchSettings(mode: mode, apps: apps)
    }

    static func encodeSetAppRule(bundleId: String, enabled: Bool, vibrate: Bool, hideContent: Bool?) -> [UInt8] {
        let flags: UInt8 = (enabled ? 1 : 0) | (vibrate ? 2 : 0)
        return str(bundleId) + [flags, tri(hideContent)]
    }

    static func encodeSetMode(_ mode: FilterMode) -> [UInt8] { [mode.rawValue] }

    static func encodeSetAppIcon(bundleId: String, image: Data) -> [UInt8] { str(bundleId) + [UInt8](image) }

    private static func tri(_ v: Bool?) -> UInt8 { v == nil ? 2 : (v! ? 1 : 0) }

    private static func untri(_ v: UInt8) throws -> Bool? {
        switch v {
        case 0: return false
        case 1: return true
        case 2: return nil
        default: throw DecodeError()
        }
    }

    /// u8 length + UTF-8, cut to 255 bytes on a character boundary.
    private static func str(_ s: String) -> [UInt8] {
        var bytes: [UInt8] = []
        for scalar in s.unicodeScalars {
            let encoded = Array(String(scalar).utf8)
            if bytes.count + encoded.count > 255 { break }
            bytes += encoded
        }
        return [UInt8(bytes.count)] + bytes
    }

    private struct Reader {
        let bytes: [UInt8]
        var p = 0

        mutating func u8() throws -> UInt8 {
            guard p < bytes.count else { throw DecodeError() }
            defer { p += 1 }
            return bytes[p]
        }

        mutating func u16() throws -> Int { Int(try u8()) << 8 | Int(try u8()) }

        mutating func u32() throws -> UInt32 {
            var v: UInt32 = 0
            for _ in 0..<4 { v = v << 8 | UInt32(try u8()) }
            return v
        }

        mutating func str() throws -> String {
            let n = Int(try u8())
            guard p + n <= bytes.count else { throw DecodeError() }
            defer { p += n }
            return String(decoding: bytes[p..<p + n], as: UTF8.self)
        }
    }
}
