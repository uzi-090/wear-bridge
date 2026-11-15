import CoreBluetooth
import CryptoKit
import Foundation
import UIKit
import Observation

/// Must match `BridgeUuids` on the watch (see docs/BLE_PROTOCOL.md). Main-actor isolated: CBUUID isn't Sendable.
@MainActor
enum BridgeUUID {
    static let ancs = CBUUID(string: "7905F431-B5CE-4E99-A40F-4B1E122D00D0")
    static let service = CBUUID(string: "9C664C93-19F2-435D-895A-A40751731AE1")
    static let pairing = CBUUID(string: "9C664C93-19F2-435D-0001-A40751731AE1")
    static let control = CBUUID(string: "9C664C93-19F2-435D-0002-A40751731AE1")
    static let contacts = CBUUID(string: "9C664C93-19F2-435D-0004-A40751731AE1")
    static let events = CBUUID(string: "9C664C93-19F2-435D-0003-A40751731AE1")
    static let deviceStatus = CBUUID(string: "9C664C93-19F2-435D-0005-A40751731AE1")
    static let settings = CBUUID(string: "9C664C93-19F2-435D-0006-A40751731AE1")
    static let auth = CBUUID(string: "9C664C93-19F2-435D-000A-A40751731AE1")
}

/// Phase 1 companion: finds the watch, forces LE pairing by reading an encrypted characteristic, then
/// keeps an auto-reconnecting connection. Notifications themselves never pass through this app.
/// The watch reads them straight from iOS over ANCS.
@MainActor
@Observable
final class WatchLink: NSObject {

    enum Phase: Equatable {
        case starting
        case bluetoothOff
        case bluetoothUnauthorized
        case unsupported
        case notSetUp
        case removing
        case searching
        case connecting
        case pairing
        case connected
        case reconnecting
        case needsAttention(String)
    }

    /// App-level authentication with the watch (docs/BLE_PROTOCOL.md). Bridge messages flow only when `.secure`.
    enum Security: Equatable {
        case none
        case handshaking
        /// The watch is asking its user to allow this app. Both screens show this code.
        case awaitingApproval(code: String)
        case secure
        case denied
        /// The watch's key differs from the one pinned at setup (watch reset or Bridge reinstalled).
        case watchIdentityChanged

        var statusTitle: String {
            switch self {
            case .denied: "Approval declined"
            case .watchIdentityChanged: "Confirm your watch’s identity"
            case .awaitingApproval: "Waiting for approval"
            case .none, .handshaking: "Securing the connection…"
            case .secure: "Connected"
            }
        }
    }

    struct Nearby: Identifiable {
        let id: UUID
        let name: String
        let peripheral: CBPeripheral
        /// A discovery hint for ordering only; selection still requires encrypted app approval.
        var discoveryPriority = 0
    }

    private(set) var phase: Phase = .starting
    private(set) var watchName: String?
    private(set) var protocolVersion: Int?
    private(set) var capabilities: BridgeProtocol.Capabilities = []
    /// Fresh authenticated state, discarded immediately when the connection is lost.
    private(set) var watchStatus: WatchStatus?
    /// When the watch last sent anything over the bridge.
    private(set) var lastSync: Date?
    /// True while this iPhone is making the watch ring.
    private(set) var ringingWatch = false
    private(set) var nearby: [Nearby] = []
    private(set) var showingAllNearby = false
    /// Redacted: states and error codes only, never names or identifiers.
    private(set) var diagnostics: [String] = []
    /// The watch's notification settings (the watch stores and applies them). nil until received.
    private(set) var watchSettings: WatchSettings?
    private(set) var security: Security = .none
    private(set) var removalMessage: String? { didSet { pairingRecord.setRemovalNotice(removalMessage) } }
    private(set) var pairingRevision = 0
    @ObservationIgnored private let pairingRecord = DevicePairing()
    @ObservationIgnored private var removalAction: Bool?
    @ObservationIgnored private var removalDeadline: Task<Void, Never>?
    @ObservationIgnored private var connectionEpoch = 0
    @ObservationIgnored private var pairingEncryptionRetries = 0
    @ObservationIgnored private var pairingReadRetry: Task<Void, Never>?
    @ObservationIgnored private var nearbyRefresh: Task<Void, Never>?


    @ObservationIgnored private var central: CBCentralManager!
    @ObservationIgnored private var peripheral: CBPeripheral?
    @ObservationIgnored private var control: CBCharacteristic?
    @ObservationIgnored private var settingsCharacteristic: CBCharacteristic?
    @ObservationIgnored private var settingsReassembler = BridgeReassembler()
    @ObservationIgnored private var eventsReassembler = BridgeReassembler()
    @ObservationIgnored private var statusReassembler = BridgeReassembler()
    @ObservationIgnored private var authReassembler = BridgeReassembler()
    @ObservationIgnored private var authCharacteristic: CBCharacteristic?
    @ObservationIgnored private var contactsCharacteristic: CBCharacteristic?
    @ObservationIgnored private var watchKey: Data?
    @ObservationIgnored private var staticKey: SymmetricKey?
    @ObservationIgnored private var helloNonce: Data?
    @ObservationIgnored private var session: SecureSession?
    @ObservationIgnored private var identityKey: P256.KeyAgreement.PrivateKey?
    /// This app's pairing key, loaded on first use. nil while the Keychain is locked; HELLO then waits for unlock.
    private var identity: P256.KeyAgreement.PrivateKey? {
        if identityKey == nil { identityKey = BridgeIdentity.privateKey() }
        return identityKey
    }
    @ObservationIgnored private var helloWaitingForUnlock = false
    private struct PendingWrite {
        let peripheral: CBPeripheral
        let characteristic: CBCharacteristic
        let data: Data
        var completion: ((Bool) -> Void)?
    }
    @ObservationIgnored private var writes: [PendingWrite] = []
    @ObservationIgnored private var writing = false
    @ObservationIgnored private var writeDeadline: Task<Void, Never>?

