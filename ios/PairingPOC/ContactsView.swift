@preconcurrency import Contacts
import SwiftUI

/// Contacts on the watch: explain, then ask for access; then one switch, sync status and favourites.
struct ContactsView: View {
    let link: WatchLink
    @State private var sync = ContactSync.shared

    var body: some View {
        List {
            switch sync.authorization {
            case .notDetermined:
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        Label("Your contacts on your wrist", systemImage: "person.crop.circle.fill")
                            .font(.headline)
                        Text("Bridge copies names, phone numbers and a few emails to your watch, so you can look people up and start calls from your wrist. Nothing goes to the internet.")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                        Button("Allow Contacts") { Task { await sync.requestAccess() } }
                            .buttonStyle(PrimaryButtonStyle())
                            .padding(.top, 4)
                    }
                    .padding(.vertical, 6)
                }
            case .denied, .restricted:
                Section {
                    Text("Bridge can't read your contacts. To sync them to your watch, allow Contacts for Bridge in Settings.")
                    Button("Open Settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                }
            default:
                Section {
                    Toggle("Sync contacts to watch", isOn: Binding(
                        get: { sync.enabled },
                        set: { on in
                            if on { sync.turnOn(link: link) } else { sync.turnOff() }
                        }
                    ))
                    if sync.enabled {
                        LabeledContent("On your watch", value: link.watchStatus.map { "\($0.contactCount)" } ?? "–")
                        if sync.syncing {
                            ProgressView(value: sync.progress) { Text("Syncing…") }
                        } else if let date = sync.lastSynced {
                            LabeledContent("Last synced", value: date.formatted(.relative(presentation: .named)))
                        }
                    }
                } footer: {
                    Text(sync.authorization == .limited
                         ? "Bridge can see only the contacts you chose. Change that in Settings › Bridge › Contacts."
                         : "Only changes are sent after the first sync. Turning this off removes every contact from your watch.")
                }
                if sync.enabled {
                    Section {
                        NavigationLink { FavoritesView() } label: {
                            LabeledContent("Favorites", value: "\(sync.favorites.count)")
                        }
                    } footer: {
                        Text("Favorites appear first on your watch. iOS doesn't share the Phone app's favorites, so choose them here.")
                    }
                }
            }
            if !link.capabilities.contains(.contacts) && link.isConnected {
                Section { Text("Update Bridge on your watch to sync contacts.").foregroundStyle(.secondary) }
            }
        }
        .navigationTitle("Contacts")
        .task { if sync.isAuthorized && sync.available.isEmpty { await sync.refreshAvailable() } }
    }
}

private struct FavoritesView: View {
    @State private var sync = ContactSync.shared
    @State private var query = ""

    var body: some View {
        let list = sync.available.filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) }
        List(list, id: \.id) { c in
            Button { sync.toggleFavorite(c.id) } label: {
                HStack {
                    Text(c.name.isEmpty ? (c.phones.first ?? "No name") : c.name).foregroundStyle(.primary)
                    Spacer()
                    Image(systemName: sync.favorites.contains(c.id) ? "star.fill" : "star")
                        .foregroundStyle(sync.favorites.contains(c.id) ? .yellow : .secondary)
                }
            }
            .accessibilityAddTraits(sync.favorites.contains(c.id) ? .isSelected : [])
        }
        .searchable(text: $query)
        .navigationTitle("Favorites")
        .task { await sync.refreshAvailable() }
    }
}
