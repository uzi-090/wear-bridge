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
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])) ?? false
    }

    func authorizationStatus() async -> UNAuthorizationStatus {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }

    func start(onFound: @escaping () -> Void) {
        self.onFound = onFound
        ringing = true
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
        UNUserNotificationCenter.current().add(request)
    }
}

extension FindMyPhone: UNUserNotificationCenterDelegate {
    /// In the foreground the app plays its own sound and shows its own screen, so no banner.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        []
    }

    /// Tapping the notification means the user has the iPhone in hand.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard response.notification.request.identifier == "find-iphone" else { return }
        await MainActor.run { FindMyPhone.shared.found() }
    }
}
