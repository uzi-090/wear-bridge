import Foundation

/// A saved device entry is distinct from approval and the current Bluetooth connection.
struct DevicePairing {
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        // Removal journals win even if termination preceded clearing the old approval flags.
        if needsDeviceCleanup || needsIdentityReset {
            defaults.set(false, forKey: "bridge.watch.pairingEnabled")
            defaults.set(false, forKey: "bridge.watch.approved")
        }
        // Migrate once, before discovery creates a saved (but not yet approved) entry.
        if defaults.object(forKey: "bridge.watch.approved") == nil {
            defaults.set(defaults.string(forKey: "bridge.watch.identifier") != nil, forKey: "bridge.watch.approved")
        }
        // Previously approved users already bypassed onboarding. Remember that independently
        // of the saved device, so Forget cannot unexpectedly restart the welcome tour.
        if identifier != nil && approved { defaults.set(true, forKey: "onboarded") }
    }
    var removalNotice: String? { defaults.string(forKey: "bridge.watch.removalNotice") }
    func setRemovalNotice(_ message: String?) { defaults.set(message, forKey: "bridge.watch.removalNotice") }
    var needsIdentityReset: Bool { defaults.bool(forKey: "bridge.watch.needsIdentityReset") }
    var needsDeviceCleanup: Bool { defaults.bool(forKey: "bridge.watch.needsDeviceCleanup") }
    func deviceCleanupCompleted() { defaults.set(false, forKey: "bridge.watch.needsDeviceCleanup") }
    func identityResetCompleted() { defaults.set(false, forKey: "bridge.watch.needsIdentityReset") }
    var identifier: UUID? { defaults.string(forKey: "bridge.watch.identifier").flatMap(UUID.init(uuidString:)) }
    var enabled: Bool { !needsDeviceCleanup && !needsIdentityReset && (defaults.object(forKey: "bridge.watch.pairingEnabled") as? Bool ?? (identifier != nil)) }
    var approved: Bool { !needsDeviceCleanup && !needsIdentityReset && (defaults.object(forKey: "bridge.watch.approved") as? Bool ?? (identifier != nil)) }
    func begin() {
        guard !needsDeviceCleanup, !needsIdentityReset else { return }
        defaults.set(true, forKey: "bridge.watch.pairingEnabled")
    }
    /// A peer asking for approval has revoked the previous app approval, even on a saved device.
    func requireApproval() { defaults.set(false, forKey: "bridge.watch.approved") }
    /// Saves the watch being set up (after its Bluetooth pairing), before the app approval.
    /// Remembers whether this attempt added the entry, so cancelling setup doesn't leave it behind.
    func saveCandidate(_ identifier: UUID) {
        guard enabled, self.identifier != identifier else { return }
        defaults.set(identifier.uuidString, forKey: "bridge.watch.identifier")
        defaults.set(true, forKey: "bridge.watch.setupAddedEntry")
    }
    func approve(_ identifier: UUID) {
        guard enabled, !needsDeviceCleanup, !needsIdentityReset else { return }
        defaults.set(identifier.uuidString, forKey: "bridge.watch.identifier")
        defaults.set(true, forKey: "bridge.watch.pairingEnabled")
        defaults.set(true, forKey: "bridge.watch.approved")
        defaults.removeObject(forKey: "bridge.watch.setupAddedEntry")
    }
    func remove(forget: Bool) {
        if identifier != nil && approved { defaults.set(true, forKey: "onboarded") }
        defaults.set(true, forKey: "bridge.watch.needsDeviceCleanup")
        defaults.set(true, forKey: "bridge.watch.needsIdentityReset")
        defaults.set(false, forKey: "bridge.watch.pairingEnabled")
        defaults.set(false, forKey: "bridge.watch.approved")
        defaults.removeObject(forKey: "bridge.watch.setupAddedEntry")
        if forget { defaults.removeObject(forKey: "bridge.watch.identifier") }
    }
    /// Ends an unfinished setup like an Unpair (a watch may still hold a pending approval for this
    /// app's key), keeping a previously saved watch but not one this attempt added.
    func cancelSetup() {
        remove(forget: defaults.bool(forKey: "bridge.watch.setupAddedEntry"))
    }
}
