import Foundation

/// Bridge protocol v1, mirroring `wearos/protocol` (Framing.kt, SettingsMessages.kt). See docs/BLE_PROTOCOL.md.
/// Chunk: | version u8 | type u8 | messageId u8 | flags u8 (bit0 = more) | data… |
enum BridgeProtocol {
    static let version: UInt8 = 1
    static let headerBytes = 4

    enum MessageType: UInt8 {
        // SETTINGS
        case getSettings = 0x01
        case setAppRule = 0x02
        case setMode = 0x03
        case setAppIcon = 0x04
        case setPrefs = 0x05
        case settings = 0x81
        // CONTROL (iPhone → watch)
        case findWatch = 0x10
        case findPhoneStopped = 0x11
        case ping = 0x12
        // EVENTS (watch → iPhone)
        case findPhone = 0x90
        case findWatchStopped = 0x91
        case callRequest = 0x92
        // CONTACTS (iPhone → watch)
        case contactsBegin = 0x30
        case contactsUpsert = 0x31
        case contactsDelete = 0x32
        case contactsEnd = 0x33
        case contactsClear = 0x34
        // DEVICE_STATUS (watch → iPhone)
        case status = 0xA0
    }

    /// Capability bits in the PAIRING value (`BridgeUuids` on the watch).
    struct Capabilities: OptionSet, Sendable {
        let rawValue: UInt32
        static let ancs = Capabilities(rawValue: 1 << 0)
        static let media = Capabilities(rawValue: 1 << 1)
        static let settings = Capabilities(rawValue: 1 << 2)
        static let deviceStatus = Capabilities(rawValue: 1 << 3)
        static let find = Capabilities(rawValue: 1 << 4)
        static let prefs = Capabilities(rawValue: 1 << 5)
        static let secure = Capabilities(rawValue: 1 << 6)
        static let contacts = Capabilities(rawValue: 1 << 7)
    }

    static func split(type: MessageType, messageId: UInt8, payload: [UInt8], maxChunkBytes: Int) -> [Data] {
        split(rawType: type.rawValue, messageId: messageId, payload: payload, maxChunkBytes: maxChunkBytes)
    }

