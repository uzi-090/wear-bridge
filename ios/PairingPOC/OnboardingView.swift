import SwiftUI

/// First-run setup. Each permission is explained before iOS asks for it, and every step shows live state
/// rather than instructions alone.
struct OnboardingView: View {
    let link: WatchLink
    let onFinish: () -> Void

    @State private var step = 0
    private let count = 6

    var body: some View {
        VStack(spacing: 0) {
            ProgressDots(count: count, current: step)
                .padding(.top, 12)

            Group {
                switch step {
                case 0: WelcomeStep()
                case 1: BluetoothStep()
                case 2: NearbyStep(link: link)
                case 3: NotificationAccessStep(link: link)
                case 4: ChooseStep(link: link)
                default: ReadyStep(link: link)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity),
                                    removal: .move(edge: .leading).combined(with: .opacity)))
            .id(step)

            footer
                .padding(.horizontal, 24)
                .padding(.bottom, 16)
        }
        .background(Theme.background)
        .animation(.smooth(duration: 0.35), value: step)
        .animation(.smooth, value: link.phase)
    }

    @ViewBuilder
    private var footer: some View {
        VStack(spacing: 12) {
            switch step {
            case 1:
                Button("Continue") {
                    link.start() // iOS asks for Bluetooth now, right after the explanation
                    step += 1
                }
                .buttonStyle(PrimaryButtonStyle())
            case 2:
                if link.phase == .connected {
                    Button("Continue") { step += 1 }.buttonStyle(PrimaryButtonStyle())
                } else {
                    Button(link.phase.isBusy ? "Looking…" : "Find Watch", action: link.findWatch)
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(link.phase.isBusy || !link.bluetoothStarted)
                }
            case 3:
                Button(link.watchStatus?.notificationsReady == true ? "Continue" : "I've Turned It On") { step += 1 }
                    .buttonStyle(PrimaryButtonStyle())
            case count - 1:
                Button("Done", action: onFinish).buttonStyle(PrimaryButtonStyle())
            default:
                Button("Continue") { step += 1 }.buttonStyle(PrimaryButtonStyle())
            }
            if step > 0 && step < count - 1 {
                Button("Back") { step -= 1 }
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

private struct ProgressDots: View {
    let count: Int
    let current: Int

    var body: some View {
        HStack(spacing: 6) {
            ForEach(0..<count, id: \.self) { i in
                Capsule()
                    .fill(i == current ? Color.primary : Color(white: 0.3))
                    .frame(width: i == current ? 18 : 6, height: 6)
            }
        }
        .animation(.snappy, value: current)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Step \(current + 1) of \(count)")
    }
}

/// Shared layout: art on top, a large title, then a short explanation.
private struct StepLayout<Art: View, Extra: View>: View {
    let title: String
    let message: String
    @ViewBuilder let art: Art
    @ViewBuilder let extra: Extra

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                art
                    .frame(height: 200)
                    .padding(.top, 36)
                VStack(spacing: 12) {
                    Text(title)
                        .font(.largeTitle.bold())
                        .multilineTextAlignment(.center)
                    Text(message)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                extra
            }
            .padding(.horizontal, 24)
        }
        .scrollBounceBehavior(.basedOnSize)
    }
}

private struct WelcomeStep: View {
    var body: some View {
        StepLayout(
            title: "Your Galaxy Watch.\nYour iPhone.",
            message: "Notifications, calls and music controls on your wrist. Everything stays on your own devices."
        ) {
            WatchGlyph(phase: .connected).frame(width: 170, height: 170)
        } extra: {
            EmptyView()
        }
    }
}

private struct BluetoothStep: View {
    var body: some View {
        StepLayout(
            title: "Connect directly over Bluetooth.",
            message: "No account and no cloud. Your watch talks to your iPhone directly, so Bridge needs Bluetooth access next."
        ) {
            Image(systemName: "dot.radiowaves.left.and.right")
                .font(.system(size: 84, weight: .medium))
                .foregroundStyle(.blue.gradient)
                .symbolEffect(.variableColor.iterative.reversing)
        } extra: {
            Label("Nothing is sent to the internet.", systemImage: "lock.fill")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}

private struct NearbyStep: View {
    let link: WatchLink

    var body: some View {
        StepLayout(
            title: link.phase == .connected ? "Connected." : "Bring your Galaxy Watch nearby.",
            message: link.phase.detail ?? (link.phase == .connected
                ? "Your watch and iPhone are paired."
                : "Open Bridge on your watch and keep it close to your iPhone.")
        ) {
            WatchGlyph(phase: link.phase).frame(width: 170, height: 170)
        } extra: {
            VStack(spacing: 12) {
                StatusPill(phase: link.phase)
                if link.phase == .searching && !link.showingAllNearby {
                    Button("Can't find it? Show nearby devices", action: link.showAllNearby)
                        .font(.callout)
                }
                if link.phase == .searching && link.showingAllNearby {
                    NearbyList(items: link.nearby, onChoose: link.choose)
                }
            }
        }
    }
}

/// iOS shares notifications with an accessory only after the user allows it in Bluetooth settings, and
/// apps can't open that screen directly. The watch tells us when it starts receiving them.
private struct NotificationAccessStep: View {
    let link: WatchLink

    var body: some View {
        let ready = link.watchStatus?.notificationsReady == true
        StepLayout(
            title: "Enable notification access.",
            message: "iOS asks you to allow this once. Your notifications then go straight from iOS to your watch, never through this app."
        ) {
            Image(systemName: ready ? "checkmark.circle.fill" : "bell.and.waves.left.and.right.fill")
                .font(.system(size: 84))
                .foregroundStyle(ready ? AnyShapeStyle(.green.gradient) : AnyShapeStyle(.orange.gradient))
                .contentTransition(.symbolEffect(.replace))
        } extra: {
            if ready {
                Label("Your watch is receiving notifications", systemImage: "checkmark")
                    .font(.headline)
                    .foregroundStyle(.green)
            } else {
                ShareNotificationsSteps()
            }
        }
    }
}

/// The three steps to turn on "Share System Notifications". Reused by the connection guide.
struct ShareNotificationsSteps: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            step(1, "Open **Settings › Bluetooth** on this iPhone.")
            step(2, "Tap **ⓘ** next to your watch.")
            step(3, "Turn on **Share System Notifications**.")
        }
        .card()
    }

    private func step(_ n: Int, _ text: LocalizedStringKey) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text("\(n)")
                .font(.footnote.bold())
                .frame(width: 22, height: 22)
                .background(Color(white: 0.22), in: Circle())
            Text(text).font(.callout)
        }
    }
}