    @ObservationIgnored private var nextMessageId: UInt8 = 0
    /// Set when pairing fails. Stops auto-reconnect from silently starting another attempt (found on hardware).
    @ObservationIgnored private var pairingFailed = false

    private static let startedKey = "bridge.bluetooth.started"

    override init() {
        super.init()
        DiagFile.append("app launched")
        removalMessage = pairingRecord.removalNotice
        if pairingRecord.needsDeviceCleanup || pairingRecord.needsIdentityReset {
            removalMessage = "Removal was interrupted on this iPhone. Unpair in Bridge on the watch too before pairing again."
        }
        clearDeviceData() // A restored app starts disconnected; dismiss orphan peer alerts before reconnecting.
        _ = finishDeviceCleanupIfNeeded()
        _ = resetPairingIdentityIfNeeded()
        startPowerReporting()
        powerObservers.append(NotificationCenter.default.addObserver(
            forName: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { self.keychainUnlocked() }
        })
        // Existing users (and iOS relaunching us for state restoration) start Bluetooth at once. New users
        // start it from onboarding, after we've explained why, because creating the manager shows iOS's prompt.
        if hasSavedWatch || UserDefaults.standard.bool(forKey: Self.startedKey) {
            start()
        } else {
            phase = .notSetUp
        }
    }

    /// Creates the Bluetooth central (and so triggers iOS's Bluetooth permission prompt the first time).
    func start() {
        guard central == nil else { return }
        UserDefaults.standard.set(true, forKey: Self.startedKey)
        phase = .starting
        central = CBCentralManager(
            delegate: self,
            queue: .main,
            options: [CBCentralManagerOptionRestoreIdentifierKey: "io.wearbridge.central"]
        )
    }

    var bluetoothStarted: Bool { central != nil }

    var hasSavedWatch: Bool { _ = pairingRevision; return pairingRecord.identifier != nil }
    var isPaired: Bool { _ = pairingRevision; return pairingRecord.enabled && pairingRecord.approved && removalAction == nil }
    /// Setup has started but the watch hasn't approved this app yet; Cancel Pairing ends it.
    var isPairingInProgress: Bool { _ = pairingRevision; return pairingRecord.enabled && !pairingRecord.approved && removalAction == nil }
    var pairingStateTitle: String {
        if phase == .removing { return removalAction == true ? "Forgetting…" : "Unpairing…" }
        if isPaired { return "Paired" }
        if pairingRecord.enabled { return "Pairing" }
        return "Unpaired"
    }


    // MARK: - User intents

    /// Pair Watch, Pair Watch Again, Try Again and Reconnect: an explicit search. A saved watch is
    /// chosen automatically when it turns up; otherwise the user picks from nearby Bridge watches.
    /// Searching (rather than only waiting on the saved identifier) matters after a Bluetooth Forget
    /// on either device: the watch then appears under a new identifier and the old one never connects.
    func findWatch() {
        guard removalAction == nil, finishDeviceCleanupIfNeeded(), resetPairingIdentityIfNeeded() else { return }
        pairingRecord.begin(); pairingRevision += 1
        removalMessage = nil
        pairingFailed = false
        start()
        guard central?.state == .poweredOn else { return }
        endSearch()
        let saved = pairingRecord.identifier.flatMap { central.retrievePeripherals(withIdentifiers: [$0]).first }
        // The search replaces whatever the link was doing (a stale pending connect or a failed attempt).
        if let old = peripheral, old !== saved {
            peripheral = nil
            central.cancelPeripheralConnection(old)
        }
        clearWrites(); clearSession()
        if let saved, saved.state == .connected { select(saved); return }
        phase = .searching
        includeSystemConnectedWatches()
        guard phase == .searching else { return } // the saved watch is system-connected: selected
        // While the saved watch's system pairing holds, a pending connect finds it even when it
        // isn't advertising Bridge first. didConnect ends the search.
        if let saved {
            peripheral = saved
            saved.delegate = self
            central.connect(saved, options: [CBConnectPeripheralOptionEnableAutoReconnect: true])
        }
        refreshSystemConnectedWatchesWhileSearching()
        central.scanForPeripherals(withServices: [BridgeUUID.service], options: [
            CBCentralManagerScanOptionSolicitedServiceUUIDsKey: [BridgeUUID.ancs],
            CBCentralManagerScanOptionAllowDuplicatesKey: true
        ])
        note("scan: bridge service and notification solicitation")
    }

    /// Fallback if the watch's scan response isn't matched by the filtered scan (risk R-02).
    func showAllNearby() {
        guard central?.state == .poweredOn else { return }
        central.stopScan()
        nearby = []
        showingAllNearby = true
        phase = .searching
        includeSystemConnectedWatches()
        refreshSystemConnectedWatchesWhileSearching()
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        note("scan: all nearby")
    }

    /// A forgotten Bridge entry can still have an iOS Bluetooth connection for ANCS. Such a
    /// watch may not advertise again, so offer it for explicit selection alongside scan results.
    private func includeSystemConnectedWatches(logEmpty: Bool = true) {
        var added = 0
        for p in central.retrieveConnectedPeripherals(withServices: [BridgeUUID.service]) {
            if p.identifier == pairingRecord.identifier {
                note("scan: saved watch is system-connected")
                select(p)
                return
            }
            guard !nearby.contains(where: { $0.id == p.identifier }) else { continue }
            let position = nearby.firstIndex(where: { $0.discoveryPriority < 2 }) ?? nearby.endIndex
            nearby.insert(Nearby(id: p.identifier, name: p.name ?? "Bridge Watch", peripheral: p, discoveryPriority: 2), at: position)
            added += 1
        }
        if logEmpty || added > 0 { note("scan: system-connected candidates=\(nearby.count)") }
    }

