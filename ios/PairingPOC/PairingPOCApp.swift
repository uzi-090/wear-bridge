import SwiftUI

@main
struct PairingPOCApp: App {
    @State private var link = WatchLink()
    @State private var findMyPhone = FindMyPhone.shared
    @AppStorage("onboarded") private var onboarded = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            Group {
                // Legacy approval migrates onboarding in DevicePairing. Discovering a watch
                // during first-run setup must not discard the remaining onboarding steps.
                if onboarded {
                    HomeView(link: link)
                } else {
                    OnboardingView(link: link) { onboarded = true }
                }
            }
            .preferredColorScheme(.dark)
            .fullScreenCover(isPresented: Binding(get: { findMyPhone.ringing }, set: { _ in })) {
                FindingPhoneView { findMyPhone.found() }
            }
            .onChange(of: scenePhase) { _, phase in
                DiagFile.append("app scenePhase=\(phase)")
                if phase == .active {
                    findMyPhone.appBecameActive()
                    Task { await ContactSync.shared.refreshAvailable() }
                }
            }
        }
    }
}

/// Shown while the watch is ringing this iPhone.
private struct FindingPhoneView: View {
    let onFound: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 28) {
            Spacer()
            Image(systemName: "iphone.radiowaves.left.and.right")
                .font(.system(size: 96))
                .foregroundStyle(.blue.gradient)
                .symbolEffect(.variableColor.iterative, isActive: !reduceMotion)
                .accessibilityHidden(true)
            Text("Your watch is looking for this iPhone")
                .font(.title.bold())
                .multilineTextAlignment(.center)
            Spacer()
            Button("Found It", action: onFound)
                .buttonStyle(PrimaryButtonStyle())
        }
        .padding(28)
        .background(Theme.background)
    }
}