private struct ChooseStep: View {
    let link: WatchLink

    var body: some View {
        StepLayout(
            title: "Choose what appears on your wrist.",
            message: "You can fine-tune each app later in Notifications."
        ) {
            Image(systemName: "applewatch.radiowaves.left.and.right")
                .font(.system(size: 84))
                .foregroundStyle(.blue.gradient)
        } extra: {
            if let settings = link.watchSettings {
                VStack(spacing: 10) {
                    ForEach(FilterMode.allCases) { mode in
                        Button { link.setMode(mode) } label: {
                            HStack(alignment: .top, spacing: 12) {
                                Image(systemName: settings.mode == mode ? "checkmark.circle.fill" : "circle")
                                    .font(.title3)
                                    .foregroundStyle(settings.mode == mode ? Color.accentColor : .secondary)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(mode.title).font(.headline)
                                    Text(mode.detail).font(.footnote).foregroundStyle(.secondary)
                                }
                                Spacer(minLength: 0)
                            }
                            .card(padding: 14)
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(settings.mode == mode ? .isSelected : [])
                    }
                }
            } else {
                Text("Connect your watch to choose. Until then, everything appears.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

private struct ReadyStep: View {
    let link: WatchLink
    @State private var alertsAllowed: Bool?

    var body: some View {
        StepLayout(
            title: "You're ready.",
            message: "Notifications, calls and music now reach your watch, even when this app is closed."
        ) {
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 92))
                .foregroundStyle(.green.gradient)
                .symbolEffect(.bounce, value: true)
        } extra: {
            VStack(alignment: .leading, spacing: 12) {
                Label("Find iPhone from your watch", systemImage: "iphone.radiowaves.left.and.right")
                    .font(.headline)
                Text("Bridge can play a sound on this iPhone when you tap Find iPhone on your watch. That needs permission to show alerts.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if alertsAllowed == true {
                    Label("Alerts allowed", systemImage: "checkmark").foregroundStyle(.green).font(.callout.weight(.semibold))
                } else {
                    Button("Allow Alerts") {
                        Task { alertsAllowed = await FindMyPhone.shared.requestPermission() }
                    }
                    .buttonStyle(SecondaryButtonStyle())
                    if alertsAllowed == false {
                        Text("You can turn alerts on later in Settings › Notifications › Bridge.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .card()
        }
        .task {
            let status = await FindMyPhone.shared.authorizationStatus()
            if status == .authorized { alertsAllowed = true } else if status == .denied { alertsAllowed = false }
        }
    }
}

struct NearbyList: View {
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
                    .contentShape(Rectangle())
                }
                .foregroundStyle(.primary)
                Divider()
            }
            if items.isEmpty {
                Text("Searching…").foregroundStyle(.secondary).padding(.vertical, 12)
            }
        }
        .padding(.horizontal, 16)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
