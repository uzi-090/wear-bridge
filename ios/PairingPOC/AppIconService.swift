import Foundation
import Observation
import UIKit

/// Official app icons from Apple's public App Store lookup service (`itunes.apple.com/lookup`).
/// iOS gives apps no API to read other apps' icons, so this is the legitimate source. Only bundle IDs of
/// apps that notified the watch are sent, and only to Apple. Results are cached on disk, so each app is
/// looked up once. Misses are retried after a week. Apple's built-in apps aren't in the store, so they're skipped.
@MainActor
@Observable
final class AppIconService {
    static let shared = AppIconService()

    /// Icons available for display, keyed by bundle ID (96 px JPEG data, the same bytes sent to the watch).
    private(set) var icons: [String: Data] = [:]

    @ObservationIgnored private var inFlight: Set<String> = []
    @ObservationIgnored private let dir: URL = {
        let d = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("AppIcons")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()

    private static let retryMissAfter: TimeInterval = 7 * 24 * 3600
    private static let pixels: CGFloat = 96

    func isLookupable(_ bundleId: String) -> Bool { !bundleId.hasPrefix("com.apple.") }

    /// Privacy control: forgets every cached icon (and the record of which apps were looked up).
    func clearCache() {
        icons.removeAll()
        if let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) {
            files.forEach { try? FileManager.default.removeItem(at: $0) }
        }
    }

    /// Cached icon, or fetches it. Returns nil if the App Store doesn't have the app.
    func icon(for bundleId: String) async -> Data? {
        if let cached = icons[bundleId] { return cached }
        let file = dir.appendingPathComponent(safe(bundleId) + ".jpg")
        if let data = try? Data(contentsOf: file) {
            icons[bundleId] = data
            return data
        }
        let miss = dir.appendingPathComponent(safe(bundleId) + ".miss")
        if let attrs = try? FileManager.default.attributesOfItem(atPath: miss.path),
           let date = attrs[.modificationDate] as? Date, Date().timeIntervalSince(date) < Self.retryMissAfter {
            return nil
        }
        guard isLookupable(bundleId), inFlight.insert(bundleId).inserted else { return nil }
        defer { inFlight.remove(bundleId) }

        for country in countries() {
            guard let artwork = await lookupArtwork(bundleId: bundleId, country: country),
                  let (raw, _) = try? await URLSession.shared.data(from: artwork),
                  let jpeg = Self.resize(raw) else { continue }
            try? jpeg.write(to: file, options: .atomic)
            icons[bundleId] = jpeg
            return jpeg
        }
        FileManager.default.createFile(atPath: miss.path, contents: nil)
        return nil
    }

    private func lookupArtwork(bundleId: String, country: String) async -> URL? {
        var c = URLComponents(string: "https://itunes.apple.com/lookup")!
        c.queryItems = [URLQueryItem(name: "bundleId", value: bundleId), URLQueryItem(name: "country", value: country)]
        guard let url = c.url, let (data, _) = try? await URLSession.shared.data(from: url),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let first = (json["results"] as? [[String: Any]])?.first,
              let artwork = (first["artworkUrl100"] ?? first["artworkUrl512"] ?? first["artworkUrl60"]) as? String
        else { return nil }
        return URL(string: artwork)
    }

    /// The user's own store region first (apps like Flipkart may only be listed there), then the US.
    private func countries() -> [String] {
        let region = Locale.current.region?.identifier.lowercased() ?? "us"
        return region == "us" ? ["us"] : [region, "us"]
    }

    private static func resize(_ data: Data) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let size = CGSize(width: pixels, height: pixels)
        let resized = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        return resized.jpegData(compressionQuality: 0.85)
    }

    private func safe(_ id: String) -> String {
        String(id.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) || $0 == "." || $0 == "-" ? Character($0) : "_" })
    }
}