    /// iOS may establish its own notification link just after setup starts, stopping the watch's
    /// advertisements. Keep offering that watch for explicit selection during this search only.
    private func refreshSystemConnectedWatchesWhileSearching() {
        nearbyRefresh?.cancel()
        nearbyRefresh = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(2))
                guard !Task.isCancelled, let self, self.phase == .searching,
                      self.pairingRecord.enabled, self.removalAction == nil,
                      self.central?.state == .poweredOn else { return }
                self.includeSystemConnectedWatches(logEmpty: false)
            }
        }
    }

    func choose(_ item: Nearby) {
        guard phase == .searching, pairingRecord.enabled, removalAction == nil,
              nearby.contains(where: { $0.id == item.id }) else { return }
        select(item.peripheral)
    }

    private func select(_ p: CBPeripheral) {
        endSearch()
        attach(p)
    }

    private func endSearch() {
        central?.stopScan()
        nearbyRefresh?.cancel(); nearbyRefresh = nil
        nearby = []
        showingAllNearby = false
    }

    /// A paired watch goes back to waiting for its automatic reconnection; unfinished setup is cancelled.
    func cancelSearch() {
        guard phase == .searching else { return }
        guard isPaired else { cancelPairing(); return }
        endSearch()
        if peripheral == nil { restoreSavedWatch() } else { phase = .reconnecting }
    }

    /// Stops an unfinished setup on this iPhone: a search, a pending connection or a waiting approval.
    /// A watch already showing the code drops it when the link closes or its request times out.
    func cancelPairing() {
        guard isPairingInProgress else { return }
        var askedWatch = false
        switch security {
        case .awaitingApproval, .denied: askedWatch = true
        default: break
        }
        endSearch()
        let old = peripheral
        let auth = authCharacteristic
        peripheral = nil
        clearWrites(); clearSession()
        if let old, let auth, askedWatch, old.state == .connected, auth.isNotifying {
            // iOS often keeps the link up for notifications, so the watch wouldn't see a disconnect.
            // Unsubscribing from AUTH tells it to drop the code; disconnect once that write is out.
            old.setNotifyValue(false, for: auth)
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(1))
                guard let self, self.peripheral !== old else { return }
                self.central?.cancelPeripheralConnection(old)
            }
        } else if let old {
            central?.cancelPeripheralConnection(old)
        }
        pairingRecord.cancelSetup(); pairingRevision += 1
        BridgeIdentity.pinnedWatch = nil
        phase = .notSetUp
        removalMessage = askedWatch ? "Pairing cancelled. If your watch still shows a code, tap Deny there." : nil
        // A key the watch may still approve late must not pair this app: replace it now.
        _ = finishDeviceCleanupIfNeeded()
        _ = resetPairingIdentityIfNeeded()
        note("pairing cancelled askedWatch=\(askedWatch)")
    }

    func sendPing() { send(.ping, [], on: control) }

    /// Rings the watch until someone taps Found it (on either device) or the watch's 60 s limit.
    func ringWatch(_ ring: Bool) {
        guard isConnected else { return }
        ringingWatch = ring
        send(.findWatch, BridgeCodec.encodeSwitch(ring), on: control)
    }

    /// The user stopped the iPhone alert here, so the watch's "Ringing…" can end.
    func phoneFound() { send(.findPhoneStopped, [], on: control) }

    var isConnected: Bool { isPaired && phase == .connected && security == .secure && peripheral?.state == .connected }

    // MARK: - iPhone power → watch

    @ObservationIgnored private var powerObservers: [NSObjectProtocol] = []

    /// Watches the iPhone's battery while the app runs and tells the watch when it starts or stops charging.
    private func startPowerReporting() {
        guard powerObservers.isEmpty else { return }
        UIDevice.current.isBatteryMonitoringEnabled = true
        for name in [UIDevice.batteryStateDidChangeNotification, UIDevice.batteryLevelDidChangeNotification] {
            powerObservers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { self.sendPhoneStatus() }
            })
        }
    }

    func sendPhoneStatus() {
        guard isConnected else { return }
        let device = UIDevice.current
        let level = device.batteryLevel < 0 ? nil : Int((device.batteryLevel * 100).rounded())
        send(.phoneStatus, BridgeCodec.encodePhoneStatus(
            battery: level, charging: device.batteryState == .charging, full: device.batteryState == .full), on: control)
    }

    // MARK: - Notification settings (stored and applied on the watch)

    func setMode(_ mode: FilterMode) {
        watchSettings?.mode = mode // optimistic. The watch answers with the authoritative snapshot.
        send(.setMode, SettingsCodec.encodeSetMode(mode))
    }

    func setRule(for app: WatchApp, enabled: Bool? = nil, vibrate: Bool? = nil, hideContent: Bool? = nil) {
        guard let index = watchSettings?.apps.firstIndex(where: { $0.id == app.id }) else { return }
        var updated = app
        if let enabled { updated.enabled = enabled }
        if let vibrate { updated.vibrate = vibrate }
        if let hideContent { updated.hideContent = hideContent }
        watchSettings?.apps[index] = updated
        send(.setAppRule, SettingsCodec.encodeSetAppRule(
            bundleId: updated.id, enabled: updated.enabled, vibrate: updated.vibrate, hideContent: updated.hideContent))
    }

    func refreshSettings() { send(.getSettings, []) }

    /// Contact sync batches (sealed like everything else).
    @discardableResult
    func sendContacts(_ type: BridgeProtocol.MessageType, _ payload: [UInt8]) -> Bool {
        send(type, payload, on: contactsCharacteristic)
    }

    func sendContactsAndWait(_ type: BridgeProtocol.MessageType, _ payload: [UInt8]) async -> Bool {
        await withCheckedContinuation { continuation in
            send(type, payload, on: contactsCharacteristic) { continuation.resume(returning: $0) }
        }
    }

    func setPrefs(_ prefs: WatchPrefs) {
        watchSettings?.prefs = prefs // optimistic
        send(.setPrefs, SettingsCodec.encodeSetPrefs(prefs))
    }

    @ObservationIgnored private var iconsSentThisConnection: Set<String> = []

    /// Sends official App Store icons for apps the watch doesn't have one for yet.
    private func syncIcons() {
        guard let apps = watchSettings?.apps else { return }
        let service = AppIconService.shared
        let needed = apps.filter { !$0.hasIcon && service.isLookupable($0.id) && !iconsSentThisConnection.contains($0.id) }
        guard !needed.isEmpty else { return }
        needed.forEach { iconsSentThisConnection.insert($0.id) }
        let epoch = connectionEpoch
        Task { @MainActor in
            for app in needed {
                guard isConnected, connectionEpoch == epoch else { return }
                guard let jpeg = await service.icon(for: app.id) else { continue }
                guard isConnected, connectionEpoch == epoch else { return }
                send(.setAppIcon, SettingsCodec.encodeSetAppIcon(bundleId: app.id, image: jpeg))
                note("icon sent (\(jpeg.count) bytes)")
            }
        }
    }

    @discardableResult
    private func send(_ type: BridgeProtocol.MessageType, _ payload: [UInt8]) -> Bool {
        send(type, payload, on: settingsCharacteristic)
    }

    @discardableResult
    private func send(_ type: BridgeProtocol.MessageType, _ payload: [UInt8], on characteristic: CBCharacteristic?, completion: ((Bool) -> Void)? = nil) -> Bool {
        guard (isConnected || type == .deviceLifecycle), session != nil, let sealed = try? session?.seal(type: type.rawValue, payload) else {
            completion?(false); return false
        }
        return writeFramed(type.rawValue, sealed, on: characteristic, completion: completion)
    }

    @discardableResult
    private func writeFramed(_ type: UInt8, _ payload: [UInt8], on characteristic: CBCharacteristic?, completion: ((Bool) -> Void)? = nil) -> Bool {
        guard let p = peripheral, let c = characteristic, p.state == .connected else {
            completion?(false); return false
        }
        let maxChunk = min(p.maximumWriteValueLength(for: .withResponse), 182)
        guard maxChunk > BridgeProtocol.headerBytes else { completion?(false); return false }
        nextMessageId &+= 1
        let chunks = BridgeProtocol.split(rawType: type, messageId: nextMessageId, payload: payload, maxChunkBytes: maxChunk)
        guard writes.reduce(0, { $0 + $1.data.count }) + chunks.reduce(0, { $0 + $1.count }) <= 131_072 else {
            completion?(false); failWrites(); return false
        }
        for (index, chunk) in chunks.enumerated() {
            writes.append(PendingWrite(peripheral: p, characteristic: c, data: chunk,
                completion: index == chunks.count - 1 ? completion : nil))
        }
        pumpWrites()
        return true
    }

    private func pumpWrites() {
        guard !writing, let write = writes.first else { return }
        guard write.peripheral === peripheral, write.peripheral.state == .connected else { failWrites(); return }
        writing = true
        write.peripheral.writeValue(write.data, for: write.characteristic, type: .withResponse)
        writeDeadline = Task { [weak self] in
            try? await Task.sleep(for: .seconds(10))
            guard !Task.isCancelled, let self else { return }
            self.note("write timed out; reconnecting")
            self.failWrites()
        }
    }

    private func clearWrites() {
        writeDeadline?.cancel(); writeDeadline = nil
        let pending = writes; writes.removeAll(); writing = false
        pending.forEach { $0.completion?(false) }
    }

    private func failWrites() {
        if removalAction != nil { finishRemoval(confirmed: false); return }
        clearWrites()
        clearSession()
        phase = .reconnecting
        if let p = peripheral { central.cancelPeripheralConnection(p) }
    }

    func unpairWatch() { removeWatch(forget: false) }
    func forgetWatch() { removeWatch(forget: true) }

    private func removeWatch(forget: Bool) {
        guard removalAction == nil else { return }
        let reachable = isConnected && capabilities.contains(.lifecycle)
        beginRemoval(forget: forget)
        guard reachable else {
            finishRemoval(confirmed: false)
            return
        }
        send(.deviceLifecycle, [forget ? 2 : 1, 0], on: control) { [weak self] written in
            if !written { self?.finishRemoval(confirmed: false) }
        }
        removalDeadline = Task { [weak self] in
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled else { return }
            self?.finishRemoval(confirmed: false)
        }
    }

    private func beginRemoval(forget: Bool) {
        note("device removal started forget=\(forget)")
        removalAction = forget
        pairingRecord.remove(forget: forget)
        pairingRevision += 1
        phase = .removing
        central?.stopScan()
        nearby = []; showingAllNearby = false
        // Preserve the outstanding ATT write until its response arrives. Otherwise that old
        // response could be mistaken for the lifecycle ACK queued on the same characteristic.
        var active = writing ? writes.first : nil
        let discarded = writing ? Array(writes.dropFirst()) : writes
        writes = []
        active?.completion?(false)
        active?.completion = nil
        if let active { writes = [active] }
        discarded.forEach { $0.completion?(false) }
        clearDeviceData()
        _ = finishDeviceCleanupIfNeeded()
        _ = resetPairingIdentityIfNeeded()
        BridgeIdentity.pinnedWatch = nil
    }

    private func finishRemoval(confirmed: Bool) {
        guard removalAction != nil else { return }
        removalDeadline?.cancel(); removalDeadline = nil
        let forget = removalAction == true
        let old = peripheral
        peripheral = nil // invalidate callbacks before cancelling the restored/pending connection
        pairingFailed = true
        clearWrites()
        clearSession()
        if let old { central?.cancelPeripheralConnection(old) }
        BridgeIdentity.pinnedWatch = nil
        removalAction = nil
        phase = .notSetUp
        removalMessage = confirmed
            ? (forget ? "Forgotten on both devices." : "Unpaired on both devices.")
                + " To remove the system Bluetooth pairing too, use Settings › Bluetooth › Forget This Device on each device."
            : (forget ? "Forgotten on this iPhone. The watch could not confirm; open Bridge on the watch and choose Forget Device there too."
                      : "Unpaired on this iPhone. The watch could not confirm; open Bridge on the watch and choose Unpair there too.")
                + " To remove the system Bluetooth pairing, use Settings › Bluetooth."
        if pairingRecord.needsIdentityReset { removalMessage = "Unpaired. Tap Pair Watch to retry resetting the pairing identity." }
        if pairingRecord.needsDeviceCleanup { removalMessage = "Unpaired. Saved data cleanup is unfinished. Tap Pair Watch to retry cleanup before pairing." }
        note("device removal confirmed=\(confirmed)")
    }

    private func resetPairingIdentityIfNeeded() -> Bool {
        guard pairingRecord.needsIdentityReset else { return true }
        do {
            identityKey = try BridgeIdentity.replacePairingIdentity()
            BridgeIdentity.pinnedWatch = nil
            pairingRecord.identityResetCompleted()
            return true
        } catch {
            removalMessage = "Pairing identity could not be reset. Tap Pair Watch to retry."
            note("pairing identity reset failed")
            return false
        }
    }

    private func finishDeviceCleanupIfNeeded() -> Bool {
        guard pairingRecord.needsDeviceCleanup else { return true }
        ContactSync.shared.deviceRemoved()
        BridgeIdentity.pinnedWatch = nil
        guard AppIconService.shared.clearCache() else {
            removalMessage = "Saved data cleanup is unfinished. Tap Pair Watch to retry cleanup before pairing."
            return false
        }
        pairingRecord.deviceCleanupCompleted()
        return true
    }

    private func clearDeviceData() {
        watchSettings = nil; watchStatus = nil; lastSync = nil
        watchName = nil; ringingWatch = false
        FindMyPhone.shared.stop()
        LowBatteryAlerts.shared.disconnected()
        CallHandoff.disconnected()
    }

    private func clearSession() {
        connectionEpoch += 1
        nearbyRefresh?.cancel(); nearbyRefresh = nil
        pairingReadRetry?.cancel(); pairingReadRetry = nil
        pairingEncryptionRetries = 0
        clearDeviceData()
        control = nil; settingsCharacteristic = nil; authCharacteristic = nil; contactsCharacteristic = nil
        protocolVersion = nil; capabilities = []
        watchKey = nil; staticKey = nil; helloNonce = nil; session = nil; security = .none
        helloWaitingForUnlock = false
        authReassembler.reset(); settingsReassembler.reset(); eventsReassembler.reset(); statusReassembler.reset()
        iconsSentThisConnection.removeAll()
    }

    // MARK: - Internals

    private func attach(_ p: CBPeripheral) {
        guard pairingRecord.enabled, removalAction == nil else { central?.cancelPeripheralConnection(p); return }
        if let current = peripheral, current !== p { central?.cancelPeripheralConnection(current) }
        clearSession()
        peripheral = p
        p.delegate = self
        watchName = p.name
        if p.state == .connected {
            p.discoverServices([BridgeUUID.service])
            phase = .connecting
            return
        }
        phase = isPaired ? .reconnecting : .connecting
        // A pending connect never times out. With auto-reconnect, iOS also restores the link after
        // drops without waking us to retry.
        central.connect(p, options: [CBConnectPeripheralOptionEnableAutoReconnect: true])
    }

    /// Automatic reconnection (launch, Bluetooth on): waits on the saved watch without a scan.
    private func restoreSavedWatch() {
        guard pairingRecord.enabled, let saved = pairingRecord.identifier else { phase = .notSetUp; return }
        // Already connected at system level (for example, iOS reconnected for ANCS)?
        if let p = central.retrieveConnectedPeripherals(withServices: [BridgeUUID.service]).first(where: { $0.identifier == saved }) {
            note("restore: system-connected")
            attach(p)
            return
        }
        if let p = central.retrievePeripherals(withIdentifiers: [saved]).first {
            note("restore: pending connect")
            attach(p)
            return
        }
        phase = .notSetUp
    }

    private static let forgetSteps = "On your iPhone, open Settings › Bluetooth, tap ⓘ next to your watch and choose Forget This Device. Then tap Try Again and confirm the code on both devices."

    /// Pairing failed: stop all reconnect attempts and explain the fix, rather than retrying silently.
    private func pairingDidFail(_ reason: String) {
        clearWrites(); clearSession()
        note("pairing failed: \(reason)")
        pairingFailed = true
        if let p = peripheral { central?.cancelPeripheralConnection(p) } // also cancels auto-reconnect
        phase = .needsAttention("Pairing didn't finish. " + Self.forgetSteps)
    }

    private func handle(error: Error, context: String) {
        note("\(context) error domain=\((error as NSError).domain) code=\((error as NSError).code)")
        // Only a first-time pairing can "fail" this way. A known watch keeps reconnecting.
        if phase == .pairing && !hasSavedWatch {
            return pairingDidFail("\(context) code=\((error as NSError).code)")
        }
        if let cb = error as? CBError, cb.code == .peerRemovedPairingInformation {
            // Only the system Bluetooth pairing is gone (forgotten on the watch). Bridge approval
            // belongs to this app's key, so it stays: after Forget in Settings and Try Again, the
            // watch pairs again at the system level and reconnects without a new approval.
            clearWrites(); clearSession()
            pairingFailed = true
            if let p = peripheral { central?.cancelPeripheralConnection(p) }
            phase = .needsAttention("Your watch no longer recognises this iPhone. " + Self.forgetSteps)
        } else if let att = error as? CBATTError, [.insufficientAuthentication, .insufficientEncryption].contains(att.code) {
            pairingDidFail("att code=\(att.code.rawValue)")
        } else {
            clearDeviceData()
            phase = .needsAttention("Watch connection failed. Bluetooth connection became unavailable.")
        }
    }

    /// Opens a sealed bridge message. nil (and dropped) if there's no session or it doesn't verify.
    private func open(_ m: BridgeReassembler.Message) -> BridgeReassembler.Message? {
        guard (isConnected || m.type == BridgeProtocol.MessageType.deviceLifecycleEvent.rawValue),
              let plain = session?.open(type: m.type, m.payload) else {
            note("rejected message type=\(m.type)")
            return nil
        }
        return BridgeReassembler.Message(type: m.type, messageId: m.messageId, payload: plain)
    }

    private func receiveSettingsChunk(_ chunk: Data) {
        switch settingsReassembler.accept(chunk) {
        case .incomplete:
            break
        case .error(let reason):
            note("settings error: \(reason)")
        case .complete(let sealed):
            guard let message = open(sealed) else { return }
            guard message.type == BridgeProtocol.MessageType.settings.rawValue else { return }
            do {
                watchSettings = try SettingsCodec.decodeSnapshot(message.payload)
                lastSync = Date()
                note("settings: \(watchSettings?.apps.count ?? 0) apps")
                syncIcons()
            } catch {
                note("settings: malformed snapshot")
            }
        }
    }

    private func receiveEventChunk(_ chunk: Data) {
        guard case .complete(let sealed) = eventsReassembler.accept(chunk), let m = open(sealed) else { return }
        lastSync = Date()
        switch BridgeProtocol.MessageType(rawValue: m.type) {
        case .deviceLifecycleEvent:
            guard m.payload.count == 2, (1...2).contains(m.payload[0]), m.payload[1] <= 1 else { return }
            let forget = m.payload[0] == 2
            if m.payload[1] == 1 {
                if removalAction == forget { finishRemoval(confirmed: true) }
            } else {
                guard removalAction == nil else { return }
                beginRemoval(forget: forget)
                send(.deviceLifecycle, [forget ? 2 : 1, 1], on: control) { [weak self] ok in
                    self?.finishRemoval(confirmed: ok)
                }
            }
        case .findPhone:
            let ring = m.payload.first == 1
            note("event: find phone ring=\(ring)")
            if ring {
                FindMyPhone.shared.start(onFound: { [weak self] in self?.phoneFound() })
            } else {
                FindMyPhone.shared.stop()
            }
        case .findWatchStopped:
            note("event: watch found")
            ringingWatch = false
        case .contactsCleared:
            ContactSync.shared.removalConfirmed(m.payload)
        case .callRequest:
            guard let request = try? BridgeCodec.decodeCallRequest(m.payload) else { return }
            note("event: call request")
            CallHandoff.offer(phone: request.phone, name: request.name)
        default:
            note("event: unknown type \(m.type)")
        }
    }

    private func receiveStatusChunk(_ chunk: Data) {
        guard case .complete(let sealed) = statusReassembler.accept(chunk), let m = open(sealed),
              m.type == BridgeProtocol.MessageType.status.rawValue else { return }
        do {
            let status = try BridgeCodec.decodeStatus(m.payload)
            watchStatus = status
            if let watchKey { LowBatteryAlerts.shared.receive(percent: status.battery, charging: status.charging, watchKey: watchKey) }
            lastSync = Date()
            if capabilities.contains(.contacts) {
                ContactSync.shared.watchReported(generation: status.contactsGeneration, link: self)
            }
        } catch {
            note("status: malformed")
        }
    }

    // MARK: - App-level authentication

    /// HELLO = our public key ‖ fresh nonce ‖ MAC proving we hold the private key.
    private func sendHello() {
        guard let watchKey else { return }
        guard let identity else {
            // Woken in the background before the first unlock after a restart: wait rather than
            // present a stand-in key the watch would treat as a different app.
            note("auth: waiting for this iPhone to be unlocked")
            security = .handshaking
            helloWaitingForUnlock = true
            return
        }
        do {
            let key = try BridgeCrypto.staticKey(privateKey: identity, watchKey: watchKey)
            let nonce = BridgeCrypto.randomNonce()
            staticKey = key
            helloNonce = nonce
            session = nil
            security = .handshaking
            let payload = identity.publicKey.x963Representation + nonce + BridgeCrypto.helloMac(staticKey: key, nonceI: nonce)
            writeFramed(BridgeCrypto.AuthType.hello.rawValue, [UInt8](payload), on: authCharacteristic)
            note("auth: hello sent")
        } catch {
            note("auth: invalid watch key")
            security = .denied
        }
    }

    private func receiveAuthChunk(_ chunk: Data) {
        guard pairingRecord.enabled, removalAction == nil else { return }
        guard case .complete(let m) = authReassembler.accept(chunk) else { return }
        switch BridgeCrypto.AuthType(rawValue: m.type) {
        case .helloOK:
            guard let key = staticKey, let nonceI = helloNonce, m.payload.count == BridgeCrypto.nonceBytes + BridgeCrypto.macBytes else { return }
            let nonceW = Data(m.payload.prefix(BridgeCrypto.nonceBytes))
            let mac = Data(m.payload.suffix(BridgeCrypto.macBytes))
            // The watch proves it holds the pinned key. A mismatch means someone else is answering.
            guard mac == BridgeCrypto.okMac(staticKey: key, nonceI: nonceI, nonceW: nonceW) else {
                note("auth: watch failed verification")
                pairingRecord.requireApproval(); pairingRevision += 1
                security = .denied
                return
            }
            session = SecureSession(keys: BridgeCrypto.sessionKeys(staticKey: key, nonceI: nonceI, nonceW: nonceW))
            security = .secure
            if let peripheral { pairingRecord.approve(peripheral.identifier); pairingRevision += 1 }
            note("auth: secure session")
            sendPhoneStatus()
        case .needsApproval:
            guard let key = staticKey, let watchKey, let identity else { return }
            pairingRecord.requireApproval(); pairingRevision += 1
            clearDeviceData()
            security = .awaitingApproval(code: BridgeCrypto.pairingCode(
                staticKey: key, iphoneKey: identity.publicKey.x963Representation, watchKey: watchKey))
            note("auth: waiting for approval on the watch")
        case .denied:
            pairingRecord.requireApproval(); pairingRevision += 1
            clearDeviceData()
            security = .denied
            note("auth: denied by the watch")
        default:
            break
        }
    }

    private func keychainUnlocked() {
        guard helloWaitingForUnlock else { return }
        helloWaitingForUnlock = false
        if session == nil, authCharacteristic != nil, peripheral?.state == .connected { sendHello() }
    }

    /// After a denial, ask the watch again.
    func retryAuthorization() { sendHello() }

    /// The watch was reset or Bridge was reinstalled on it: trust its new key (the user confirmed).
    func trustNewWatchIdentity() {
        BridgeIdentity.pinnedWatch = watchKey
        note("auth: new watch identity trusted")
        sendHello()
    }

    func note(_ line: String) {
        DiagFile.append(line)
        diagnostics.append("\(Date().formatted(date: .omitted, time: .standard))  \(line)")
        if diagnostics.count > 100 { diagnostics.removeFirst(diagnostics.count - 100) }
    }
}

