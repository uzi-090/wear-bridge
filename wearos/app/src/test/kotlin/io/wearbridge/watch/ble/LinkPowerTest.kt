package io.wearbridge.watch.ble

import android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_BALANCED
import android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkPowerTest {
    /** A timer the test fires by hand. */
    private class FakeTimer : LinkPower.IdleTimer {
        var pending: (() -> Unit)? = null
        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            pending = action
            return { if (pending === action) pending = null }
        }
        fun fire() = pending?.also { pending = null }?.invoke()
    }

    private val requests = mutableListOf<Int>()
    private val timer = FakeTimer()
    private val power = LinkPower(request = { requests += it; true }, timer = timer)

    @Test
    fun `nothing is requested before the link is ready`() {
        power.activity()
        power.setForeground(true)
        assertEquals(emptyList<Int>(), requests)
        assertNull(timer.pending)
    }

    @Test
    fun `ready starts balanced, then drops to low power when idle`() {
        power.onReady()
        assertEquals(listOf(CONNECTION_PRIORITY_BALANCED), requests)
        timer.fire()
        assertEquals(listOf(CONNECTION_PRIORITY_BALANCED, CONNECTION_PRIORITY_LOW_POWER), requests)
    }

    @Test
    fun `activity while idle goes back to balanced and restarts the idle timer`() {
        power.onReady()
        timer.fire()
        power.activity()
        assertEquals(CONNECTION_PRIORITY_BALANCED, power.priority)
        assertEquals(3, requests.size)
        // More traffic while already balanced doesn't re-request.
        power.activity()
        assertEquals(3, requests.size)
        timer.fire()
        assertEquals(CONNECTION_PRIORITY_LOW_POWER, power.priority)
    }

    @Test
    fun `stays balanced while a screen is showing`() {
        power.onReady()
        power.setForeground(true)
        assertNull(timer.pending)
        power.activity()
        assertNull(timer.pending)
        assertEquals(CONNECTION_PRIORITY_BALANCED, power.priority)
        power.setForeground(false)
        timer.fire()
        assertEquals(CONNECTION_PRIORITY_LOW_POWER, power.priority)
    }

    @Test
    fun `link down cancels the idle drop and a new link starts fresh`() {
        power.onReady()
        power.onDown()
        assertNull(timer.pending)
        assertNull(power.priority)
        power.activity()
        assertEquals(1, requests.size)
        power.onReady()
        assertEquals(listOf(CONNECTION_PRIORITY_BALANCED, CONNECTION_PRIORITY_BALANCED), requests)
    }

    @Test
    fun `a refused request is retried on the next activity`() {
        var allow = false
        val tries = mutableListOf<Int>()
        val p = LinkPower(request = { tries += it; allow }, timer = timer)
        p.onReady()
        assertNull(p.priority)
        allow = true
        p.activity()
        assertEquals(CONNECTION_PRIORITY_BALANCED, p.priority)
        assertEquals(2, tries.size)
    }
}
