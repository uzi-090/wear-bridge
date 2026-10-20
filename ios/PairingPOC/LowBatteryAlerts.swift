import CryptoKit
import Foundation
import UserNotifications

/// Same boundary as the watch; only confirmed charging or recovery rearms a warning.
enum LowBatteryPolicy {
    enum Decision { case ignore, rearm, alert }
    static func decide(percent: Int?, alerted: Bool, charging: Bool? = false) -> Decision {
        if charging == true { return .rearm }
        guard let percent, (0...100).contains(percent) else { return .ignore }
        if percent > 10 { return .rearm }
        guard charging == false else { return .ignore }
        return alerted ? .ignore : .alert
    }
}

@MainActor
final class LowBatteryAlerts {
    static let shared = LowBatteryAlerts()
    nonisolated static let notificationPrefix = "watch-low-battery."
    private var pending = Set<String>()
    private var levels: [String: Int] = [:]
    private var chargingStates: [String: Bool] = [:]

    func disconnected() {
        levels.removeAll(); chargingStates.removeAll()
        let center = UNUserNotificationCenter.current()
        center.getDeliveredNotifications { notifications in
            center.removeDeliveredNotifications(withIdentifiers: notifications.map(\.request.identifier).filter { $0.hasPrefix(Self.notificationPrefix) })
        }
    }

    /// Called only after decoding a freshly authenticated watch STATUS, never from cached Home state.
    func receive(percent: Int?, charging: Bool, watchKey: Data) {
        let peer = SHA256.hash(data: watchKey).map { String(format: "%02x", $0) }.joined()
        let key = "batteryAlert.low.\(peer)"
        let id = Self.notificationPrefix + peer
        chargingStates[peer] = charging
        if let percent, (0...100).contains(percent) { levels[peer] = percent }
        else { levels.removeValue(forKey: peer) }
        let center = UNUserNotificationCenter.current()
        switch LowBatteryPolicy.decide(percent: percent, alerted: UserDefaults.standard.bool(forKey: key), charging: charging) {
        case .ignore: return
        case .rearm:
            UserDefaults.standard.set(false, forKey: key)
            center.removeDeliveredNotifications(withIdentifiers: [id])
            center.removePendingNotificationRequests(withIdentifiers: [id])
        case .alert:
            guard pending.insert(peer).inserted else { return }
            Task {
                defer { pending.remove(peer) }
                let settings = await center.notificationSettings()
                guard (settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional),
                      settings.alertSetting == .enabled,
                      let current = levels[peer], current <= 10, chargingStates[peer] == false,
                      !UserDefaults.standard.bool(forKey: key) else { return }
                let content = UNMutableNotificationContent()
                content.title = "Watch battery low"
                content.body = "Your watch battery is at \(current)%. Charge your watch soon."
                content.sound = .default
                do {
                    try await center.add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
                    // Charging/recovery or an unknown reading during submission invalidates this warning.
                    if chargingStates[peer] != false || levels[peer].map({ $0 > 10 }) != false {
                        center.removeDeliveredNotifications(withIdentifiers: [id])
                        center.removePendingNotificationRequests(withIdentifiers: [id])
                    } else { UserDefaults.standard.set(true, forKey: key) }
                } catch { DiagFile.append("watch low battery: notification failed") }
            }
        }
    }
}
