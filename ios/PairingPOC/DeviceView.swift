import SwiftUI

struct DeviceView: View {
    let link: WatchLink
    @State private var action: Removal?
    enum Removal: String, Identifiable { case unpair, forget; var id: String { rawValue } }

    private var connectionTitle: String {
        if link.isConnected { return "Connected" }
        if link.phase == .connected { return link.security.statusTitle }
        if link.phase == .notSetUp { return "Not connected" }
        return link.phase.title
    }

    var body: some View {
        List {
            Section {
                LabeledContent("State", value: link.pairingStateTitle)
                LabeledContent("Connection", value: connectionTitle)
                if !link.isPaired, !link.isPairingInProgress, link.phase != .removing {
                    Button(link.hasSavedWatch ? "Pair Watch Again" : "Pair Watch", action: link.findWatch)
                        .disabled(link.phase.isBusy)
                }
                if link.isPairingInProgress {
                    if case .needsAttention = link.phase {
                        Button("Try Again", action: link.findWatch)
                    }
                    Button("Cancel Pairing", role: .cancel, action: link.cancelPairing)
                }
                // Recovery when the saved watch doesn't come back on its own (for example after
                // Forget in Bluetooth settings): search again, choosing the saved watch automatically.
                if link.isPaired, !link.isConnected, link.phase != .searching, link.phase != .connected {
                    Button("Reconnect Watch", action: link.findWatch)
                        .disabled(link.phase == .connecting || link.phase == .pairing)
                }
                if link.isPaired {
                    Button("Unpair Watch", role: .destructive) { action = .unpair }
                }
                if link.hasSavedWatch {
                    Button("Forget Device", role: .destructive) { action = .forget }
                }
            } header: { Text("Pairing") } footer: {
                Text("Unpair ends the Bridge relationship and clears synced data. Forget Device also removes the saved device entry. Pair again and approve on your watch to use features.")
            }
            if link.phase == .connected && !link.isConnected {
                Section("Approval") { SecurityCard(link: link) }
            }
            if link.phase == .searching {
                Section {
                    NearbyList(items: link.nearby, onChoose: link.choose)
                    if !link.showingAllNearby { Button("Show all nearby devices", action: link.showAllNearby) }
                    // Unfinished setup is cancelled above; a paired watch just stops searching.
                    if link.isPaired { Button("Cancel Search", role: .cancel, action: link.cancelSearch) }
                }
            }
            if let message = link.removalMessage { Section { Text(message) } }
            Section("System Bluetooth pairing") {
                Text("To remove the Bluetooth bond, open Settings › Bluetooth on each device and choose Forget or Unpair for the other device. Bridge cannot remove the system pairing itself.")
            }
        }
        .disabled(link.phase == .removing)
        .navigationTitle("Device")
        .confirmationDialog(action == .forget ? "Forget this device?" : "Unpair your watch?", isPresented: Binding(get: { action != nil }, set: { if !$0 { action = nil } }), titleVisibility: .visible, presenting: action) { removal in
            Button(removal == .forget ? "Forget Device" : "Unpair Watch", role: .destructive) {
                if removal == .forget { link.forgetWatch() } else { link.unpairWatch() }
                action = nil
            }
            Button("Cancel", role: .cancel) { action = nil }
        } message: { removal in
            Text(removal == .forget
                ? "Device features will stop, synced data and approval will be removed, and this watch will no longer be saved. If the watch is offline, choose Forget Device in Bridge on the watch too."
                : "Device features will stop and synced data and approval will be removed. The watch stays saved for Pair Watch Again. If the watch is offline, choose Unpair in Bridge on the watch too.")
        }
    }
}
