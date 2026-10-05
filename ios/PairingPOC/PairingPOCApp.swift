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
                // People who paired before onboarding existed go straight home.
                if onboarded || link.hasSavedWatch {
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
                if phase == .active { findMyPhone.appBecameActive() }
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
