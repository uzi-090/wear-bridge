@preconcurrency import Contacts
import CryptoKit
import Foundation
import Observation

/// Contact sync to the watch (§14). Reads the minimal fields with the Contacts framework, then sends
/// only what changed since the last sync: a per-contact digest is kept on the iPhone, and the watch reports
/// the generation it last committed. Any mismatch (watch reset, interrupted sync) means a full resync.
/// Everything travels sealed over the bridge. Nothing goes to any server.
@MainActor
@Observable
final class ContactSync {
    static let shared = ContactSync()

    private(set) var authorization = CNContactStore.authorizationStatus(for: .contacts)
    private(set) var syncing = false
    private(set) var progress: Double = 0
    private(set) var awaitingConfirmation = false
    private(set) var removalPending = UserDefaults.standard.bool(forKey: "contacts.pendingClear")
    @ObservationIgnored private var removalNonce: [UInt8] = Array(UserDefaults.standard.data(forKey: "contacts.clearNonce") ?? Data())
    private(set) var lastError: String?
    @ObservationIgnored private var syncTask: Task<Void, Never>?
    @ObservationIgnored private var confirmationTimer: Task<Void, Never>?
    @ObservationIgnored private var pendingDigests: [String: String]?
    @ObservationIgnored private var syncEpoch: UInt64 = 0
    private(set) var lastSynced: Date? = UserDefaults.standard.object(forKey: "contacts.lastSynced") as? Date
    /// Contacts on the iPhone that are eligible to sync (after the last fetch).
    private(set) var available: [SyncContact] = []

    var enabled: Bool {
        get { access(keyPath: \.enabled); return UserDefaults.standard.bool(forKey: Self.enabledKey) }
        set {
            withMutation(keyPath: \.enabled) { UserDefaults.standard.set(newValue, forKey: Self.enabledKey) }
        }
    }

    /// Which contacts go to the watch: everyone (minus any removed), or only the ones chosen.
    enum Selection: String { case all, chosen }

    private(set) var selection = Selection(rawValue: UserDefaults.standard.string(forKey: "contacts.selection") ?? "") ?? .all
    /// In `.all` mode: contacts removed from the watch. In `.chosen` mode: contacts added to it.
    private(set) var excluded: Set<String> = Set(UserDefaults.standard.stringArray(forKey: "contacts.excluded") ?? [])
    private(set) var included: Set<String> = Set(UserDefaults.standard.stringArray(forKey: "contacts.included") ?? [])

    func isOnWatch(_ id: String) -> Bool {
        selection == .all ? !excluded.contains(id) : included.contains(id)
    }

    /// The contacts that go to the watch, from everything readable on the iPhone.
    var onWatch: [SyncContact] { available.filter { isOnWatch($0.id) } }

    /// Switching mode keeps exactly the same people on the watch; it only changes what happens to new contacts.
    func setSelection(_ s: Selection) {
        guard s != selection else { return }
        let current = Set(onWatch.map(\.id))
        let all = Set(available.map(\.id))
        if s == .chosen { included = current } else { excluded = all.subtracting(current) }
        selection = s
        UserDefaults.standard.set(s.rawValue, forKey: "contacts.selection")
        saveSelection()
    }

    /// Adds these contacts to the watch (or removes them), and syncs the difference.
    func add(_ ids: Set<String>) { save(onWatch: Set(onWatch.map(\.id)).union(ids)) }

    func remove(_ ids: Set<String>) { save(onWatch: Set(onWatch.map(\.id)).subtracting(ids)) }

    /// Add one contact to the watch (true) or remove it (false).
    func setOnWatch(_ id: String, _ on: Bool) {
        if selection == .all {
            if on { excluded.remove(id) } else { excluded.insert(id) }
        } else {
            if on { included.insert(id) } else { included.remove(id) }
        }
        saveSelection()
        scheduleSync()
    }

    /// Saves an edited selection: exactly [onWatch] (contact IDs) goes to the watch, then syncs the difference.
    func save(onWatch ids: Set<String>) {
        let all = Set(available.map(\.id))
        if selection == .all {
            excluded = all.subtracting(ids)
        } else {
            included = ids.intersection(all)
        }
        saveSelection()
        scheduleSync()
    }

    /// Add or remove every contact matching the current search (or all of them).
    func setOnWatch(_ ids: [String], _ on: Bool) {
        for id in ids {
            if selection == .all {
                if on { excluded.remove(id) } else { excluded.insert(id) }
            } else {
                if on { included.insert(id) } else { included.remove(id) }
            }
        }
        saveSelection()
        scheduleSync()
    }

