import CoreBluetooth
import Foundation
import Observation

/// Must match `BridgeUuids` on the watch (see docs/BLE_PROTOCOL.md). Main-actor isolated: CBUUID isn't Sendable.
@MainActor
enum BridgeUUID {
    static let service = CBUUID(string: "9C664C93-19F2-435D-895A-A40751731AE1")
    static let pairing = CBUUID(string: "9C664C93-19F2-435D-0001-A40751731AE1")
    static let control = CBUUID(string: "9C664C93-19F2-435D-0002-A40751731AE1")
    static let events = CBUUID(string: "9C664C93-19F2-435D-0003-A40751731AE1")
    static let deviceStatus = CBUUID(string: "9C664C93-19F2-435D-0005-A40751731AE1")
    static let settings = CBUUID(string: "9C664C93-19F2-435D-0006-A40751731AE1")
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
        case searching
        case connecting
        case pairing
        case connected
        case reconnecting
        case needsAttention(String)
    }

    struct Nearby: Identifiable {
        let id: UUID
        let name: String
        let peripheral: CBPeripheral
    }

    private(set) var phase: Phase = .starting
    private(set) var watchName: String?
    private(set) var protocolVersion: Int?
    private(set) var capabilities: BridgeProtocol.Capabilities = []
    /// The watch's battery and link state. Kept after a disconnect, so the app can show when it was last seen.
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

    @ObservationIgnored private var central: CBCentralManager!
    @ObservationIgnored private var peripheral: CBPeripheral?
    @ObservationIgnored private var control: CBCharacteristic?
    @ObservationIgnored private var settingsCharacteristic: CBCharacteristic?
    @ObservationIgnored private var settingsReassembler = BridgeReassembler()
    @ObservationIgnored private var eventsReassembler = BridgeReassembler()
    @ObservationIgnored private var statusReassembler = BridgeReassembler()
    @ObservationIgnored private var nextMessageId: UInt8 = 0
    /// Set when pairing fails. Stops auto-reconnect from silently starting another attempt (found on hardware).
    @ObservationIgnored private var pairingFailed = false

    private static let savedIdentifierKey = "bridge.watch.identifier"

    private static let startedKey = "bridge.bluetooth.started"

    override init() {
        super.init()
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

    var hasSavedWatch: Bool { UserDefaults.standard.string(forKey: Self.savedIdentifierKey) != nil }

    // MARK: - User intents

    func findWatch() {
        guard central?.state == .poweredOn else { return }
        nearby = []
        showingAllNearby = false
        pairingFailed = false
        phase = .searching
        central.scanForPeripherals(withServices: [BridgeUUID.service])
        note("scan: bridge service")
    }

    /// Fallback if the watch's scan response isn't matched by the filtered scan (risk R-02).
    func showAllNearby() {
        guard central?.state == .poweredOn else { return }
        central.stopScan()
        nearby = []
        showingAllNearby = true
        phase = .searching
        central.scanForPeripherals(withServices: nil)
        note("scan: all nearby")
    }

    func choose(_ item: Nearby) {
        central?.stopScan()
        attach(item.peripheral)
    }

    func cancelSearch() {
        central?.stopScan()
        phase = hasSavedWatch ? .reconnecting : .notSetUp
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

    var isConnected: Bool { phase == .connected }

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
        Task { @MainActor in
            for app in needed {
                guard let jpeg = await service.icon(for: app.id) else { continue }
                send(.setAppIcon, SettingsCodec.encodeSetAppIcon(bundleId: app.id, image: jpeg))
                note("icon sent (\(jpeg.count) bytes)")
            }
        }
    }

    private func send(_ type: BridgeProtocol.MessageType, _ payload: [UInt8]) {
        send(type, payload, on: settingsCharacteristic)
    }

    private func send(_ type: BridgeProtocol.MessageType, _ payload: [UInt8], on characteristic: CBCharacteristic?) {
        guard let p = peripheral, let c = characteristic, p.state == .connected else { return }
        nextMessageId &+= 1
        // One ATT packet per chunk (no prepared writes, which the watch rejects).
        let maxChunk = min(p.maximumWriteValueLength(for: .withoutResponse), 182)
        for chunk in BridgeProtocol.split(type: type, messageId: nextMessageId, payload: payload, maxChunkBytes: maxChunk) {
            p.writeValue(chunk, for: c, type: .withResponse)
        }
    }

    func forgetWatch() {
        if let p = peripheral { central?.cancelPeripheralConnection(p) }
        peripheral = nil
        control = nil
        settingsCharacteristic = nil
        watchSettings = nil
        watchStatus = nil
        lastSync = nil
        watchName = nil
        protocolVersion = nil
        UserDefaults.standard.removeObject(forKey: Self.savedIdentifierKey)
        phase = .notSetUp
        note("forgot watch")
    }

    // MARK: - Internals

    private func attach(_ p: CBPeripheral) {
        peripheral = p
        p.delegate = self
        watchName = p.name
        if p.state == .connected {
            p.discoverServices([BridgeUUID.service])
            phase = .connecting
            return
        }
        phase = hasSavedWatch ? .reconnecting : .connecting
        // A pending connect never times out. With auto-reconnect, iOS also restores the link after
        // drops without waking us to retry.
        central.connect(p, options: [CBConnectPeripheralOptionEnableAutoReconnect: true])
    }

    private func restoreSavedWatch() {
        // Already connected at system level (for example, iOS reconnected for ANCS)?
        if let p = central.retrieveConnectedPeripherals(withServices: [BridgeUUID.service]).first {
            note("restore: system-connected")
            attach(p)
            return
        }
        if let raw = UserDefaults.standard.string(forKey: Self.savedIdentifierKey),
           let id = UUID(uuidString: raw),
           let p = central.retrievePeripherals(withIdentifiers: [id]).first {
            note("restore: pending connect")
            attach(p)
            return
        }
        phase = .notSetUp
    }

    private static let forgetSteps = "On your iPhone, open Settings › Bluetooth, tap ⓘ next to your watch and choose Forget This Device. Then tap Try Again and confirm the code on both devices."

    /// Pairing failed: stop all reconnect attempts and explain the fix, rather than retrying silently.
    private func pairingDidFail(_ reason: String) {
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
            pairingFailed = true
            if let p = peripheral { central?.cancelPeripheralConnection(p) }
            phase = .needsAttention("Your watch no longer recognises this iPhone. " + Self.forgetSteps)
        } else if let att = error as? CBATTError, [.insufficientAuthentication, .insufficientEncryption].contains(att.code) {
            pairingDidFail("att code=\(att.code.rawValue)")
        } else {
            phase = .needsAttention("Watch connection failed. Bluetooth connection became unavailable.")
        }
    }

    private func receiveSettingsChunk(_ chunk: Data) {
        switch settingsReassembler.accept(chunk) {
        case .incomplete:
            break
        case .error(let reason):
            note("settings error: \(reason)")
        case .complete(let message):
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
        guard case .complete(let m) = eventsReassembler.accept(chunk) else { return }
        lastSync = Date()
        switch BridgeProtocol.MessageType(rawValue: m.type) {
        case .findPhone:
            let ring = m.payload.first == 1
            note("event: find phone ring=\(ring)")
            if ring { FindMyPhone.shared.start(onFound: { [weak self] in self?.phoneFound() }) } else { FindMyPhone.shared.stop() }
        case .findWatchStopped:
            note("event: watch found")
            ringingWatch = false
        default:
            note("event: unknown type \(m.type)")
        }
    }

    private func receiveStatusChunk(_ chunk: Data) {
        guard case .complete(let m) = statusReassembler.accept(chunk), m.type == BridgeProtocol.MessageType.status.rawValue else { return }
        do {
            watchStatus = try BridgeCodec.decodeStatus(m.payload)
            lastSync = Date()
        } catch {
            note("status: malformed")
        }
    }

    private func note(_ line: String) {
        diagnostics.append("\(Date().formatted(date: .omitted, time: .standard))  \(line)")
        if diagnostics.count > 100 { diagnostics.removeFirst(diagnostics.count - 100) }
    }
}

