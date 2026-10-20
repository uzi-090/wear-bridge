package io.wearbridge.watch.ui

import io.wearbridge.watch.ble.DevicePairing
import io.wearbridge.watch.ble.LinkIssue
import io.wearbridge.watch.ble.LinkSnapshot
import io.wearbridge.watch.ble.LinkState
import org.junit.Assert.*
import org.junit.Test

class PairingUxTest {
    @Test fun readyTransportCannotClaimCompletedPairingBeforeApproval() {
        val setup = DevicePairing().begin()
        assertNotEquals("Connected", Copy.pairingConnection(setup, LinkSnapshot(LinkState.READY)))
        assertEquals("Connected", Copy.pairingConnection(setup.approved("phone"), LinkSnapshot(LinkState.READY)))
        assertNull(Copy.pairingConnection(setup.removing(), LinkSnapshot(LinkState.READY)))
        // Bonded and waiting for the app approval: not "Connecting…".
        assertEquals("Waiting for approval", Copy.pairingConnection(setup, LinkSnapshot(LinkState.SUBSCRIBING)))
    }

    @Test fun pairedDeviceShowsActualRecoveryReasonInsteadOfDisconnected() {
        val paired = DevicePairing().begin().approved("phone")
        assertEquals("Reconnecting…", Copy.pairingConnection(paired, LinkSnapshot(LinkState.RECONNECTING)))
        assertEquals("Bluetooth is off", Copy.pairingConnection(paired,
            LinkSnapshot(LinkState.DISCONNECTED, LinkIssue.BLUETOOTH_OFF)))
        assertEquals("Notifications not shared", Copy.pairingConnection(paired,
            LinkSnapshot(LinkState.ERROR, LinkIssue.ANCS_UNAVAILABLE)))
    }

    @Test fun rememberedDeviceHasDifferentPairingInstructionsFromForgottenDevice() {
        val paired = DevicePairing().begin().approved("phone")
        val saved = paired.removed(false)
        val fresh = paired.removed(true)
        assertNotEquals(Copy.pairLabel(saved), Copy.pairLabel(fresh))
        assertTrue(Copy.pairInstructions(saved).contains("Again"))
        assertFalse(Copy.pairInstructions(fresh).contains("Again"))
        assertTrue(Copy.pairInstructions(fresh).contains("select this watch"))
    }

    @Test fun forgetProgressAndConfirmationExplainDifferentOutcomeFromUnpair() {
        val removing = DevicePairing().begin().approved("phone").removing()
        assertNotEquals(Copy.pairingPhase(removing, true), Copy.pairingPhase(removing, false))
        assertTrue(Copy.removalExplanation(false).contains("Keeps"))
        assertTrue(Copy.removalExplanation(true).contains("removes saved"))
    }

    @Test fun disconnectKeepsDeviceContextAndPrunesPeerDataFromBackStack() {
        val nav = BridgeNavigator(listOf(NavEntry("home", 0)))
        nav.navigate("media"); nav.navigate("contact/42"); nav.navigate("device")
        val deviceEntry = nav.top
        nav.removePeerScreens()
        assertEquals(deviceEntry, nav.top)
        assertEquals(listOf("home", "device"), nav.stack.map { it.route })
        nav.popBackStack()
        assertEquals("home", nav.top.route)
    }

    @Test fun disconnectFromFeatureReturnsHomeAndKeepsDiagnosticsAvailable() {
        val nav = BridgeNavigator(listOf(NavEntry("home", 0)))
        nav.navigate("notifications"); nav.navigate("notification/42")
        nav.removePeerScreens()
        assertEquals("home", nav.top.route)
        nav.navigate("device"); nav.navigate("diagnostics")
        nav.removePeerScreens()
        assertEquals("diagnostics", nav.top.route)
    }

    @Test fun repeatedDeviceIntentsDoNotCreateRepeatedBackSteps() {
        val nav = BridgeNavigator(listOf(NavEntry("home", 0)))
        nav.navigate("device"); nav.navigate("device")
        assertEquals(2, nav.stack.size)
        nav.popBackStack()
        assertEquals("home", nav.top.route)
    }
}