    private func saveSelection() {
        UserDefaults.standard.set(Array(excluded), forKey: "contacts.excluded")
        UserDefaults.standard.set(Array(included), forKey: "contacts.included")
    }

    /// iOS has no API for Phone's favourites, so people pick favourites for the watch here.
    private(set) var favorites: Set<String> = Set(UserDefaults.standard.stringArray(forKey: "contacts.favorites") ?? [])

    @ObservationIgnored private var lastGeneration = UInt32(truncatingIfNeeded: UserDefaults.standard.integer(forKey: "contacts.generation"))
    @ObservationIgnored private var digests: [String: String] = ContactSync.loadDigests()
    @ObservationIgnored private var changeObserver: NSObjectProtocol?
    @ObservationIgnored private weak var link: WatchLink?

    private static let enabledKey = "contacts.enabled"
    private static let batchBytes = 3_000

    private init() {
        changeObserver = NotificationCenter.default.addObserver(forName: .CNContactStoreDidChange, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { ContactSync.shared.contactsChanged() }
        }
    }

    var isAuthorized: Bool { authorization == .authorized || authorization == .limited }

    func requestAccess() async {
        _ = try? await CNContactStore().requestAccess(for: .contacts)
        authorization = CNContactStore.authorizationStatus(for: .contacts)
        if isAuthorized { await refreshAvailable() }
    }

    func toggleFavorite(_ id: String) {
        if favorites.contains(id) { favorites.remove(id) } else { favorites.insert(id) }
        UserDefaults.standard.set(Array(favorites), forKey: "contacts.favorites")
        scheduleSync()
    }

    /// The user turned sync on: send everything now.
    func turnOn(link: WatchLink) {
        enabled = true
        setRemovalPending(false)
        self.link = link
        guard isAuthorized, link.isConnected else { return }
        start(full: true)
    }

    /// Cancels queued work and discards all selections and sync state belonging to a removed watch.
    func deviceRemoved() {
        enabled = false
        syncEpoch &+= 1
        syncTask?.cancel(); debounce?.cancel(); confirmationTimer?.cancel()
        syncing = false; awaitingConfirmation = false
        awaitingGeneration = nil; pendingDigests = nil
        setRemovalPending(false)
        selection = .all; included = []; excluded = []; favorites = []
        available = []; progress = 0
        for key in ["contacts.selection", "contacts.included", "contacts.excluded"] { UserDefaults.standard.removeObject(forKey: key) }
        UserDefaults.standard.removeObject(forKey: "contacts.favorites")
        lastError = nil
        link = nil
        resetState()
    }

    /// The user turned sync off: remove every contact from the watch and forget the sync state.
    func turnOff() {
        enabled = false
        syncEpoch &+= 1
        syncTask?.cancel(); debounce?.cancel(); confirmationTimer?.cancel()
        syncing = false; awaitingConfirmation = false
        awaitingGeneration = nil; pendingDigests = nil
        setRemovalPending(true)
        resetState()
        retryRemoval()
    }

    private func setRemovalPending(_ pending: Bool) {
        removalPending = pending
        UserDefaults.standard.set(pending, forKey: "contacts.pendingClear")
        if pending && removalNonce.count != 16 {
            // Use an independent cryptographic nonce, persisted across reconnect/restart.
            removalNonce = Array(BridgeCrypto.randomNonce())
            UserDefaults.standard.set(Data(removalNonce), forKey: "contacts.clearNonce")
        } else if !pending {
            removalNonce = []; UserDefaults.standard.removeObject(forKey: "contacts.clearNonce")
        }
    }

    private func retryRemoval() {
        guard removalPending, let link, link.isConnected else { return }
        if removalNonce.count != 16 { setRemovalPending(true) }
        link.sendContacts(.contactsClear, removalNonce)
        confirmationTimer?.cancel()
        confirmationTimer = Task { [weak self] in
            try? await Task.sleep(for: .seconds(10))
            guard !Task.isCancelled, let self else { return }
            self.retryRemoval()
        }
    }

    /// Authenticated acknowledgement of this exact deletion request, after durable watch storage.
    func removalConfirmed(_ nonce: [UInt8]) {
        guard !enabled, removalPending, nonce.count == 16, nonce == removalNonce else { return }
        setRemovalPending(false); confirmationTimer?.cancel(); lastError = nil
    }

