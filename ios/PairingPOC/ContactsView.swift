@preconcurrency import Contacts
import SwiftUI

/// Contacts on the watch: explain, then ask for access; then one switch, sync status and favourites.
struct ContactsView: View {
    let link: WatchLink
    @State private var sync = ContactSync.shared

    var body: some View {
        List {
            if sync.removalPending { Section { Text("Removal pending — reconnect your watch.") } }
            if let error = sync.lastError { Section { Text(error).foregroundStyle(.secondary) } }
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
                        } else if sync.awaitingConfirmation {
                            Text("Waiting for watch confirmation…")
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
                        NavigationLink { WatchContactsView() } label: {
                            LabeledContent("Contacts on Watch", value: "\(sync.onWatch.count)")
                        }
                        Toggle("Add new contacts automatically", isOn: Binding(
                            get: { sync.selection == .all },
                            set: { sync.setSelection($0 ? .all : .chosen) }
                        ))
                    } footer: {
                        Text(sync.selection == .all
                             ? "People you add to your iPhone later also appear on your watch."
                             : "Only the people you add appear on your watch.")
                    }
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

/// Exactly who's on the watch. Swipe or Edit to remove; ＋ to add more.
private struct WatchContactsView: View {
    @State private var sync = ContactSync.shared
    @State private var query = ""
    @State private var selection = Set<String>()
    @State private var adding = false
    @Environment(\.editMode) private var editMode

    var body: some View {
        let list = sync.onWatch.filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) || $0.phones.contains { $0.contains(query) } }
        List(selection: $selection) {
            if sync.onWatch.isEmpty {
                ContentUnavailableView {
                    Label("No contacts on your watch", systemImage: "person.crop.circle.badge.plus")
                } description: {
                    Text("Add the people you want to see and call from your wrist.")
                } actions: {
                    Button("Add Contacts") { adding = true }.buttonStyle(.borderedProminent)
                }
            }
            ForEach(list, id: \.id) { c in
                ContactRow(contact: c)
                    .swipeActions {
                        Button("Remove", role: .destructive) { sync.remove([c.id]) }
                    }
            }
        }
        .searchable(text: $query, prompt: "Search contacts on watch")
        .navigationTitle("Contacts on Watch")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { adding = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Add contacts")
            }
            ToolbarItem(placement: .topBarTrailing) { EditButton() }
            if editMode?.wrappedValue.isEditing == true {
                ToolbarItem(placement: .bottomBar) {
                    Button("Remove \(selection.count) from Watch", role: .destructive) {
                        sync.remove(selection)
                        selection.removeAll()
                        editMode?.wrappedValue = .inactive
                    }
                    .disabled(selection.isEmpty)
                }
            }
        }
        .sheet(isPresented: $adding) { AddContactsView() }
        .task { if sync.available.isEmpty { await sync.refreshAvailable() } }
    }
}

/// People not on the watch yet: tick any, then Add.
private struct AddContactsView: View {
    @State private var sync = ContactSync.shared
    @State private var query = ""
    @State private var picked = Set<String>()
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let onWatch = Set(sync.onWatch.map(\.id))
        let list = sync.available.filter { !onWatch.contains($0.id) }
            .filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) || $0.phones.contains { $0.contains(query) } }
        NavigationStack {
            List {
                if list.isEmpty {
                    Text(query.isEmpty ? "Everyone is already on your watch." : "No matches.").foregroundStyle(.secondary)
                }
                ForEach(list, id: \.id) { c in
                    let on = picked.contains(c.id)
                    Button {
                        if on { picked.remove(c.id) } else { picked.insert(c.id) }
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: on ? "checkmark.circle.fill" : "circle")
                                .font(.title3)
                                .foregroundStyle(on ? Color.accentColor : .secondary)
                                .contentTransition(.symbolEffect(.replace))
                            ContactRow(contact: c)
                        }
                        .contentShape(Rectangle())
                    }
                    .foregroundStyle(.primary)
                    .accessibilityValue(on ? "Selected" : "")
                }
            }
            .searchable(text: $query, prompt: "Search contacts")
            .navigationTitle("Add to Watch")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(picked.isEmpty ? "Add" : "Add (\(picked.count))") {
                        sync.add(picked)
                        dismiss()
                    }
                    .fontWeight(.semibold)
                    .disabled(picked.isEmpty)
                }
                ToolbarItem(placement: .bottomBar) {
                    Button(query.isEmpty ? "Select All" : "Select These") { picked.formUnion(list.map(\.id)) }
                        .disabled(list.isEmpty)
                }
            }
            .sensoryFeedback(.selection, trigger: picked.count)
        }
    }
}

private struct ContactRow: View {
    let contact: SyncContact

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(contact.name.isEmpty ? (contact.phones.first ?? "No name") : contact.name)
            if let phone = contact.phones.first, !contact.name.isEmpty {
                Text(phone).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }
}

private struct FavoritesView: View {
    @State private var sync = ContactSync.shared
    @State private var query = ""

    var body: some View {
        // Only people who are on the watch can be favourites there.
        let list = sync.onWatch.filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) }
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
