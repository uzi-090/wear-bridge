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
    private(set) var lastSynced: Date? = UserDefaults.standard.object(forKey: "contacts.lastSynced") as? Date
    /// Contacts on the iPhone that are eligible to sync (after the last fetch).
    private(set) var available: [SyncContact] = []

    var enabled: Bool {
        get { access(keyPath: \.enabled); return UserDefaults.standard.bool(forKey: Self.enabledKey) }
        set {
            withMutation(keyPath: \.enabled) { UserDefaults.standard.set(newValue, forKey: Self.enabledKey) }
        }
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
        self.link = link
        guard isAuthorized, link.security == .secure else { return }
        start(full: true)
    }

    /// The user turned sync off: remove every contact from the watch and forget the sync state.
    func turnOff() {
        enabled = false
        link?.sendContacts(.contactsClear, [])
        resetState()
    }

    /// Called whenever the watch reports its status (connect, and after each change).
    func watchReported(generation: UInt32, link: WatchLink) {
        self.link = link
        guard enabled, isAuthorized else { return }
        if let awaiting = awaitingGeneration {
            // Status pushes that left the watch before it committed our last sync still carry the old
            // generation. Wait for the new one instead of starting another full sync (found on hardware).
            if generation == awaiting || Date() > awaitingDeadline {
                awaitingGeneration = nil
            } else {
                return
            }
        }
        if generation != lastGeneration || generation == 0 {
            start(full: true)
        } else if pendingChanges {
            pendingChanges = false
            start(full: false)
        }
    }

    /// One sync at a time: `syncing` is set before the task starts, so two status pushes can't both start one.
    private func start(full: Bool) {
        guard !syncing else { return }
        syncing = true
        Task { await sync(full: full) }
    }

    @ObservationIgnored private var awaitingGeneration: UInt32?
    @ObservationIgnored private var awaitingDeadline = Date.distantPast

    @ObservationIgnored private var pendingChanges = true // check once per launch

    private func contactsChanged() {
        pendingChanges = true
        scheduleSync()
    }

    private func scheduleSync() {
        guard enabled, isAuthorized, let link, link.security == .secure, awaitingGeneration == nil else { return }
        start(full: link.watchStatus?.contactsGeneration != lastGeneration)
    }

    func refreshAvailable() async {
        let favs = favorites
        available = await Task.detached(priority: .utility) { Self.fetch(favorites: favs) }.value
    }

    private func sync(full: Bool) async {
        defer { syncing = false }
        guard let link, link.security == .secure else { return }
        progress = 0
        await refreshAvailable()
        let contacts = available
        var newDigests: [String: String] = [:]
        for c in contacts { newDigests[c.id] = Self.digest(c) }

        let changed = full ? contacts : contacts.filter { digests[$0.id] != newDigests[$0.id] }
        let removed = full ? [] : digests.keys.filter { newDigests[$0] == nil }
        if !full && changed.isEmpty && removed.isEmpty { return }

        link.sendContacts(.contactsBegin, ContactsCodec.begin(full: full, count: changed.count))
        var batch: [SyncContact] = []
        var size = 0
        var sent = 0
        for c in changed {
            var w = ByteWriter()
            c.encode(into: &w)
            if !batch.isEmpty && (size + w.bytes.count > Self.batchBytes || batch.count == 255) {
                link.sendContacts(.contactsUpsert, ContactsCodec.upsert(batch))
                sent += batch.count
                progress = Double(sent) / Double(max(changed.count, 1))
                batch.removeAll(keepingCapacity: true)
                size = 0
                await Task.yield()
            }
            batch.append(c)
            size += w.bytes.count
        }
        if !batch.isEmpty { link.sendContacts(.contactsUpsert, ContactsCodec.upsert(batch)) }
        for chunk in stride(from: 0, to: removed.count, by: 200).map({ Array(removed[$0..<min($0 + 200, removed.count)]) }) {
            link.sendContacts(.contactsDelete, ContactsCodec.delete(chunk))
        }
        var generation = UInt32.random(in: 1...UInt32.max)
        while generation == lastGeneration || generation == 0xDE70DE70 { generation = UInt32.random(in: 1...UInt32.max) }
        link.sendContacts(.contactsEnd, ContactsCodec.end(generation: generation))

        // If the watch doesn't commit it (link lost mid-way), its generation won't match next time and we resync fully.
        lastGeneration = generation
        awaitingGeneration = generation
        awaitingDeadline = Date().addingTimeInterval(30)
        UserDefaults.standard.set(Int(generation), forKey: "contacts.generation")
        digests = newDigests
        Self.saveDigests(newDigests)
        lastSynced = Date()
        UserDefaults.standard.set(lastSynced, forKey: "contacts.lastSynced")
        progress = 1
        link.note("contacts: sent full=\(full) changed=\(changed.count) removed=\(removed.count)")
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

    nonisolated private static func fetch(favorites: Set<String>) -> [SyncContact] {
        let keys: [CNKeyDescriptor] = [
            CNContactFormatter.descriptorForRequiredKeys(for: .fullName),
            CNContactOrganizationNameKey as CNKeyDescriptor,
            CNContactPhoneNumbersKey as CNKeyDescriptor,
            CNContactEmailAddressesKey as CNKeyDescriptor,
        ]
        let request = CNContactFetchRequest(keysToFetch: keys)
        request.sortOrder = .userDefault
        var out: [SyncContact] = []
        try? CNContactStore().enumerateContacts(with: request) { c, _ in
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
