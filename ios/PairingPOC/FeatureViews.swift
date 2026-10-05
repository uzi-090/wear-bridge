import SwiftUI
import UserNotifications

// MARK: - Find Watch

struct FindWatchView: View {
    let link: WatchLink
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            Image(systemName: link.ringingWatch ? "speaker.wave.3.fill" : "speaker.wave.2")
                .font(.system(size: 80))
                .foregroundStyle(.blue.gradient)
                .symbolEffect(.variableColor.iterative, isActive: link.ringingWatch && !reduceMotion)
                .contentTransition(.symbolEffect(.replace))
                .accessibilityHidden(true)
            VStack(spacing: 8) {
                Text(link.ringingWatch ? "Ringing your watch…" : "Find your watch")
                    .font(.title.bold())
                Text(link.isConnected
                     ? "Your watch plays a sound and vibrates until you tap Found it, or for one minute."
                     : "Your watch needs to be connected. If it's out of range, it can't ring.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            Spacer()
            Button(link.ringingWatch ? "Stop" : "Play Sound") { link.ringWatch(!link.ringingWatch) }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!link.isConnected)
        }
        .padding(24)
        .background(Theme.background)
        .navigationTitle("Find Watch")
        .navigationBarTitleDisplayMode(.inline)
        .sensoryFeedback(.impact, trigger: link.ringingWatch)
    }
}

// MARK: - Calls

struct CallsView: View {
    let link: WatchLink

    var body: some View {
        List {
            Section {
                Capability(symbol: "phone.arrow.down.left.fill", tint: .green, title: "Incoming calls",
                           detail: "Caller name or number on your watch, with Answer and Decline.", works: true)
                Capability(symbol: "phone.down.fill", tint: .red, title: "Missed calls",
                           detail: "Shown on your watch with iOS's own actions.", works: true)
                Capability(symbol: "speaker.slash.fill", tint: .indigo, title: "Silence",
                           detail: "Stops the watch ringing. Your iPhone keeps ringing: iOS doesn't let accessories silence it.", works: true)
            } header: {
                Text("On your watch")
            }

            Section {
                Capability(symbol: "speaker.wave.2.fill", tint: .blue, title: "Call audio",
                           detail: "Stays on your iPhone, AirPods or car. iOS doesn't let a watch app carry iPhone call audio.", works: false)
                Capability(symbol: "mic.slash.fill", tint: .gray, title: "Mute and hold",
                           detail: "Not available. iOS doesn't share these controls with accessories.", works: false)
                Capability(symbol: "phone.arrow.up.right.fill", tint: .gray, title: "Calling from the watch",
                           detail: "Not available. iOS doesn't let apps start a call in the background.", works: false)
            } header: {
                Text("Audio routing and limits")
            }

            if let prefs = link.watchSettings?.prefs, let calls = prefs.calls {
                Section {
                    Toggle("Bezel to answer", isOn: Binding(
                        get: { calls.bezelToAnswer },
                        set: { var p = prefs; p.calls?.bezelToAnswer = $0; link.setPrefs(p) }
                    ))
                    Toggle("Shake to decline", isOn: Binding(
                        get: { calls.shakeToDecline },
                        set: { var p = prefs; p.calls?.shakeToDecline = $0; link.setPrefs(p) }
                    ))
                    Toggle("Shake to end call", isOn: Binding(
                        get: { calls.shakeToEnd },
                        set: { var p = prefs; p.calls?.shakeToEnd = $0; link.setPrefs(p) }
                    ))
                    .disabled(!prefs.endCall)
                } header: {
                    Text("Gestures on your watch")
                } footer: {
                    Text("Turn the bezel 3 clicks clockwise to answer, or anticlockwise to decline. A firm shake declines a ringing call. Shake to end needs End calls from watch, below. Silence (on the call screen, or Back) stops the watch only: iOS doesn't let accessories silence the iPhone.")
                }
            }

            if let prefs = link.watchSettings?.prefs {
                Section {
                    Toggle("End calls from watch", isOn: Binding(
                        get: { prefs.endCall },
                        set: { var p = prefs; p.endCall = $0; link.setPrefs(p) }
                    ))
                } header: {
                    Text("Experimental")
                } footer: {
                    Text("Shows iOS's End Call button on your watch during a call. It uses iPhone behaviour Apple doesn't document, so an iOS update may remove it.")
                }
            }
        }
        .navigationTitle("Calls")
    }
}

