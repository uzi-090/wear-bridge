import Foundation

/// A small on-device log of the same redacted lines the Diagnostics screen shows (states, error codes;
/// never names, numbers or content), so background behaviour can be checked afterwards. Developers copy it
/// with `xcrun devicectl device copy from --domain-type appDataContainer --source Library/Caches/diagnostics.log`.
enum DiagFile {
    private static let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("diagnostics.log")
    private static let maxBytes = 256 * 1024
    private static let lock = NSLock()

    static func append(_ line: String) {
        let stamp = ISO8601DateFormatter.string(from: Date(), timeZone: .current, formatOptions: [.withTime, .withColonSeparatorInTime, .withFractionalSeconds])
        guard let data = "\(stamp) \(line)\n".data(using: .utf8) else { return }
        lock.lock()
        defer { lock.unlock() }
        if let size = try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int, size > maxBytes {
            try? FileManager.default.removeItem(at: url) // simple bound: start over
        }
        if let h = try? FileHandle(forWritingTo: url) {
            h.seekToEndOfFile()
            h.write(data)
            try? h.close()
        } else {
            try? data.write(to: url)
        }
    }
}