    static func split(rawType: UInt8, messageId: UInt8, payload: [UInt8], maxChunkBytes: Int) -> [Data] {
        let perChunk = max(1, maxChunkBytes - headerBytes)
        let count = max(1, (payload.count + perChunk - 1) / perChunk)
        return (0..<count).map { i in
            let start = i * perChunk
            let end = min(payload.count, start + perChunk)
            let more: UInt8 = i < count - 1 ? 1 : 0
            return Data([version, rawType, messageId, more] + payload[start..<end])
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

/// Watch-wide preferences (stored and applied on the watch).
struct WatchPrefs: Equatable {
    enum PopupStyle: UInt8 { case standard = 0, subtle = 1 }
    var popupStyle: PopupStyle
    var history: Bool
    var endCall: Bool
}

struct WatchSettings: Equatable {
    var mode: FilterMode
    var apps: [WatchApp]
    /// nil from watches older than v1.1.
    var prefs: WatchPrefs?
}

/// The watch's own state, pushed over DEVICE_STATUS when it changes.
struct WatchStatus: Equatable {
    var battery: Int?
    var charging: Bool
    var notificationsReady: Bool
    var mediaReady: Bool
    var inboxCount: Int
    var model: String
    var osVersion: String
    var appVersion: String
    /// The contacts sync the watch last committed (0 = none) and how many it holds.
    var contactsGeneration: UInt32 = 0
    var contactCount: Int = 0

    /// Marketing name for Samsung model numbers (SM-R8xx = Galaxy Watch4 family).
    var displayName: String {
        let m = model.uppercased()
        if m.hasPrefix("SM-R88") || m.hasPrefix("SM-R89") { return "Galaxy Watch4 Classic" }
        if m.hasPrefix("SM-R86") || m.hasPrefix("SM-R87") { return "Galaxy Watch4" }
        if m.hasPrefix("SM-R9") { return "Galaxy Watch" }
        return model.isEmpty ? "Your watch" : model
    }
}

enum BridgeCodec {
    static func decodeStatus(_ payload: [UInt8]) throws -> WatchStatus {
        var r = ByteReader(bytes: payload)
        let battery = try r.u8()
        let flags = try r.u8()
        var s = WatchStatus(
            battery: battery <= 100 ? Int(battery) : nil,
            charging: flags & 1 != 0,
            notificationsReady: flags & 2 != 0,
            mediaReady: flags & 4 != 0,
            inboxCount: try r.u16(),
            model: try r.str(),
            osVersion: try r.str(),
            appVersion: try r.str()
        )
        if r.remaining >= 6 {
            s.contactsGeneration = try r.u32()
            s.contactCount = try r.u16()
        }
        return s
    }

    static func decodeCallRequest(_ payload: [UInt8]) throws -> (phone: String, name: String) {
        var r = ByteReader(bytes: payload)
        return (try r.str(), try r.str())
    }

    static func encodeSwitch(_ on: Bool) -> [UInt8] { [on ? 1 : 0] }
}

enum SettingsCodec {
    struct DecodeError: Error {}

    static func decodeSnapshot(_ payload: [UInt8]) throws -> WatchSettings {
        var r = ByteReader(bytes: payload)
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
        var prefs: WatchPrefs?
        if r.remaining >= 2 {
            let popup = try r.u8()
            let f = try r.u8()
            prefs = WatchPrefs(popupStyle: .init(rawValue: popup) ?? .standard, history: f & 1 != 0, endCall: f & 2 != 0)
        }
        return WatchSettings(mode: mode, apps: apps, prefs: prefs)
    }

    static func encodeSetPrefs(_ p: WatchPrefs) -> [UInt8] {
        [p.popupStyle.rawValue, (p.history ? 1 : 0) | (p.endCall ? 2 : 0)]
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
}

/// u8 / u16 / u32 big-endian, strings as u8 length + UTF-8. Mirrors the watch's readers.
struct ByteReader {
    struct DecodeError: Error {}

    let bytes: [UInt8]
    var p = 0

    var remaining: Int { bytes.count - p }

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

/// Mirrors the watch's writers: u8 / u16 / u32 big-endian, strings as u8 length + UTF-8 cut on a character boundary.
struct ByteWriter {
    private(set) var bytes: [UInt8] = []

    mutating func u8(_ v: Int) { bytes.append(UInt8(truncatingIfNeeded: v)) }
    mutating func u16(_ v: Int) { u8(v >> 8); u8(v) }
    mutating func u32(_ v: UInt32) { for shift in [24, 16, 8, 0] { u8(Int(v >> UInt32(shift))) } }

    mutating func str(_ s: String) {
        var out: [UInt8] = []
        for scalar in s.unicodeScalars {
            let encoded = Array(String(scalar).utf8)
            if out.count + encoded.count > 255 { break }
            out += encoded
        }
        u8(out.count)
        bytes += out
    }
}

/// CONTACTS messages (wearos/protocol/ContactsMessages.kt).
struct SyncContact: Equatable {
    let id: String
    let name: String
    let phones: [String]
    let emails: [String]
    var favorite: Bool

    static let maxPhones = 8
    static let maxEmails = 3

    func encode(into w: inout ByteWriter) {
        w.str(id)
        w.str(name)
        w.u8(favorite ? 1 : 0)
        let p = phones.prefix(Self.maxPhones)
        w.u8(p.count)
        p.forEach { w.str($0) }
        let e = emails.prefix(Self.maxEmails)
        w.u8(e.count)
        e.forEach { w.str($0) }
    }

    /// Digits with an optional leading "+", the same rule as the watch's ContactsCodec.normalizePhone.
    static func normalize(_ raw: String) -> String {
        let digits = raw.filter(\.isNumber).filter(\.isASCII)
        return raw.trimmingCharacters(in: .whitespaces).hasPrefix("+") ? "+" + digits : digits
    }
}

enum ContactsCodec {
    static func begin(full: Bool, count: Int) -> [UInt8] {
        var w = ByteWriter()
        w.u8(full ? 1 : 0)
        w.u16(min(count, 0xFFFF))
        return w.bytes
    }

    static func upsert(_ contacts: [SyncContact]) -> [UInt8] {
        var w = ByteWriter()
        w.u8(contacts.count)
        contacts.forEach { $0.encode(into: &w) }
        return w.bytes
    }

    static func delete(_ ids: [String]) -> [UInt8] {
        var w = ByteWriter()
        w.u8(ids.count)
        ids.forEach { w.str($0) }
        return w.bytes
    }

    static func end(generation: UInt32) -> [UInt8] {
        var w = ByteWriter()
        w.u32(generation)
        return w.bytes
    }
}