// MARK: - CBCentralManagerDelegate (delivered on the main queue, so the conformances are main-actor isolated)

extension WatchLink: @preconcurrency CBCentralManagerDelegate {

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        note("central state=\(central.state.rawValue)")
        switch central.state {
        case .poweredOn:
            guard pairingRecord.enabled, removalAction == nil else { phase = .notSetUp; return }
            if let p = peripheral { attach(p) }
            else if hasSavedWatch { restoreSavedWatch() }
            else { findWatch() }
        case .poweredOff:
            clearWrites(); clearSession()
            phase = .bluetoothOff
        case .unauthorized: clearWrites(); clearSession(); phase = .bluetoothUnauthorized
        case .unsupported: clearWrites(); clearSession(); phase = .unsupported
        default: break
        }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        let restored = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
        note("restored peripherals=\(restored.count)")
        for p in restored where !pairingRecord.enabled || p.identifier != pairingRecord.identifier {
            central.cancelPeripheralConnection(p)
        }
        if pairingRecord.enabled, let p = restored.first(where: { $0.identifier == pairingRecord.identifier }) {
            peripheral = p
            p.delegate = self
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard phase == .searching, pairingRecord.enabled, removalAction == nil else { return }
        let name = peripheral.name ?? advertisementData[CBAdvertisementDataLocalNameKey] as? String
        let advertised = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
        let solicited = advertisementData[CBAdvertisementDataSolicitedServiceUUIDsKey] as? [CBUUID] ?? []
        let candidate = advertised.contains(BridgeUUID.service) || solicited.contains(BridgeUUID.ancs)
        // Pair Watch Again / Reconnect: the saved watch needs no choosing.
        if candidate, peripheral.identifier == pairingRecord.identifier {
            note("scan: saved watch found")
            select(peripheral)
            return
        }
        guard !showingAllNearby || name != nil || candidate else { return }
        let priority = candidate || !showingAllNearby ? 2 : ((name?.localizedCaseInsensitiveContains("watch") == true) ? 1 : 0)
        if let index = nearby.firstIndex(where: { $0.id == peripheral.identifier }) {
            guard priority > nearby[index].discoveryPriority else { return }
            nearby.remove(at: index)
        }
        // After Forget, the old watch may still advertise if it was offline. Let the user choose
        // the intended replacement instead of connecting to whichever advertisement arrives first.
        let item = Nearby(id: peripheral.identifier, name: name ?? "Bridge Watch", peripheral: peripheral, discoveryPriority: priority)
        let position = nearby.firstIndex(where: { $0.discoveryPriority < priority }) ?? nearby.endIndex
        nearby.insert(item, at: position)
        note("scan: candidate priority=\(priority) count=\(nearby.count)")
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        if pairingFailed || peripheral !== self.peripheral || !pairingRecord.enabled || removalAction != nil {
            // The system's auto-reconnect brought the link back after a failed pairing. Don't start another attempt.
            central.cancelPeripheralConnection(peripheral)
            return
        }
        if phase == .searching {
            note("search: saved watch connected")
            endSearch()
        }
        note("connected")
        phase = .connecting
        peripheral.discoverServices([BridgeUUID.service])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard peripheral === self.peripheral, pairingRecord.enabled, removalAction == nil else { return }
        if phase == .searching { // the saved watch's background connect; the scan carries on
            central.connect(peripheral, options: [CBConnectPeripheralOptionEnableAutoReconnect: true])
            return
        }
        clearSession()
        if let error { handle(error: error, context: "connect") }
        guard !pairingFailed else { return }
        // Keep a pending connect so the link comes back when the watch is reachable again.
        central.connect(peripheral, options: [CBConnectPeripheralOptionEnableAutoReconnect: true])
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        timestamp: CFAbsoluteTime,
        isReconnecting: Bool,
        error: Error?
    ) {
        guard peripheral === self.peripheral else { return }
        if removalAction != nil { finishRemoval(confirmed: false); return }
        if phase == .searching { // the saved watch's background connect; the scan carries on
            if !isReconnecting { central.connect(peripheral, options: [CBConnectPeripheralOptionEnableAutoReconnect: true]) }
            return
        }
        clearWrites()
        note("disconnected autoReconnect=\(isReconnecting) code=\((error as NSError?)?.code ?? 0)")
        clearSession()
        if let error, (error as? CBError)?.code == .peerRemovedPairingInformation {
            handle(error: error, context: "disconnect")
            return
        }
        // Dropping mid-pairing is how a rejected pairing usually shows up.
        if phase == .pairing && !hasSavedWatch { pairingDidFail("link dropped during pairing") }
        guard !pairingFailed, pairingRecord.enabled, peripheral.identifier == self.peripheral?.identifier else { return }
        guard hasSavedWatch else {
            // A newly chosen watch dropped before its Bluetooth pairing finished. Nothing is saved to
            // reconnect to, so say so instead of showing Connecting… forever.
            pairingFailed = true
            central.cancelPeripheralConnection(peripheral)
            phase = .needsAttention("The watch disconnected before pairing finished. Keep Bridge open on the watch, then tap Try Again.")
            return
        }
        phase = .reconnecting
        if !isReconnecting {
            central.connect(peripheral, options: [CBConnectPeripheralOptionEnableAutoReconnect: true])
        }
    }
}