/// A capability with an honest works/doesn't-work marker (icon + words).
struct Capability: View {
    let symbol: String
    let tint: Color
    let title: String
    let detail: String
    let works: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            IconTile(symbol: symbol, tint: works ? tint : .gray)
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(title).font(.body.weight(.semibold))
                    Spacer()
                    Text(works ? "Works" : "Not available")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(works ? .green : .secondary)
                }
                Text(detail).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Media

struct MediaInfoView: View {
    let link: WatchLink

    var body: some View {
        List {
            Section {
                Capability(symbol: "playpause.fill", tint: .pink, title: "Play, pause and skip",
                           detail: "Open Now playing on your watch. It controls whatever your iPhone is playing.", works: true)
                Capability(symbol: "speaker.wave.2.fill", tint: .pink, title: "Volume",
                           detail: "Turn the watch's bezel or crown on the Now playing screen.", works: true)
                Capability(symbol: "music.note", tint: .pink, title: "Track and artist",
                           detail: "Shown on your watch, with the track's progress around the edge.", works: true)
                Capability(symbol: "photo", tint: .gray, title: "Album artwork",
                           detail: "Not available. iOS doesn't share artwork with accessories.", works: false)
            } footer: {
                Text("Works with Apple Music, Spotify, podcasts and any app that shows controls on your Lock Screen. iOS shares this with your watch directly, even when Bridge is closed.")
            }
            if let status = link.watchStatus {
                Section("Status") {
                    LabeledContent("Media controls", value: status.mediaReady ? "Ready" : "Not available")
                }
            }
        }
        .navigationTitle("Music & Media")
    }
}

// MARK: - Settings

struct SettingsView: View {
    let link: WatchLink
    @State private var confirmForget = false
    @State private var iconsCleared = false
    @State private var alerts: UNAuthorizationStatus?

    var body: some View {
        List {
            Section {
                NavigationLink { NotificationSettingsView(link: link) } label: {
                    Label("Notifications", systemImage: "bell.badge")
                }
                NavigationLink { CallsView(link: link) } label: {
                    Label("Calls", systemImage: "phone")
                }
            }

            Section {
                LabeledContent("Alerts", value: alertsText)
                if alerts == .notDetermined {
                    Button("Allow Alerts") {
                        Task {
                            _ = await FindMyPhone.shared.requestPermission()
                            alerts = await FindMyPhone.shared.authorizationStatus()
                        }
                    }
                } else if alerts == .denied {
                    Button("Open Notification Settings") {
                        if let url = URL(string: UIApplication.openNotificationSettingsURLString) { UIApplication.shared.open(url) }
                    }
                }
            } header: {
                Text("Find iPhone")
            } footer: {
                Text("When Bridge is in the background, your watch rings this iPhone with an alert. The ring switch and Focus still apply. If you swipe Bridge away, it can't ring until you open it again.")
            }

            Section {
                LabeledContent("Auto reconnect", value: "On")
                LabeledContent("Battery use", value: "Event-driven")
                Button("Re-pair Watch", role: .destructive) { confirmForget = true }
            } header: {
                Text("Connection")
            } footer: {
                Text("Your watch reconnects by itself when it's back in range. Re-pair only if the connection keeps failing.")
            }

            Section {
                NavigationLink { NotificationSettingsView(link: link) } label: {
                    Text("Hide sensitive content")
                }
                Button(iconsCleared ? "Cached Icons Deleted" : "Delete Cached App Icons") {
                    AppIconService.shared.clearCache()
                    iconsCleared = true
                }
                .disabled(iconsCleared)
                LabeledContent("Analytics", value: "None")
            } header: {
                Text("Privacy")
            } footer: {
                Text("Bridge has no account, server or analytics. Notifications go straight from iOS to your watch. The only internet use is fetching official app icons from Apple's App Store, by app ID only. To delete notification history, use Clear all on your watch.")
            }

            Section("Advanced") {
                NavigationLink { DiagnosticsView(link: link) } label: {
                    Label("Diagnostics", systemImage: "stethoscope")
                }
            }
        }
        .navigationTitle("Settings")
        .task { alerts = await FindMyPhone.shared.authorizationStatus() }
        .confirmationDialog("Re-pair your watch?", isPresented: $confirmForget, titleVisibility: .visible) {
            Button("Forget Watch", role: .destructive, action: link.forgetWatch)
        } message: {
            Text("Then open Settings › Bluetooth, tap ⓘ next to your watch and choose Forget This Device, and set it up again from the home screen.")
        }
    }
}