// MARK: - CBCentralManagerDelegate (delivered on the main queue, so the conformances are main-actor isolated)

extension WatchLink: @MainActor CBCentralManagerDelegate {

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        note("central state=\(central.state.rawValue)")
        switch central.state {
        case .poweredOn:
            if let p = peripheral { attach(p) } else { restoreSavedWatch() }
        case .poweredOff: phase = .bluetoothOff
        case .unauthorized: phase = .bluetoothUnauthorized
        case .unsupported: phase = .unsupported
        default: break
        }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        let restored = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
        note("restored peripherals=\(restored.count)")
        if let p = restored.first {
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
        if showingAllNearby {
            let name = peripheral.name ?? advertisementData[CBAdvertisementDataLocalNameKey] as? String
            guard let name, !nearby.contains(where: { $0.id == peripheral.identifier }) else { return }
            nearby.append(Nearby(id: peripheral.identifier, name: name, peripheral: peripheral))
        } else {
            central.stopScan()
            note("found bridge watch")
            attach(peripheral)
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        if pairingFailed {
            // The system's auto-reconnect brought the link back after a failed pairing. Don't start another attempt.
            central.cancelPeripheralConnection(peripheral)
            return
        }
        note("connected")
        phase = .connecting
        peripheral.discoverServices([BridgeUUID.service])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
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
        note("disconnected autoReconnect=\(isReconnecting) code=\((error as NSError?)?.code ?? 0)")
        watchSettings = nil // only show what the watch confirms
        ringingWatch = false
        iconsSentThisConnection.removeAll()
        settingsReassembler.reset()
        eventsReassembler.reset()
        statusReassembler.reset()
        // Dropping mid-pairing is how a rejected pairing usually shows up.
        if phase == .pairing && !hasSavedWatch { pairingDidFail("link dropped during pairing") }
        guard !pairingFailed, peripheral.identifier == self.peripheral?.identifier, hasSavedWatch else { return }
        phase = .reconnecting
        if !isReconnecting {
            central.connect(peripheral, options: [CBConnectPeripheralOptionEnableAutoReconnect: true])
        }
    }
}

// MARK: - CBPeripheralDelegate

extension WatchLink: @MainActor CBPeripheralDelegate {

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error { return handle(error: error, context: "services") }
        guard let service = peripheral.services?.first(where: { $0.uuid == BridgeUUID.service }) else {
            phase = .needsAttention("This device isn't running Bridge. Open Bridge on your watch and try again.")
            return
        }
        peripheral.discoverCharacteristics(
            [BridgeUUID.pairing, BridgeUUID.control, BridgeUUID.settings, BridgeUUID.events, BridgeUUID.deviceStatus], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error { return handle(error: error, context: "characteristics") }
        control = service.characteristics?.first { $0.uuid == BridgeUUID.control }
        settingsCharacteristic = service.characteristics?.first { $0.uuid == BridgeUUID.settings }
        guard let pairing = service.characteristics?.first(where: { $0.uuid == BridgeUUID.pairing }) else {
            phase = .needsAttention("Your watch is running an incompatible version of Bridge.")
            return
        }
        // This read requires encryption. On first use, iOS shows its pairing prompt.
        phase = .pairing
        peripheral.readValue(for: pairing)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
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
        default:
            break
        }
        if let error { return handle(error: error, context: "pairing read") }
        guard characteristic.uuid == BridgeUUID.pairing, let value = characteristic.value, !value.isEmpty else { return }
        let pairing = characteristic
        protocolVersion = Int(value[value.startIndex])
        if value.count >= 5 {
            let b = [UInt8](value)
            capabilities = .init(rawValue: UInt32(b[1]) | UInt32(b[2]) << 8 | UInt32(b[3]) << 16 | UInt32(b[4]) << 24)
        }
        watchName = peripheral.name
        UserDefaults.standard.set(peripheral.identifier.uuidString, forKey: Self.savedIdentifierKey)
        phase = .connected
        note("bonded, protocol v\(protocolVersion ?? 0)")
        // Subscribing makes the watch send its current settings and status. Events (Find iPhone) arrive
        // even while this app is in the background: iOS wakes it for them (bluetooth-central mode).
        let wanted: Set<CBUUID> = [BridgeUUID.settings, BridgeUUID.events, BridgeUUID.deviceStatus]
        for c in pairing.service?.characteristics ?? [] where wanted.contains(c.uuid) {
            peripheral.setNotifyValue(true, for: c)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error { note("write failed code=\((error as NSError).code)") }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        note("notify \(characteristic.uuid.uuidString.prefix(8))… on=\(characteristic.isNotifying) code=\((error as NSError?)?.code ?? 0)")
    }

    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        note("watch services changed")
        peripheral.discoverServices([BridgeUUID.service])
    }
}
