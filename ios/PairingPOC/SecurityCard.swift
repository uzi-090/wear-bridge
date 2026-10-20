import SwiftUI

/// App-level pairing states. The watch's user approves this app once, with the same code on both screens.
struct SecurityCard: View {
    let link: WatchLink

    var body: some View {
        switch link.security {
        case .awaitingApproval(let code):
            VStack(spacing: 12) {
                Label("Allow on your watch", systemImage: "applewatch.radiowaves.left.and.right")
                    .font(.headline)
                Text(code.prefix(3) + " " + code.suffix(3))
                    .font(.system(size: 44, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                    .accessibilityLabel("Code \(code.map(String.init).joined(separator: " "))")
                Text("Tap Allow on your watch if it shows the same code. This keeps other apps on this iPhone away from your watch.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .card()
        case .denied:
            VStack(alignment: .leading, spacing: 10) {
                Label("Your watch didn't allow this app", systemImage: "hand.raised.fill")
                    .font(.headline)
                    .foregroundStyle(.orange)
                Text("Device features stay unavailable until you approve pairing on the watch.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button("Ask Again", action: link.retryAuthorization).buttonStyle(SecondaryButtonStyle())
            }
            .card()
        case .watchIdentityChanged:
            VStack(alignment: .leading, spacing: 10) {
                Label("Your watch has a new identity", systemImage: "exclamationmark.shield.fill")
                    .font(.headline)
                    .foregroundStyle(.orange)
                Text("This happens after the watch is reset or Bridge is reinstalled on it. Only trust it if you did that.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button("Trust This Watch", action: link.trustNewWatchIdentity).buttonStyle(SecondaryButtonStyle())
            }
            .card()
        case .handshaking:
            Label("Securing the connection…", systemImage: "lock.rotation")
                .font(.footnote)
                .foregroundStyle(.secondary)
        case .none, .secure:
            EmptyView()
        }
    }
}
