import CoreBluetooth
import Foundation
import Observation

/// Must match `BridgeUuids` on the watch (see docs/BLE_PROTOCOL.md). Main-actor isolated: CBUUID isn't Sendable.
@MainActor
enum BridgeUUID {
    static let service = CBUUID(string: "9C664C93-19F2-435D-895A-A40751731AE1")
    static let pairing = CBUUID(string: "9C664C93-19F2-435D-0001-A40751731AE1")
    static let control = CBUUID(string: "9C664C93-19F2-435D-0002-A40751731AE1")
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
    private(set) var lastPing: Date?
    private(set) var nearby: [Nearby] = []
    private(set) var showingAllNearby = false
    /// Redacted: states and error codes only, never names or identifiers.
    private(set) var diagnostics: [String] = []

    @ObservationIgnored private var central: CBCentralManager!
    @ObservationIgnored private var peripheral: CBPeripheral?
    @ObservationIgnored private var control: CBCharacteristic?
    /// Set when pairing fails. Stops auto-reconnect from silently starting another attempt (found on hardware).
    @ObservationIgnored private var pairingFailed = false

    private static let savedIdentifierKey = "bridge.watch.identifier"

    override init() {
        super.init()
        central = CBCentralManager(
            delegate: self,
            queue: .main,
            options: [CBCentralManagerOptionRestoreIdentifierKey: "io.wearbridge.central"]
        )
    }

    var hasSavedWatch: Bool { UserDefaults.standard.string(forKey: Self.savedIdentifierKey) != nil }

    // MARK: - User intents

    func findWatch() {
        guard central.state == .poweredOn else { return }
        nearby = []
        showingAllNearby = false
        pairingFailed = false
        phase = .searching
        central.scanForPeripherals(withServices: [BridgeUUID.service])
        note("scan: bridge service")
    }

    /// Fallback if the watch's scan response isn't matched by the filtered scan (risk R-02).
    func showAllNearby() {
        guard central.state == .poweredOn else { return }
        central.stopScan()
        nearby = []
        showingAllNearby = true
        phase = .searching
        central.scanForPeripherals(withServices: nil)
        note("scan: all nearby")
    }

    func choose(_ item: Nearby) {
        central.stopScan()
        attach(item.peripheral)
    }

    func cancelSearch() {
        central.stopScan()
        phase = hasSavedWatch ? .reconnecting : .notSetUp
    }

    func sendPing() {
        guard let p = peripheral, let c = control, p.state == .connected else { return }
        p.writeValue(Data([0x01]), for: c, type: .withResponse)
    }

    func forgetWatch() {
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        peripheral = nil
        control = nil
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
        if let p = peripheral { central.cancelPeripheralConnection(p) } // also cancels auto-reconnect
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
            if let p = peripheral { central.cancelPeripheralConnection(p) }
            phase = .needsAttention("Your watch no longer recognises this iPhone. " + Self.forgetSteps)
        } else if let att = error as? CBATTError, [.insufficientAuthentication, .insufficientEncryption].contains(att.code) {
            pairingDidFail("att code=\(att.code.rawValue)")
        } else {
            phase = .needsAttention("Watch connection failed. Bluetooth connection became unavailable.")
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
        peripheral.discoverCharacteristics([BridgeUUID.pairing, BridgeUUID.control], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error { return handle(error: error, context: "characteristics") }
        control = service.characteristics?.first { $0.uuid == BridgeUUID.control }
        guard let pairing = service.characteristics?.first(where: { $0.uuid == BridgeUUID.pairing }) else {
            phase = .needsAttention("Your watch is running an incompatible version of Bridge.")
            return
        }
        // This read requires encryption. On first use, iOS shows its pairing prompt.
        phase = .pairing
        peripheral.readValue(for: pairing)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error { return handle(error: error, context: "pairing read") }
        guard characteristic.uuid == BridgeUUID.pairing, let value = characteristic.value, !value.isEmpty else { return }
        protocolVersion = Int(value[value.startIndex])
        watchName = peripheral.name
        UserDefaults.standard.set(peripheral.identifier.uuidString, forKey: Self.savedIdentifierKey)
        phase = .connected
        note("bonded, protocol v\(protocolVersion ?? 0)")
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error {
            note("ping failed code=\((error as NSError).code)")
        } else {
            lastPing = Date()
            note("ping ok")
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        note("watch services changed")
        peripheral.discoverServices([BridgeUUID.service])
    }
}