    /// Generation comes from the watch's durable, authenticated store.
    func watchReported(generation: UInt32, link: WatchLink) {
        self.link = link
        if !enabled {
            if removalPending { retryRemoval() }
            return
        }
        guard isAuthorized, link.isConnected else { return }
        if let awaiting = awaitingGeneration {
            guard generation == awaiting else { return }
            confirmationTimer?.cancel()
            awaitingGeneration = nil; awaitingConfirmation = false
            lastGeneration = generation
            digests = pendingDigests ?? digests; pendingDigests = nil
            Self.saveDigests(digests)
            UserDefaults.standard.set(Int(generation), forKey: "contacts.generation")
            lastSynced = Date()
            UserDefaults.standard.set(lastSynced, forKey: "contacts.lastSynced")
            lastError = nil
            if pendingChanges { scheduleSync() }
            return
        }
        if syncing { return }
        if generation != lastGeneration || generation == 0 { start(full: true) }
        else if pendingChanges { pendingChanges = false; start(full: false) }
    }

    private func start(full: Bool) {
        guard enabled, !syncing, awaitingGeneration == nil else { return }
        syncing = true
        pendingChanges = false
        let epoch = syncEpoch
        syncTask = Task { await sync(full: full, epoch: epoch) }
    }

    @ObservationIgnored private var awaitingGeneration: UInt32?

    @ObservationIgnored private var pendingChanges = true // check once per launch

    private func contactsChanged() {
        pendingChanges = true
        scheduleSync()
    }

    @ObservationIgnored private var debounce: Task<Void, Never>?

