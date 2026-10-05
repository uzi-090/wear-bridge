package io.wearbridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeMessagesTest {

    @Test
    fun `status round-trips`() {
        val s = WatchStatus(72, charging = true, notificationsReady = true, mediaReady = false, inboxCount = 12,
            model = "SM-R895F", osVersion = "16", appVersion = "0.2.0")
        assertEquals(s, BridgeCodec.decodeStatus(BridgeCodec.encodeStatus(s)))
    }

    @Test
    fun `unknown battery is null and counts are clamped`() {
        val s = WatchStatus(null, false, false, true, 70_000, "", "", "")
        val d = BridgeCodec.decodeStatus(BridgeCodec.encodeStatus(s))
        assertNull(d.battery)
        assertEquals(0xFFFF, d.inboxCount)
        assertTrue(d.mediaReady)
    }

    @Test
    fun `switch payload`() {
        assertTrue(BridgeCodec.decodeSwitch(BridgeCodec.encodeSwitch(true)))
        assertFalse(BridgeCodec.decodeSwitch(BridgeCodec.encodeSwitch(false)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `truncated status is rejected`() {
        BridgeCodec.decodeStatus(byteArrayOf(50))
    }
}
