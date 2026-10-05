import SwiftUI

struct ContentView: View {
    let link: WatchLink

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 28) {
                    WatchGlyph(phase: link.phase)
                        .frame(width: 148, height: 148)
                        .padding(.top, 32)

                    VStack(spacing: 10) {
                        Text(link.watchName ?? "Your watch")
                            .font(.largeTitle.bold())
                            .multilineTextAlignment(.center)
                        StatusLabel(phase: link.phase)
                        if let detail = link.phase.detail {
                            Text(detail)
                                .font(.callout)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                        }
                    }

                    actions

                    if link.phase == .searching && link.showingAllNearby {
                        NearbyList(items: link.nearby, onChoose: link.choose)
                    }

                    if link.phase == .connected {
                        FinishSetupCard()
                    }

                    DiagnosticsSection(link: link)
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 40)
            }
            .background(Color.black)
            .animation(.smooth, value: link.phase)
        }
    }

    @ViewBuilder
    private var actions: some View {
        switch link.phase {
        case .notSetUp:
            Button("Find Watch", action: link.findWatch)
                .buttonStyle(PrimaryButtonStyle())
        case .needsAttention:
            Button("Try Again", action: link.findWatch)
                .buttonStyle(PrimaryButtonStyle())
        case .searching:
            VStack(spacing: 12) {
                if !link.showingAllNearby {
                    Button("Can't find it? Show nearby devices", action: link.showAllNearby)
                        .font(.callout)
                }
                Button("Cancel", role: .cancel, action: link.cancelSearch)
                    .font(.callout)
            }
        case .connected:
            VStack(spacing: 12) {
                NavigationLink {
                    NotificationSettingsView(link: link)
                } label: {
                    Label("Notifications", systemImage: "bell.badge")
                }
                .buttonStyle(PrimaryButtonStyle())
                Button("Send Test Ping", action: link.sendPing)
                    .font(.callout)
                if let lastPing = link.lastPing {
                    Text("Watch replied \(lastPing.formatted(.relative(presentation: .named)))")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Button("Forget Watch", role: .destructive, action: link.forgetWatch)
                    .font(.callout)
            }
        case .reconnecting:
            Button("Forget Watch", role: .destructive, action: link.forgetWatch)
                .font(.callout)
        case .bluetoothUnauthorized:
            Button("Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
            .buttonStyle(PrimaryButtonStyle())
        default:
            EmptyView()
        }
    }
}

// MARK: - Status

extension WatchLink.Phase {
    var title: String {
        switch self {
        case .starting: "Starting…"
        case .bluetoothOff: "Bluetooth is off"
        case .bluetoothUnauthorized: "Bluetooth access needed"
        case .unsupported: "Bluetooth unavailable"
        case .notSetUp: "Not set up"
        case .searching: "Looking for your watch…"
        case .connecting: "Connecting…"
        case .pairing: "Pairing"
        case .connected: "Connected"
        case .reconnecting: "Reconnecting…"
        case .needsAttention: "Needs attention"
        }
    }

    var detail: String? {
        switch self {
        case .bluetoothOff: "Turn on Bluetooth in Control Center."
        case .bluetoothUnauthorized: "Bridge connects to your watch over Bluetooth. Allow Bluetooth for Bridge in Settings."
        case .unsupported: "This device can't use Bluetooth LE."
        case .notSetUp: "Open Bridge on your watch, keep it close, then tap Find Watch."
        case .searching: "Keep your watch nearby with Bridge open."
        case .pairing: "If asked, check that the code matches on your watch, then tap Pair on both."
        case .reconnecting: "Your watch will reconnect automatically when it's nearby."
        case .needsAttention(let message): message
        default: nil
        }
    }

    var symbol: String {
        switch self {
        case .connected: "checkmark.circle.fill"
        case .reconnecting, .connecting, .pairing, .searching, .starting: "arrow.triangle.2.circlepath"
        case .notSetUp: "circle.dashed"
        default: "exclamationmark.triangle.fill"
        }
    }

    var tint: Color {
        switch self {
        case .connected: .green
        case .reconnecting, .connecting, .pairing, .searching, .starting, .notSetUp: .blue
        default: .orange
        }
    }

    var isBusy: Bool {
        switch self {
        case .searching, .connecting, .pairing, .reconnecting: true
        default: false
        }
    }
}

/// Icon + words, so the state never depends on colour alone.
private struct StatusLabel: View {
    let phase: WatchLink.Phase

    var body: some View {
        Label(phase.title, systemImage: phase.symbol)
            .font(.headline)
            .foregroundStyle(phase.tint)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(phase.tint.opacity(0.14), in: Capsule())
            .contentTransition(.symbolEffect(.replace))
            .accessibilityLabel("Status: \(phase.title)")
    }
}

/// Watch silhouette with a gentle pulse while connecting.
private struct WatchGlyph: View {
    let phase: WatchLink.Phase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pulse = false

    var body: some View {
        ZStack {
            if phase.isBusy && !reduceMotion {
                Circle()
                    .stroke(phase.tint.opacity(0.35), lineWidth: 2)
                    .scaleEffect(pulse ? 1.12 : 0.92)
                    .opacity(pulse ? 0 : 1)
                    .animation(.easeOut(duration: 1.6).repeatForever(autoreverses: false), value: pulse)
                    .onAppear { pulse = true }
                    .onDisappear { pulse = false }
            }
            Circle()
                .fill(LinearGradient(colors: [Color(white: 0.16), Color(white: 0.08)], startPoint: .top, endPoint: .bottom))
                .overlay(Circle().stroke(Color(white: 0.28), lineWidth: 6))
                .padding(14)
            Image(systemName: phase == .connected ? "bell.badge.fill" : "dot.radiowaves.left.and.right")
                .font(.system(size: 38, weight: .semibold))
                .foregroundStyle(phase.tint)
                .symbolEffect(.pulse, isActive: phase.isBusy && !reduceMotion)
        }
        .accessibilityHidden(true)
    }
}

private struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(Color.accentColor, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .foregroundStyle(.white)
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}

// MARK: - Sections

private struct NearbyList: View {
    let items: [WatchLink.Nearby]
    let onChoose: (WatchLink.Nearby) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(items) { item in
                Button { onChoose(item) } label: {
                    HStack {
                        Text(item.name)
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                    }
                    .padding(.vertical, 12)
                }
                .foregroundStyle(.primary)
                Divider()
            }
            if items.isEmpty {
                Text("Searching…").foregroundStyle(.secondary).padding(.vertical, 12)
            }
        }
        .padding(.horizontal, 16)
        .background(Color(white: 0.1), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

/// iOS only shares notifications with a Bluetooth accessory once the user allows it, and apps
/// can't deep-link to Bluetooth settings, so we explain the steps instead.
private struct FinishSetupCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Show notifications on your watch", systemImage: "bell.and.waves.left.and.right")
                .font(.headline)
            VStack(alignment: .leading, spacing: 6) {
                Text("1. Open **Settings › Bluetooth**.")
                Text("2. Tap **ⓘ** next to your watch.")
                Text("3. Turn on **Share System Notifications**.")
            }
            .font(.callout)
            .foregroundStyle(.secondary)
            Text("Your notifications go straight from iOS to your watch over Bluetooth. They never pass through this app or the internet.")
                .font(.footnote)
                .foregroundStyle(.tertiary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(Color(white: 0.1), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

private struct DiagnosticsSection: View {
    let link: WatchLink

    var body: some View {
        DisclosureGroup("Diagnostics") {
            VStack(alignment: .leading, spacing: 4) {
                Text("Protocol: \(link.protocolVersion.map { "v\($0)" } ?? "–")")
                ForEach(Array(link.diagnostics.reversed().enumerated()), id: \.offset) { _, line in
                    Text(line)
                }
            }
            .font(.caption.monospaced())
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 8)
        }
        .tint(.secondary)
    }
}