    /// A change on the iPhone (contacts edited, someone added or removed, favourites): sync it soon.
    /// Taps within a second go out together. If a sync is running or unconfirmed, it runs after the watch
    /// confirms (see watchReported), so no change is lost.
    private func scheduleSync() {
        pendingChanges = true
        debounce?.cancel()
        debounce = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled, let self else { return }
            guard self.enabled, self.isAuthorized, let link = self.link, link.isConnected,
                  self.awaitingGeneration == nil, !self.syncing else { return }
            self.pendingChanges = false
            self.start(full: link.watchStatus?.contactsGeneration != self.lastGeneration)
        }
    }

    @discardableResult
    func refreshAvailable() async -> Bool {
        authorization = CNContactStore.authorizationStatus(for: .contacts)
        guard isAuthorized else { return false }
        let favs = favorites
        do {
            let fetched = try await Task.detached(priority: .utility) { try Self.fetch(favorites: favs) }.value
            available = fetched
            return true
        } catch {
            lastError = "Couldn't read contacts. The watch's last confirmed contacts are preserved."
            return false
        }
    }

    private func sync(full: Bool, epoch: UInt64) async {
        defer { if epoch == syncEpoch { syncing = false } }
        guard let link, link.isConnected else { return }
        progress = 0
        guard await refreshAvailable(), enabled, !Task.isCancelled, epoch == syncEpoch else { return }
        let contacts = onWatch
        guard contacts.count <= 5000 else { lastError = "Choose at most 5,000 contacts for the watch."; return }
        var newDigests: [String: String] = [:]
        for c in contacts { newDigests[c.id] = Self.digest(c) }
        let changed = full ? contacts : contacts.filter { digests[$0.id] != newDigests[$0.id] }
        let removed = full ? [] : digests.keys.filter { newDigests[$0] == nil }
        if !full && changed.isEmpty && removed.isEmpty { return }
        var candidate = UInt32.random(in: 1...UInt32.max)
        while candidate == lastGeneration || candidate == 0xDE70DE70 { candidate = UInt32.random(in: 1...UInt32.max) }
        let generation = candidate

        func send(_ type: BridgeProtocol.MessageType, _ payload: [UInt8]) async -> Bool {
            guard enabled, epoch == syncEpoch, !Task.isCancelled, link.isConnected else { return false }
            let ok = await link.sendContactsAndWait(type, payload)
            if !ok && enabled && epoch == syncEpoch {
                lastError = "Contact transfer interrupted. Reconnect to retry."
                pendingChanges = true
            }
            return ok && enabled && epoch == syncEpoch && !Task.isCancelled
        }
        guard await send(.contactsBegin, ContactsCodec.begin(full: full, count: changed.count, deletes: removed.count, transaction: generation)) else { return }
        var batch: [SyncContact] = []; var size = 0; var sent = 0
        for contact in changed {
            var w = ByteWriter(); contact.encode(into: &w)
            if !batch.isEmpty && (size + w.bytes.count > Self.batchBytes || batch.count == 255) {
                guard await send(.contactsUpsert, ContactsCodec.upsert(batch)) else { return }
                sent += batch.count; progress = Double(sent) / Double(max(changed.count, 1))
                batch.removeAll(keepingCapacity: true); size = 0
            }
            batch.append(contact); size += w.bytes.count
        }
        if !batch.isEmpty { guard await send(.contactsUpsert, ContactsCodec.upsert(batch)) else { return } }
        for offset in stride(from: 0, to: removed.count, by: 200) {
            guard await send(.contactsDelete, ContactsCodec.delete(Array(removed[offset..<min(offset + 200, removed.count)]))) else { return }
        }
        guard enabled, epoch == syncEpoch, !Task.isCancelled else { return }
        awaitingGeneration = generation; pendingDigests = newDigests; awaitingConfirmation = true
        confirmationTimer?.cancel()
        confirmationTimer = Task { [weak self] in
            try? await Task.sleep(for: .seconds(30))
            guard !Task.isCancelled, let self, self.awaitingGeneration == generation else { return }
            self.awaitingGeneration = nil; self.awaitingConfirmation = false; self.pendingDigests = nil
            self.lastError = "The watch hasn't confirmed this sync. Retrying when connected."
            self.pendingChanges = true
            if self.enabled && self.link?.security == .secure { self.start(full: true) }
        }
        let ended = await send(.contactsEnd, ContactsCodec.end(generation: generation, transaction: generation, contacts: contacts))
        if !ended && awaitingGeneration == generation {
            confirmationTimer?.cancel(); awaitingGeneration = nil; pendingDigests = nil; awaitingConfirmation = false
        }
        if ended { progress = 1; link.note("contacts: transferred full=\(full) changed=\(changed.count) removed=\(removed.count); awaiting commit") }
    }

    private func resetState() {
        lastGeneration = 0
        UserDefaults.standard.removeObject(forKey: "contacts.generation")
        digests = [:]
        Self.saveDigests([:])
        lastSynced = nil
        UserDefaults.standard.removeObject(forKey: "contacts.lastSynced")
    }

    // MARK: - Reading contacts (off the main thread)

    nonisolated private static func fetch(favorites: Set<String>) throws -> [SyncContact] {
        let keys: [CNKeyDescriptor] = [
            CNContactFormatter.descriptorForRequiredKeys(for: .fullName),
            CNContactOrganizationNameKey as CNKeyDescriptor,
            CNContactPhoneNumbersKey as CNKeyDescriptor,
            CNContactEmailAddressesKey as CNKeyDescriptor,
        ]
        let request = CNContactFetchRequest(keysToFetch: keys)
        request.sortOrder = .userDefault
        var out: [SyncContact] = []
        try CNContactStore().enumerateContacts(with: request) { c, _ in
            let name = CNContactFormatter.string(from: c, style: .fullName) ?? c.organizationName
            var phones: [String] = []
            for p in c.phoneNumbers {
                let n = SyncContact.normalize(p.value.stringValue)
                if !n.isEmpty && !phones.contains(n) { phones.append(n) } // duplicate detection
            }
            let emails = c.emailAddresses.map { String($0.value) }.filter { !$0.isEmpty }
            // Contacts with neither a name nor a number aren't useful on a watch.
            guard !name.isEmpty || !phones.isEmpty else { return }
            out.append(SyncContact(id: c.identifier, name: name, phones: phones, emails: emails, favorite: favorites.contains(c.identifier)))
        }
        return out
    }

    nonisolated private static func digest(_ c: SyncContact) -> String {
        var w = ByteWriter()
        c.encode(into: &w)
        return Data(SHA256.hash(data: Data(w.bytes)).prefix(12)).base64EncodedString()
    }

    // The digest file holds contact identifiers and hashes only (no names or numbers).
    nonisolated private static var digestsURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("contact-digests.json")
    }

    nonisolated private static func loadDigests() -> [String: String] {
        guard let data = try? Data(contentsOf: digestsURL) else { return [:] }
        return (try? JSONDecoder().decode([String: String].self, from: data)) ?? [:]
    }

    nonisolated private static func saveDigests(_ d: [String: String]) {
        if let data = try? JSONEncoder().encode(d) {
            try? data.write(to: digestsURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }
}