private extension SettingsView {
    var alertsText: String {
        switch alerts {
        case .authorized, .provisional, .ephemeral: "Allowed"
        case .denied: "Off"
        case .notDetermined: "Not set up"
        default: "–"
        }
    }
}

// MARK: - Diagnostics (technical details live only here)

struct DiagnosticsView: View {
    let link: WatchLink

    var body: some View {
        List {
            Section("Link") {
                LabeledContent("State", value: link.phase.title)
                LabeledContent("Protocol", value: link.protocolVersion.map { "v\($0)" } ?? "–")
                LabeledContent("Capabilities", value: capabilityList)
            }
            if let s = link.watchStatus {
                Section("Watch") {
                    LabeledContent("Model", value: s.model)
                    LabeledContent("Android", value: s.osVersion)
                    LabeledContent("Bridge", value: s.appVersion)
                    LabeledContent("ANCS", value: s.notificationsReady ? "Subscribed" : "Not subscribed")
                    LabeledContent("AMS", value: s.mediaReady ? "Ready" : "Not ready")
                    LabeledContent("Inbox", value: "\(s.inboxCount)")
                }
            }
            Section("iPhone") {
                LabeledContent("iOS", value: UIDevice.current.systemVersion)
                LabeledContent("App", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "–")
            }
            Section {
                ForEach(Array(link.diagnostics.reversed().enumerated()), id: \.offset) { _, line in
                    Text(line).font(.caption.monospaced()).foregroundStyle(.secondary)
                }
            } header: {
                Text("Log")
            } footer: {
                Text("States and error codes only. Bridge never logs notification content, names or numbers.")
            }
        }
        .navigationTitle("Diagnostics")
    }

    private var capabilityList: String {
        let c = link.capabilities
        let names: [(BridgeProtocol.Capabilities, String)] = [
            (.ancs, "ANCS"), (.media, "AMS"), (.settings, "Settings"), (.deviceStatus, "Status"), (.find, "Find"), (.prefs, "Prefs"),
        ]
        let on = names.filter { c.contains($0.0) }.map(\.1)
        return on.isEmpty ? "–" : on.joined(separator: ", ")
    }
}

// MARK: - Connection guide and About

struct ConnectionGuideView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                guide("Notifications don't appear", symbol: "bell.slash") {
                    Text("iOS shares notifications with your watch only after you allow it:")
                    ShareNotificationsSteps()
                    Text("Apps whose notifications are off in iOS Settings don't reach the watch either.")
                }
                guide("The watch won't connect", symbol: "antenna.radiowaves.left.and.right.slash") {
                    Text("Keep Bridge installed on the watch and Bluetooth on for both. The watch reconnects on its own when it's in range, even after a restart.")
                }
                guide("Pairing fails", symbol: "lock.trianglebadge.exclamationmark") {
                    Text("On this iPhone, open **Settings › Bluetooth**, tap **ⓘ** next to your watch and choose **Forget This Device**. Then pair again from the Bridge home screen and confirm the code on both devices.")
                }
                guide("Find iPhone doesn't ring", symbol: "iphone.slash") {
                    Text("iOS only lets Bridge ring while it's running in the background. If you swipe Bridge away in the app switcher, open it once again. A phone on silent vibrates instead of playing the sound.")
                }
            }
            .padding(20)
        }
        .background(Theme.background)
        .navigationTitle("Connection Guide")
    }

    private func guide<Content: View>(_ title: String, symbol: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(title, systemImage: symbol).font(.headline)
            content().font(.callout).foregroundStyle(.secondary)
        }
        .card()
    }
}

struct AboutView: View {
    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Bridge").font(.title2.bold())
                    Text("Bring your Wear OS watch closer to your iPhone.").foregroundStyle(.secondary)
                }
                .padding(.vertical, 6)
                LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "–")
            }
            Section("Your data stays on your devices") {
                Text("Notifications travel from iOS to your watch over Bluetooth using Apple's Notification Center Service. They never pass through this app or any server. Settings are stored on your watch. There's no account and no analytics.")
                    .font(.callout)
            }
            Section("Not affiliated") {
                Text("Bridge isn't made by Apple, Samsung or Google. Galaxy Watch is a trademark of Samsung Electronics. iPhone is a trademark of Apple Inc.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("About")
    }
}
