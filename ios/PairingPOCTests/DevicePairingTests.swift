import XCTest
@testable import PairingPOC

final class DevicePairingTests: XCTestCase {
    private func store(_ run: (UserDefaults) -> Void) {
        let name = "pairing-tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        run(defaults)
    }
    func testFreshDiscoveryDoesNotCountAsApproval() {
        store { defaults in
            let pairing = DevicePairing(defaults: defaults)
            XCTAssertFalse(pairing.enabled)
            pairing.begin()
            defaults.set(UUID().uuidString, forKey: "bridge.watch.identifier")
            XCTAssertTrue(pairing.enabled)
            XCTAssertFalse(pairing.approved)
            XCTAssertFalse(DevicePairing(defaults: defaults).approved)
        }
    }
    func testUnpairSurvivesRelaunchAndRequiresApproval() {
        store { defaults in
            let id = UUID()
            let pairing = DevicePairing(defaults: defaults)
            pairing.begin()
            pairing.approve(id)
            pairing.remove(forget: false)
            let restored = DevicePairing(defaults: defaults)
            XCTAssertEqual(restored.identifier, id)
            XCTAssertFalse(restored.enabled)
            XCTAssertFalse(restored.approved)
            XCTAssertTrue(restored.needsIdentityReset)
            XCTAssertTrue(restored.needsDeviceCleanup)
            restored.deviceCleanupCompleted()
            XCTAssertFalse(DevicePairing(defaults: defaults).needsDeviceCleanup)
            restored.identityResetCompleted()
            XCTAssertFalse(DevicePairing(defaults: defaults).needsIdentityReset)
            restored.begin()
            XCTAssertFalse(restored.approved)
            restored.approve(id)
            XCTAssertTrue(restored.approved)
        }
    }
    func testForgetClearsEntryWithoutRestoringPairing() {
        store { defaults in
            let pairing = DevicePairing(defaults: defaults)
            pairing.begin()
            pairing.approve(UUID())
            pairing.remove(forget: true)
            let restored = DevicePairing(defaults: defaults)
            XCTAssertNil(restored.identifier)
            XCTAssertFalse(restored.enabled)
            XCTAssertFalse(restored.approved)
        }
    }
    func testExistingPairingMigratesButExplicitUnpairWins() {
        store { defaults in
            defaults.set(UUID().uuidString, forKey: "bridge.watch.identifier")
            let migrated = DevicePairing(defaults: defaults)
            XCTAssertTrue(migrated.enabled)
            XCTAssertTrue(migrated.approved)
            migrated.remove(forget: false)
            XCTAssertFalse(DevicePairing(defaults: defaults).enabled)
        }
    }
    func testForgetThenPairDifferentWatchSurvivesRelaunch() {
        store { defaults in
            let oldWatch = UUID(), newWatch = UUID()
            let pairing = DevicePairing(defaults: defaults)
            pairing.begin()
            pairing.approve(oldWatch)
            pairing.remove(forget: true)
            let restarted = DevicePairing(defaults: defaults)
            XCTAssertNil(restarted.identifier)
            XCTAssertFalse(restarted.enabled)
            XCTAssertFalse(restarted.approved)
            XCTAssertTrue(restarted.needsIdentityReset)
            XCTAssertTrue(restarted.needsDeviceCleanup)
            restarted.deviceCleanupCompleted()
            restarted.identityResetCompleted()
            restarted.begin()
            defaults.set(newWatch.uuidString, forKey: "bridge.watch.identifier")
            XCTAssertFalse(DevicePairing(defaults: defaults).approved)
            restarted.approve(newWatch)
            let replacement = DevicePairing(defaults: defaults)
            XCTAssertEqual(replacement.identifier, newWatch)
            XCTAssertNotEqual(replacement.identifier, oldWatch)
            XCTAssertTrue(replacement.enabled)
            XCTAssertTrue(replacement.approved)
            XCTAssertFalse(replacement.needsIdentityReset)
        }
    }
    func testNewApprovalRequestRevokesOldApprovalOnSavedDevice() {
        store { defaults in
            let id = UUID()
            let pairing = DevicePairing(defaults: defaults)
            pairing.begin()
            pairing.approve(id)
            pairing.requireApproval()
            let restarted = DevicePairing(defaults: defaults)
            XCTAssertEqual(restarted.identifier, id)
            XCTAssertTrue(restarted.enabled)
            XCTAssertFalse(restarted.approved)
            restarted.approve(id)
            XCTAssertTrue(DevicePairing(defaults: defaults).approved)
        }
    }
    func testInterruptedForgetRetainsCleanupJournalAcrossRestarts() {
        store { defaults in
            let pairing = DevicePairing(defaults: defaults)
            pairing.begin()
            pairing.approve(UUID())
            pairing.remove(forget: true)
            let restored = DevicePairing(defaults: defaults)
            XCTAssertNil(restored.identifier)
            XCTAssertFalse(restored.approved)
            XCTAssertTrue(restored.needsDeviceCleanup)
            restored.identityResetCompleted()
            XCTAssertTrue(DevicePairing(defaults: defaults).needsDeviceCleanup)
            restored.deviceCleanupCompleted()
            XCTAssertFalse(DevicePairing(defaults: defaults).needsDeviceCleanup)
        }
    }

