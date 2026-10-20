package io.wearbridge.watch.ble

import org.junit.Assert.*
import org.junit.Test

class DevicePairingTest {
    @Test fun freshDeviceCannotAcceptAConnectionOrShowData() {
        val state = DevicePairing()
        assertFalse(state.accepts("phone"))
        LinkState.entries.forEach { assertFalse(state.canAccess(LinkSnapshot(it))) }
    }
    @Test fun approvalAndReadyAreBothRequired() {
        val setup = DevicePairing().begin()
        assertTrue(setup.accepts("phone"))
        assertFalse(setup.canAccess(LinkSnapshot(LinkState.READY)))
        val paired = setup.approved("phone")
        assertTrue(paired.canAccess(LinkSnapshot(LinkState.READY)))
        LinkState.entries.filter { it != LinkState.READY }.forEach { assertFalse(paired.canAccess(LinkSnapshot(it))) }
    }
    @Test fun savedDeviceRejectsAnotherPhoneWhileItsBondExists() {
        val state = DevicePairing().begin().approved("phone")
        assertTrue(state.accepts("phone"))
        assertFalse(state.accepts("other"))
        assertFalse(state.accepts("other", savedBonded = true))
    }
    @Test fun pairedPhoneThatLostItsSystemBondCanReturnWithANewAddress() {
        // After Forget in Bluetooth settings the iPhone bonds again under a new private address.
        val state = DevicePairing().begin().approved("old-address")
        assertTrue(state.accepts("new-address", savedBonded = false))
        val reconnected = state.approved("new-address")
        assertEquals(PairingPhase.PAIRED, reconnected.phase)
        assertEquals("new-address", reconnected.address)
        assertFalse(reconnected.accepts("old-address"))
    }
    @Test fun setupAcceptsAnyPhoneEvenWithASavedEntry() {
        // Pair again on a retained entry: the phone may have bonded again (new address) or be a new phone.
        val setup = DevicePairing().begin().approved("phone").removed(false).begin()
        assertTrue(setup.accepts("phone"))
        assertTrue(setup.accepts("rebonded"))
        assertFalse(setup.canAccess(LinkSnapshot(LinkState.READY)))
        assertEquals("rebonded", setup.approved("rebonded").address)
    }
    @Test fun lateApprovalCannotPairAnUnpairedOrRemovingWatch() {
        val removing = DevicePairing().begin().approved("phone").removing()
        assertEquals(removing, removing.approved("phone"))
        val unpaired = removing.removed(false)
        assertEquals(unpaired, unpaired.approved("phone"))
        assertFalse(unpaired.accepts("phone", savedBonded = false))
    }
    @Test fun unpairStopsReconnectAndRequiresFreshApproval() {
        val state = DevicePairing().begin().approved("phone").removing()
        assertFalse(state.enabled)
        assertFalse(state.canAccess(LinkSnapshot(LinkState.READY)))
        val removed = state.removed(false)
        assertEquals("phone", removed.address)
        assertFalse(removed.accepts("phone"))
        assertFalse(removed.begin().canAccess(LinkSnapshot(LinkState.READY)))
        assertTrue(removed.begin().approved("phone").canAccess(LinkSnapshot(LinkState.READY)))
    }
    @Test fun forgetAllowsExplicitPairingWithAnotherPhone() {
        val state = DevicePairing().begin().approved("phone").removed(true)
        assertNull(state.address)
        assertFalse(state.enabled)
        assertTrue(state.begin().accepts("other"))
        assertEquals("other", state.begin().approved("other").address)
    }
    @Test fun replacementPhoneRequiresApprovalAndRejectsPreviousPhone() {
        val forgotten = DevicePairing().begin().approved("old").removing().removed(true)
        val restored = DevicePairing.restore(forgotten.phase.name, forgotten.address, false)
        assertFalse(restored.accepts("old"))
        assertFalse(restored.accepts("new"))
        val setup = restored.begin()
        LinkState.entries.forEach { assertFalse(setup.canAccess(LinkSnapshot(it))) }
        val replacement = setup.approved("new")
        assertTrue(replacement.canAccess(LinkSnapshot(LinkState.READY)))
        assertFalse(replacement.accepts("old"))
        assertEquals(replacement, DevicePairing.restore(replacement.phase.name, replacement.address, true))
    }
    @Test fun interruptedRemovalNeverRestoresApprovalEvenIfOldTrustIsOnDisk() {
        for (phase in listOf(PairingPhase.REMOVING, PairingPhase.UNPAIRED, PairingPhase.PAIRING)) {
            val restored = DevicePairing.restore(phase.name, "old", true)
            assertEquals(PairingPhase.UNPAIRED, restored.phase)
            assertFalse(restored.enabled)
            assertFalse(restored.canAccess(LinkSnapshot(LinkState.READY)))
        }
    }
    @Test fun missingTrustCannotRestorePairedState() {
        val restored = DevicePairing.restore(PairingPhase.PAIRED.name, "phone", false)
        assertFalse(restored.enabled)
        assertEquals("phone", restored.address)
        assertEquals(PairingPhase.PAIRED, DevicePairing.restore(null, null, true).phase)
    }
    @Test fun interruptedForgetClearsOldEntryAndBlocksResidualTrust() {
        for (phase in listOf(PairingPhase.REMOVING, PairingPhase.PAIRED, PairingPhase.UNPAIRED)) {
            val restored = DevicePairing.restore(phase.name, "old", true, pendingRemovalForget = true)
            assertNull(restored.address)
            assertFalse(restored.enabled)
            assertFalse(restored.canAccess(LinkSnapshot(LinkState.READY)))
        }
        assertEquals("old", DevicePairing.restore(PairingPhase.REMOVING.name, "old", true, pendingRemovalForget = false).address)
        assertFalse(DevicePairing.restore("invalid", "old", true).enabled)
    }
}
