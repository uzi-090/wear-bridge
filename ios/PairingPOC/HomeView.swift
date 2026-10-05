import SwiftUI

/// "Connect Watch → Everything Just Works." One glance answers "is it working?", then a short list of
/// things to do. Technical details live under Settings › Advanced.
struct HomeView: View {
    let link: WatchLink

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 22) {
                    hero
                    if link.phase == .connected { SecurityCard(link: link) }
                    if link.phase == .connected || link.watchStatus != nil {
                        StatsCard(link: link)
                    }
                    if link.phase == .connected, let status = link.watchStatus, !status.notificationsReady {
                        NotificationsOffCard()
                    }
                    if link.phase == .searching && link.showingAllNearby {
                        NearbyList(items: link.nearby, onChoose: link.choose)
                    }
                    primaryList
                    secondaryList
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 32)
            }
            .background(Theme.background)
            .animation(.smooth, value: link.phase)
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var hero: some View {
        VStack(spacing: 14) {
            WatchGlyph(phase: link.phase)
                .frame(width: 150, height: 150)
                .padding(.top, 12)
            Text(link.watchStatus?.displayName ?? link.watchName ?? "Galaxy Watch")
                .font(.largeTitle.bold())
                .multilineTextAlignment(.center)
            StatusPill(phase: link.phase)
            if link.phase != .connected, let detail = link.phase.detail {
                Text(detail)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            heroAction
        }
    }

    @ViewBuilder
    private var heroAction: some View {
        switch link.phase {
        case .notSetUp:
            Button("Find Watch", action: link.findWatch).buttonStyle(PrimaryButtonStyle())
        case .needsAttention:
            Button("Try Again", action: link.findWatch).buttonStyle(PrimaryButtonStyle())
        case .searching:
            HStack(spacing: 20) {
                if !link.showingAllNearby { Button("Show nearby devices", action: link.showAllNearby) }
                Button("Cancel", role: .cancel, action: link.cancelSearch)
            }
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

    private var primaryList: some View {
        VStack(spacing: 0) {
            NavigationLink { NotificationSettingsView(link: link) } label: {
                Row(symbol: "bell.badge.fill", tint: .red, title: "Notifications", value: link.watchSettings?.mode.title)
            }
            Divider().padding(.leading, 58)
            NavigationLink { FindWatchView(link: link) } label: {
                Row(symbol: "speaker.wave.2.fill", tint: .blue, title: "Find Watch", value: link.ringingWatch ? "Ringing" : nil)
            }
            Divider().padding(.leading, 58)
            NavigationLink { CallsView(link: link) } label: {
                Row(symbol: "phone.fill", tint: .green, title: "Calls", value: nil)
            }
            Divider().padding(.leading, 58)
            NavigationLink { MediaInfoView(link: link) } label: {
                Row(symbol: "play.fill", tint: .pink, title: "Music & Media", value: nil)
            }
            Divider().padding(.leading, 58)
            NavigationLink { SettingsView(link: link) } label: {
                Row(symbol: "gearshape.fill", tint: .gray, title: "Settings", value: nil)
            }
        }
        .padding(.vertical, 4)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: Theme.corner, style: .continuous))
    }

    private var secondaryList: some View {
        VStack(spacing: 0) {
            NavigationLink { ConnectionGuideView() } label: {
                Row(symbol: "questionmark.circle.fill", tint: .indigo, title: "Connection Guide", value: nil)
            }
            Divider().padding(.leading, 58)
            NavigationLink { AboutView() } label: {
                Row(symbol: "info.circle.fill", tint: .teal, title: "About", value: nil)
            }
        }
        .padding(.vertical, 4)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: Theme.corner, style: .continuous))
    }
}

/// A Settings-style row: icon tile, title, optional value, chevron.
struct Row: View {
    let symbol: String
    let tint: Color
    let title: String
    let value: String?

    var body: some View {
        HStack(spacing: 14) {
            IconTile(symbol: symbol, tint: tint)
            Text(title).foregroundStyle(.primary)
            Spacer(minLength: 8)
            if let value {
                Text(value).foregroundStyle(.secondary).lineLimit(1)
            }
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 52)
        .contentShape(Rectangle())
    }
}

/// Battery · Notifications · Last synchronized. Words and icons, never colour alone.
private struct StatsCard: View {
    let link: WatchLink

    var body: some View {
        HStack(spacing: 0) {
            stat(
                symbol: batterySymbol,
                value: link.watchStatus?.battery.map { "\($0)%" } ?? "–",
                label: link.watchStatus?.charging == true ? "Charging" : "Battery"
            )
            Divider().frame(height: 44)
            stat(
                symbol: notificationsOn ? "bell.fill" : "bell.slash",
                value: notificationsOn ? "On" : (link.phase == .connected ? "Off" : "–"),
                label: "Notifications"
            )
            Divider().frame(height: 44)
            TimelineView(.periodic(from: .now, by: 30)) { context in
                stat(symbol: "arrow.triangle.2.circlepath", value: lastSync(now: context.date), label: "Last sync")
            }
        }
        .padding(.vertical, 16)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: Theme.corner, style: .continuous))
    }

    private var notificationsOn: Bool { link.phase == .connected && link.watchStatus?.notificationsReady == true }

    private var batterySymbol: String {
        guard let s = link.watchStatus, let b = s.battery else { return "battery.0percent" }
        if s.charging { return "battery.100percent.bolt" }
        switch b {
        case ..<13: return "battery.0percent"
        case ..<38: return "battery.25percent"
        case ..<63: return "battery.50percent"
        case ..<88: return "battery.75percent"
        default: return "battery.100percent"
        }
    }

    private func lastSync(now: Date) -> String {
        guard let date = link.lastSync else { return "–" }
        if link.phase == .connected || now.timeIntervalSince(date) < 60 { return "Just now" }
        return date.formatted(.relative(presentation: .named, unitsStyle: .abbreviated))
    }

    private func stat(symbol: String, value: String, label: String) -> some View {
        VStack(spacing: 4) {
            Image(systemName: symbol).font(.title3).foregroundStyle(.secondary).accessibilityHidden(true)
            Text(value).font(.headline).monospacedDigit().lineLimit(1).minimumScaleFactor(0.7)
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }
}

private struct NotificationsOffCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Turn on notifications for your watch", systemImage: "exclamationmark.triangle.fill")
                .font(.headline)
                .foregroundStyle(.orange)
            ShareNotificationsSteps()
        }
    }
}
