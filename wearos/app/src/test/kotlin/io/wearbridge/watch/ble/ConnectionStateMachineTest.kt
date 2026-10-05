package io.wearbridge.watch.ble

import io.wearbridge.watch.ble.LinkEffect.CloseClient
import io.wearbridge.watch.ble.LinkEffect.CreateBond
import io.wearbridge.watch.ble.LinkEffect.Discover
import io.wearbridge.watch.ble.LinkEffect.OpenClient
import io.wearbridge.watch.ble.LinkEffect.ScheduleRetry
import io.wearbridge.watch.ble.LinkEffect.StartAdvertising
import io.wearbridge.watch.ble.LinkEffect.Subscribe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateMachineTest {

    private val sm = ConnectionStateMachine(maxAttempts = 3)

    private fun ready() {
        sm.on(LinkEvent.Start)
        sm.on(LinkEvent.CentralConnected)
        sm.on(LinkEvent.ClientReady)
        sm.on(LinkEvent.ServicesDiscovered(hasAncs = true, bonded = true))
        sm.on(LinkEvent.Subscribed)
    }

    @Test
    fun `happy path reaches READY with the right effects`() {
        assertEquals(listOf(StartAdvertising), sm.on(LinkEvent.Start))
        assertEquals(LinkState.ADVERTISING, sm.snapshot.state)
        assertTrue(OpenClient in sm.on(LinkEvent.CentralConnected))
        assertEquals(listOf(Discover), sm.on(LinkEvent.ClientReady))
        assertEquals(listOf(Subscribe), sm.on(LinkEvent.ServicesDiscovered(hasAncs = true, bonded = true)))
        sm.on(LinkEvent.Subscribed)
        assertEquals(LinkSnapshot(LinkState.READY), sm.snapshot)
    }

    @Test
    fun `unbonded link goes through pairing then rediscovers`() {
        sm.on(LinkEvent.Start); sm.on(LinkEvent.CentralConnected); sm.on(LinkEvent.ClientReady)
        assertEquals(listOf(CreateBond), sm.on(LinkEvent.ServicesDiscovered(hasAncs = false, bonded = false)))
        assertEquals(LinkState.PAIRING, sm.snapshot.state)
        assertEquals(listOf(Discover), sm.on(LinkEvent.Bonded))
        assertEquals(LinkState.DISCOVERING_SERVICES, sm.snapshot.state)
    }

    @Test
    fun `auth failure while subscribing triggers pairing`() {
        sm.on(LinkEvent.Start); sm.on(LinkEvent.CentralConnected); sm.on(LinkEvent.ClientReady)
        sm.on(LinkEvent.ServicesDiscovered(hasAncs = true, bonded = true))
        assertEquals(listOf(CreateBond), sm.on(LinkEvent.SubscribeFailed(authRequired = true)))
    }

    @Test
    fun `missing ANCS retries with exponential backoff then errors`() {
        sm.on(LinkEvent.Start); sm.on(LinkEvent.CentralConnected); sm.on(LinkEvent.ClientReady)
        val delays = (1..3).map {
            val effects = sm.on(LinkEvent.ServicesDiscovered(hasAncs = false, bonded = true))
            assertEquals(listOf(Discover), sm.on(LinkEvent.RetryTimer))
            (effects.single() as ScheduleRetry).delayMs
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L), delays)
        sm.on(LinkEvent.ServicesDiscovered(hasAncs = false, bonded = true))
        assertEquals(LinkState.ERROR, sm.snapshot.state)
        assertEquals(LinkIssue.ANCS_UNAVAILABLE, sm.snapshot.issue)
    }

    @Test
    fun `backoff is capped`() {
        val big = ConnectionStateMachine(maxAttempts = 50)
        assertEquals(60_000L, big.backoffMs(30))
    }

    @Test
    fun `link loss after READY shows reconnecting and restarts advertising`() {
        ready()
        val effects = sm.on(LinkEvent.CentralDisconnected)
        assertEquals(LinkState.RECONNECTING, sm.snapshot.state)
        assertTrue(CloseClient in effects && StartAdvertising in effects)
        sm.on(LinkEvent.CentralConnected)
        assertEquals(LinkState.CONNECTED, sm.snapshot.state)
    }

    @Test
    fun `link loss before first READY goes back to plain advertising`() {
        sm.on(LinkEvent.Start); sm.on(LinkEvent.CentralConnected)
        sm.on(LinkEvent.CentralDisconnected)
        assertEquals(LinkState.ADVERTISING, sm.snapshot.state)
    }

    @Test
    fun `duplicate connect and stale events are ignored`() {
        ready()
        assertTrue(sm.on(LinkEvent.CentralConnected).isEmpty())
        assertTrue(sm.on(LinkEvent.ClientReady).isEmpty())
        assertTrue(sm.on(LinkEvent.Bonded).isEmpty())
        assertEquals(LinkState.READY, sm.snapshot.state)
    }

    @Test
    fun `bluetooth off and on`() {
        ready()
        sm.on(LinkEvent.BluetoothOff)
        assertEquals(LinkSnapshot(LinkState.DISCONNECTED, LinkIssue.BLUETOOTH_OFF), sm.snapshot)
        // A late disconnect callback after BT off must not restart advertising.
        assertTrue(sm.on(LinkEvent.CentralDisconnected).isEmpty())
        assertEquals(listOf(StartAdvertising), sm.on(LinkEvent.BluetoothOn))
        assertEquals(LinkState.RECONNECTING, sm.snapshot.state)
    }

    @Test
    fun `gatt client failure while READY rebuilds the client`() {
        ready()
        val effects = sm.on(LinkEvent.SetupFailed)
        assertEquals(LinkState.CONNECTED, sm.snapshot.state)
        assertTrue(CloseClient in effects && effects.last() is ScheduleRetry)
        assertEquals(listOf(OpenClient), sm.on(LinkEvent.RetryTimer))
    }

    @Test
    fun `advertising unsupported is an error with a specific issue`() {
        sm.on(LinkEvent.Start)
        sm.on(LinkEvent.AdvertisingFailed(unsupported = true))
        assertEquals(LinkSnapshot(LinkState.ERROR, LinkIssue.ADVERTISING_UNSUPPORTED), sm.snapshot)
        assertEquals(listOf(StartAdvertising), sm.on(LinkEvent.Start))
    }

    @Test
    fun `user stop disconnects everything and ignores later events`() {
        ready()
        sm.on(LinkEvent.Stop)
        assertEquals(LinkState.DISCONNECTED, sm.snapshot.state)
        assertTrue(sm.on(LinkEvent.CentralConnected).isEmpty())
    }
}
