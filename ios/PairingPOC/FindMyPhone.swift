import AVFoundation
import Observation
import UIKit
import UserNotifications

/// "Find iPhone" from the watch. Best effort by design, because iOS decides what a third-party app may do:
/// - App in the foreground: a looping sound (plays even with the ring switch on silent), plus an in-app screen.
/// - App in the background: iOS wakes the app for the Bluetooth event, and it posts a Time Sensitive local
///   notification with a bundled 29.5 s alert sound. The ring switch, Focus and notification settings
///   still apply. Critical Alerts would bypass them, but Apple grants that entitlement only to
///   medical/safety apps.
/// - App force-quit by the user: iOS doesn't relaunch it, so nothing rings. The watch says so.
@MainActor
@Observable
final class FindMyPhone: NSObject {
    static let shared = FindMyPhone()

    private(set) var ringing = false

    @ObservationIgnored private var player: AVAudioPlayer?
    @ObservationIgnored private var onFound: (() -> Void)?
    @ObservationIgnored private var timeout: Task<Void, Never>?

    static let soundName = "FindPhone.caf"
    private static let notificationId = "find-iphone"
    private static let maxRing: Duration = .seconds(30)

    override init() {
        super.init()
        UNUserNotificationCenter.current().delegate = self
    }

    /// Asks for notification permission (alert + sound), after the UI has explained why.
    func requestPermission() async -> Bool {
        do {
            let granted = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
            DiagFile.append("find: permission granted=\(granted)")
            return granted
        } catch {
            DiagFile.append("find: permission error=\((error as NSError).code)")
            return false
        }
    }

    func authorizationStatus() async -> UNAuthorizationStatus {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }

    func start(onFound: @escaping () -> Void) {
        self.onFound = onFound
        ringing = true
        DiagFile.append("find: start appState=\(UIApplication.shared.applicationState.rawValue)")
        if UIApplication.shared.applicationState == .active {
            playLoop()
        } else {
            postNotification()
        }
        timeout?.cancel()
        timeout = Task { [weak self] in
            try? await Task.sleep(for: Self.maxRing)
            guard !Task.isCancelled else { return }
            self?.stop()
        }
    }

    /// The watch stopped it, or it timed out.
    func stop() {
        guard ringing else { return }
        ringing = false
        timeout?.cancel()
        player?.stop()
        player = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        let center = UNUserNotificationCenter.current()
        center.removeDeliveredNotifications(withIdentifiers: [Self.notificationId])
        center.removePendingNotificationRequests(withIdentifiers: [Self.notificationId])
    }

    /// The user found the iPhone: stop here and tell the watch.
    func found() {
        let callback = onFound
        stop()
        callback?()
    }

    /// The app came to the foreground while ringing (for example, from the notification): switch to the in-app sound.
    func appBecameActive() {
        if ringing && player == nil { playLoop() }
    }

    private func playLoop() {
        guard let url = Bundle.main.url(forResource: "FindPhone", withExtension: "caf") else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default)
            try session.setActive(true)
            let p = try AVAudioPlayer(contentsOf: url)
            p.numberOfLoops = -1
            p.play()
            player = p
        } catch {
            postNotification() // fall back to the system sound path
        }
    }

    private func postNotification() {
        let content = UNMutableNotificationContent()
        content.title = "Your watch is looking for this iPhone"
        content.body = "Tap to stop the sound."
        content.sound = UNNotificationSound(named: UNNotificationSoundName(Self.soundName))
        // Honoured only with the Time Sensitive entitlement (paid developer team). Otherwise iOS treats it as .active.
        content.interruptionLevel = .timeSensitive
        let request = UNNotificationRequest(identifier: Self.notificationId, content: content, trigger: nil)
        let center = UNUserNotificationCenter.current()
        center.add(request) { error in
            DiagFile.append("find: notification added error=\((error as NSError?)?.code ?? 0)")
        }
        // Diagnostics: the settings that decide whether and how iOS shows it, and whether it was delivered.
        Task {
            let s = await center.notificationSettings()
            DiagFile.append("find: settings auth=\(s.authorizationStatus.rawValue) alert=\(s.alertSetting.rawValue) sound=\(s.soundSetting.rawValue) lock=\(s.lockScreenSetting.rawValue) center=\(s.notificationCenterSetting.rawValue) style=\(s.alertStyle.rawValue) timeSensitive=\(s.timeSensitiveSetting.rawValue) summary=\(s.scheduledDeliverySetting.rawValue)")
            try? await Task.sleep(for: .seconds(1))
            let delivered = await center.deliveredNotifications()
            DiagFile.append("find: delivered=\(delivered.filter { $0.request.identifier == Self.notificationId }.count)")
        }
    }
}

extension FindMyPhone: UNUserNotificationCenterDelegate {
    /// Find iPhone has its own foreground screen; battery warnings still show a banner and sound.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        if notification.request.identifier.hasPrefix(LowBatteryAlerts.notificationPrefix) {
            return [.banner, .list, .sound]
        }
        return []
    }

    /// Tapping the notification means the user has the iPhone in hand.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let id = response.notification.request.identifier
        let phone = response.notification.request.content.userInfo["phone"] as? String
        await MainActor.run {
            if id == "find-iphone" { FindMyPhone.shared.found() }
            if id == CallHandoff.notificationId, let phone { CallHandoff.dial(phone) }
        }
    }
}

/// "Call from iPhone" on the watch. iOS doesn't let apps place calls silently, so the iPhone offers the
/// call and the user confirms it: directly if Bridge is open, otherwise through a notification.
@MainActor
enum CallHandoff {
    static let notificationId = "call-request"

    static func disconnected() {
        let center = UNUserNotificationCenter.current()
        center.removeDeliveredNotifications(withIdentifiers: [notificationId])
        center.removePendingNotificationRequests(withIdentifiers: [notificationId])
    }

    static func offer(phone: String, name: String) {
        if UIApplication.shared.applicationState == .active {
            dial(phone)
            return
        }
        let content = UNMutableNotificationContent()
        content.title = name.isEmpty ? "Call from your watch" : "Call \(name)?"
        content.body = "Tap to call from this iPhone."
        content.sound = .default
        content.userInfo = ["phone": phone]
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: notificationId, content: content, trigger: nil))
    }

    /// Opening tel: shows iOS's own call confirmation.
    static func dial(_ phone: String) {
        let digits = phone.filter { $0.isNumber || $0 == "+" }
        guard !digits.isEmpty, let url = URL(string: "tel:\(digits)") else { return }
        UIApplication.shared.open(url)
    }
}
