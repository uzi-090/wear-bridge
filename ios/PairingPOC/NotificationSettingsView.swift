import SwiftUI

/// Notification settings for the watch. The watch receives notifications directly from iOS (ANCS)
/// and applies these rules itself. This screen edits what the watch stores, over the bridge channel.
struct NotificationSettingsView: View {
    let link: WatchLink

    var body: some View {
        Group {
            if let settings = link.watchSettings {
                List {
                    Section {
                        Picker("On your wrist", selection: Binding(get: { settings.mode }, set: link.setMode)) {
                            ForEach(FilterMode.allCases) { Text($0.title).tag($0) }
                        }
                    } footer: {
                        Text(settings.mode.detail)
                    }

                    Section {
                        ForEach(settings.apps) { app in
                            NavigationLink {
                                AppRuleView(link: link, appId: app.id)
                            } label: {
                                HStack(spacing: 12) {
                                    AppBadge(app: app, size: 30)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(app.displayName)
                                        Text(summary(app)).font(.footnote).foregroundStyle(.secondary)
                                    }
                                }
                                .padding(.vertical, 2)
                            }
                        }
                    } header: {
                        Text("Apps")
                    } footer: {
                        Text("Apps appear here after they send their first notification. iOS doesn't let apps see what's installed on your iPhone.")
                    }
                }
                .refreshable { link.refreshSettings() }
            } else if link.phase == .connected {
                ProgressView("Loading watch settings…")
            } else {
                ContentUnavailableView(
                    "Watch not connected",
                    systemImage: "applewatch.slash",
                    description: Text("Notification settings live on your watch. Connect it to change them.")
                )
            }
        }
        .navigationTitle("Notifications")
    }

    private func summary(_ app: WatchApp) -> String {
        guard app.enabled else { return "Off" }
        var parts = [app.vibrate ? "On" : "Silent"]
        if app.hidesContent { parts.append("content hidden") }
        return parts.joined(separator: " · ")
    }
}

private struct AppRuleView: View {
    let link: WatchLink
    let appId: String

    var body: some View {
        if let app = link.watchSettings?.apps.first(where: { $0.id == appId }) {
            Form {
                Section {
                    HStack(spacing: 14) {
                        AppBadge(app: app, size: 44)
                        Text(app.displayName).font(.title3.weight(.semibold))
                    }
                    .padding(.vertical, 4)
                }
                Section {
                    Toggle("Show on watch", isOn: Binding(get: { app.enabled }, set: { link.setRule(for: app, enabled: $0) }))
                    Toggle("Vibrate", isOn: Binding(get: { app.vibrate }, set: { link.setRule(for: app, vibrate: $0) }))
                        .disabled(!app.enabled)
                    Toggle("Hide content", isOn: Binding(get: { app.hidesContent }, set: { link.setRule(for: app, hideContent: $0) }))
                        .disabled(!app.enabled)
                } footer: {
                    Text("Hidden notifications show only the app name on your watch until you tap them.")
                }
            }
            .navigationTitle(app.displayName)
            .navigationBarTitleDisplayMode(.inline)
        } else {
            ContentUnavailableView("Watch not connected", systemImage: "applewatch.slash")
        }
    }
}

/// The same badge the watch shows: the bundled logo (or initial) in white on the app's brand colour.
struct AppBadge: View {
    let app: WatchApp
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle().fill(Color(argb: app.color))
            if !app.logo.isEmpty {
                Image(app.logo.hasPrefix("glyph_") ? app.logo : "logo_" + app.logo)
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .foregroundStyle(.white)
                    .padding(size * 0.21)
            } else {
                Text(String(app.displayName.first(where: { $0.isLetter || $0.isNumber }) ?? "•").uppercased())
                    .font(.system(size: size * 0.48, weight: .bold))
                    .foregroundStyle(.white)
            }
        }
        .frame(width: size, height: size)
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