// MARK: - CBPeripheralDelegate

extension WatchLink: @preconcurrency CBPeripheralDelegate {

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard peripheral === self.peripheral else { return }
        if let error { return handle(error: error, context: "services") }
        guard let service = peripheral.services?.first(where: { $0.uuid == BridgeUUID.service }) else {
            // Stay connected: when Bridge opens its service on the watch, didModifyServices continues setup.
            phase = .needsAttention("Bridge isn't ready on this watch. On the watch, open Bridge and tap Pair iPhone (or Pair again), then tap Try Again.")
            return
        }
        peripheral.discoverCharacteristics(
            [BridgeUUID.pairing, BridgeUUID.control, BridgeUUID.settings, BridgeUUID.events, BridgeUUID.deviceStatus, BridgeUUID.auth,
             BridgeUUID.contacts],
            for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard peripheral === self.peripheral else { return }
        if let error { return handle(error: error, context: "characteristics") }
        control = service.characteristics?.first { $0.uuid == BridgeUUID.control }
        settingsCharacteristic = service.characteristics?.first { $0.uuid == BridgeUUID.settings }
        authCharacteristic = service.characteristics?.first { $0.uuid == BridgeUUID.auth }
        contactsCharacteristic = service.characteristics?.first { $0.uuid == BridgeUUID.contacts }
        guard let pairing = service.characteristics?.first(where: { $0.uuid == BridgeUUID.pairing }) else {
            phase = .needsAttention("Your watch is running an incompatible version of Bridge.")
            return
        }
        // This read requires encryption. On first use, iOS shows its pairing prompt.
        phase = .pairing
        peripheral.readValue(for: pairing)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === self.peripheral else { return }
        switch characteristic.uuid {
        case BridgeUUID.settings:
            if let value = characteristic.value { receiveSettingsChunk(value) }
            return
        case BridgeUUID.events:
            if let value = characteristic.value { receiveEventChunk(value) }
            return
        case BridgeUUID.deviceStatus:
            if let value = characteristic.value { receiveStatusChunk(value) }
            return
        case BridgeUUID.auth:
            if let value = characteristic.value { receiveAuthChunk(value) }
            return
        default:
            break
        }
        if let error {
            // Android may report insufficient encryption while its system confirmation is still
            // opening. Disconnecting here cancels the iPhone prompt before the user can respond.
            // Keep this explicit setup attempt alive for at most 40 seconds; removal/disconnect
            // invalidates the task, and refusals/other errors still follow the failure path.
            if characteristic.uuid == BridgeUUID.pairing, phase == .pairing,
               (error as? CBATTError)?.code == .insufficientEncryption,
               pairingEncryptionRetries < 20, pairingRecord.enabled, removalAction == nil {
                pairingEncryptionRetries += 1
                let epoch = connectionEpoch
                note("pairing: waiting for system encryption retry=\(pairingEncryptionRetries)")
                pairingReadRetry?.cancel()
                pairingReadRetry = Task { [weak self] in
                    try? await Task.sleep(for: .seconds(2))
                    guard !Task.isCancelled, let self, self.connectionEpoch == epoch,
                          self.phase == .pairing, self.pairingRecord.enabled, self.removalAction == nil,
                          peripheral === self.peripheral, peripheral.state == .connected else { return }
                    peripheral.readValue(for: characteristic)
                }
                return
            }
            return handle(error: error, context: "pairing read")
        }
        guard characteristic.uuid == BridgeUUID.pairing, let value = characteristic.value, !value.isEmpty else { return }
        pairingReadRetry?.cancel(); pairingReadRetry = nil
        let pairing = characteristic
        protocolVersion = Int(value[value.startIndex])
        let b = [UInt8](value)
        if b.count >= 5 {
            capabilities = .init(rawValue: UInt32(b[1]) | UInt32(b[2]) << 8 | UInt32(b[3]) << 16 | UInt32(b[4]) << 24)
        }
        // The watch's app-level public key follows. Pinned on first use, checked on every connection after.
        if b.count >= 5 + BridgeCrypto.publicKeyBytes {
            let key = Data(b[5..<(5 + BridgeCrypto.publicKeyBytes)])
            watchKey = key
            if let pinned = BridgeIdentity.pinnedWatch, pinned != key {
                pairingRecord.requireApproval(); pairingRevision += 1
                security = .watchIdentityChanged
                note("auth: watch identity changed")
            } else {
                BridgeIdentity.pinnedWatch = key
            }
        } else {
            note("auth: watch doesn't support app-level security (update Bridge on the watch)")
        }
        watchName = peripheral.name
        // Save the entry before approval, but keep features locked until HELLO_OK.
        pairingRecord.saveCandidate(peripheral.identifier)
        pairingRevision += 1
        phase = .connected
        note("bonded, protocol v\(protocolVersion ?? 0)")
        // Subscribing makes the watch send its current settings and status. Events (Find iPhone) arrive
        // even while this app is in the background: iOS wakes it for them (bluetooth-central mode).
        let wanted: Set<CBUUID> = [BridgeUUID.settings, BridgeUUID.events, BridgeUUID.deviceStatus, BridgeUUID.auth]
        for c in pairing.service?.characteristics ?? [] where wanted.contains(c.uuid) {
            peripheral.setNotifyValue(true, for: c)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard writing, let write = writes.first, write.peripheral === peripheral,
              write.characteristic === characteristic else { return }
        writeDeadline?.cancel(); writeDeadline = nil
        if let error {
            note("write failed code=\((error as NSError).code)"); failWrites(); return
        }
        writes.removeFirst(); writing = false
        write.completion?(true)
        pumpWrites()
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === self.peripheral else { return }
        // The watch's answer arrives on AUTH, so say hello only once we're listening there.
        // Only once per connection: a repeated discovery (services changed mid-setup) must not
        // send a second HELLO, which would make the watch ask for approval twice.
        if characteristic.uuid == BridgeUUID.auth, characteristic.isNotifying, security == .none {
            sendHello()
        }
        note("notify \(characteristic.uuid.uuidString.prefix(8))… on=\(characteristic.isNotifying) code=\((error as NSError?)?.code ?? 0)")
    }

    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        guard peripheral === self.peripheral else { return }
        endSearch()
        clearWrites()
        clearSession()
        phase = .connecting
        note("watch services changed")
        peripheral.discoverServices([BridgeUUID.service])
    }
}
