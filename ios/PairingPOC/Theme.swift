import SwiftUI

/// Visual language: dark-first, calm, high contrast. Status is always icon + words, never colour alone.
enum Theme {
    static let background = Color.black
    static let card = Color(white: 0.11)
    static let cardStroke = Color(white: 0.18)
    static let corner: CGFloat = 22
}

extension View {
    /// A soft, rounded surface for grouping content.
    func card(padding: CGFloat = 18) -> some View {
        self
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: Theme.corner, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Theme.corner, style: .continuous).stroke(Theme.cardStroke, lineWidth: 0.5))
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .frame(maxWidth: .infinity, minHeight: 54)
            .background(Color.accentColor.opacity(isEnabled ? 1 : 0.35), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .foregroundStyle(.white)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.snappy(duration: 0.15), value: configuration.isPressed)
    }
}

struct SecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(Color(white: 0.16), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .foregroundStyle(.primary)
            .opacity(configuration.isPressed ? 0.75 : 1)
    }
}

/// A coloured rounded-square icon, like Settings rows.
struct IconTile: View {
    let symbol: String
    let tint: Color
    var size: CGFloat = 30

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.5, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(tint.gradient, in: RoundedRectangle(cornerRadius: size * 0.27, style: .continuous))
            .accessibilityHidden(true)
    }
}

// MARK: - Link status presentation

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
struct StatusPill: View {
    let phase: WatchLink.Phase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Label(phase.title, systemImage: phase.symbol)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(phase.tint)
            .symbolEffect(.pulse, options: .repeating, isActive: phase == .connected && !reduceMotion)
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .background(phase.tint.opacity(0.14), in: Capsule())
            .contentTransition(.symbolEffect(.replace))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Status: \(phase.title)")
    }
}

/// Round watch silhouette with a gentle radar pulse while it's looking or connecting.
struct WatchGlyph: View {
    let phase: WatchLink.Phase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pulse = false

    var body: some View {
        ZStack {
            if phase.isBusy && !reduceMotion {
                ForEach(0..<2) { i in
                    Circle()
                        .stroke(phase.tint.opacity(0.35), lineWidth: 1.5)
                        .scaleEffect(pulse ? 1.22 : 0.9)
                        .opacity(pulse ? 0 : 1)
                        .animation(.easeOut(duration: 1.8).repeatForever(autoreverses: false).delay(Double(i) * 0.9), value: pulse)
                }
                .onAppear { pulse = true }
                .onDisappear { pulse = false }
            }
            // Strap stubs, so it reads as a watch rather than a button.
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color(white: 0.13))
                .frame(width: 58, height: 170)
            Circle()
                .fill(LinearGradient(colors: [Color(white: 0.17), Color(white: 0.07)], startPoint: .top, endPoint: .bottom))
                .overlay(Circle().stroke(Color(white: 0.32), lineWidth: 7))
                .overlay(Circle().stroke(Color(white: 0.5), lineWidth: 0.5).padding(3.5))
                .padding(16)
            Image(systemName: phase == .connected ? "bell.badge.fill" : "dot.radiowaves.left.and.right")
                .font(.system(size: 34, weight: .semibold))
                .foregroundStyle(phase.tint)
                .contentTransition(.symbolEffect(.replace))
                .symbolEffect(.variableColor.iterative, isActive: phase.isBusy && !reduceMotion)
        }
        .accessibilityHidden(true)
    }
}

extension Color {
    init(argb: UInt32) {
        self.init(
            red: Double((argb >> 16) & 0xFF) / 255,
            green: Double((argb >> 8) & 0xFF) / 255,
            blue: Double(argb & 0xFF) / 255
        )
    }
}