    func testLegacyApprovedUserDoesNotReturnToWelcomeAfterForget() {
        store { defaults in
            defaults.set(UUID().uuidString, forKey: "bridge.watch.identifier")
            let legacy = DevicePairing(defaults: defaults)
            XCTAssertTrue(defaults.bool(forKey: "onboarded"))
            legacy.remove(forget: true)
            let restarted = DevicePairing(defaults: defaults)
            XCTAssertNil(restarted.identifier)
            XCTAssertFalse(restarted.approved)
            XCTAssertTrue(defaults.bool(forKey: "onboarded"))
        }
    }

    func testFirstRunDiscoveryAndApprovalKeepRemainingOnboardingSteps() {
        store { defaults in
            let pairing = DevicePairing(defaults: defaults)
            pairing.begin()
            let id = UUID()
            defaults.set(id.uuidString, forKey: "bridge.watch.identifier")
            XCTAssertFalse(DevicePairing(defaults: defaults).approved)
            XCTAssertFalse(defaults.bool(forKey: "onboarded"))
            pairing.approve(id)
            XCTAssertFalse(defaults.bool(forKey: "onboarded"))
            defaults.set(true, forKey: "onboarded") // user finishes the tour
            pairing.remove(forget: true)
            XCTAssertTrue(defaults.bool(forKey: "onboarded"))
        }
    }
    func testInterruptedRemovalCannotRestoreApprovalOrBeginBeforeCleanup() {
        store { defaults in
            let pairing = DevicePairing(defaults: defaults)
            pairing.begin()
            pairing.approve(UUID())
            // Simulate termination after journaling cleanup, before clearing approval flags.
            defaults.set(true, forKey: "bridge.watch.needsDeviceCleanup")
            let restored = DevicePairing(defaults: defaults)
            XCTAssertFalse(restored.enabled)
            XCTAssertFalse(restored.approved)
            restored.begin()
            restored.approve(UUID())
            XCTAssertFalse(restored.enabled)
            restored.deviceCleanupCompleted()
            defaults.set(true, forKey: "bridge.watch.needsIdentityReset")
            XCTAssertFalse(restored.approved)
            restored.begin()
            XCTAssertFalse(restored.enabled)
        }
    }

    func testLateApprovalAfterRemovalCannotReenablePairing() {
        store { defaults in
            let pairing = DevicePairing(defaults: defaults)
            let id = UUID()
            pairing.begin(); pairing.approve(id)
            pairing.remove(forget: true)
            pairing.deviceCleanupCompleted(); pairing.identityResetCompleted()
            pairing.approve(id) // delayed HELLO_OK from the removed connection
            XCTAssertFalse(pairing.enabled)
            XCTAssertFalse(pairing.approved)
            XCTAssertNil(pairing.identifier)
            pairing.begin(); pairing.approve(id)
            XCTAssertTrue(pairing.approved)
        }
    }

    func testCancellingSetupOfANewWatchLeavesNoSavedEntry() {
        store { defaults in
            let pairing = DevicePairing(defaults: defaults)
            pairing.begin()
            pairing.saveCandidate(UUID()) // Bluetooth pairing done, app approval still pending
            XCTAssertNotNil(pairing.identifier)
            pairing.cancelSetup()
            let restarted = DevicePairing(defaults: defaults)
            XCTAssertNil(restarted.identifier)
            XCTAssertFalse(restarted.enabled)
            XCTAssertFalse(restarted.approved)
            // The key a watch may still approve late is replaced before the next setup.
            XCTAssertTrue(restarted.needsIdentityReset)
        }
    }

    func testCancellingPairWatchAgainKeepsTheSavedWatch() {
        store { defaults in
            let id = UUID()
            let pairing = DevicePairing(defaults: defaults)
            pairing.begin(); pairing.approve(id)
            pairing.remove(forget: false)
            pairing.deviceCleanupCompleted(); pairing.identityResetCompleted()
            pairing.begin()
            pairing.saveCandidate(id) // same watch: not a new entry
            pairing.cancelSetup()
            let restarted = DevicePairing(defaults: defaults)
            XCTAssertEqual(restarted.identifier, id)
            XCTAssertFalse(restarted.enabled)
        }
    }

    func testApprovalKeepsTheEntryEvenIfLaterCancelled() {
        store { defaults in
            let id = UUID()
            let pairing = DevicePairing(defaults: defaults)
            pairing.begin()
            pairing.saveCandidate(id)
            pairing.approve(id)
            pairing.requireApproval() // the watch asks again later
            pairing.cancelSetup()
            XCTAssertEqual(DevicePairing(defaults: defaults).identifier, id)
        }
    }

    func testCandidateIsNotSavedOutsideSetup() {
        store { defaults in
            let pairing = DevicePairing(defaults: defaults)
            pairing.saveCandidate(UUID()) // late Bluetooth callback after cancel
            XCTAssertNil(pairing.identifier)
        }
    }

    func testOfflineRemovalNoticeSurvivesRestartAndCanBeClearedForPairing() {
        store { defaults in
            let pairing = DevicePairing(defaults: defaults)
            pairing.setRemovalNotice("Watch could not confirm removal")
            let restored = DevicePairing(defaults: defaults)
            XCTAssertEqual(restored.removalNotice, "Watch could not confirm removal")
            restored.setRemovalNotice(nil)
            XCTAssertNil(DevicePairing(defaults: defaults).removalNotice)
        }
    }

}
