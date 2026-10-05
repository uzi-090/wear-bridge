package io.wearbridge.watch.ble

import android.bluetooth.BluetoothGatt

/**
 * Picks the BLE connection priority from activity, so the always-on link costs little while nothing
 * happens. Without a request iOS keeps a ~30 ms interval: about 33 radio wake-ups a second, even idle.
 *
 * - Busy (any traffic on the link, or a Bridge screen showing): BALANCED, so notifications, media
 *   controls and calls stay quick.
 * - Quiet for [idleMs] with nothing on screen: LOW_POWER (100–125 ms interval with peripheral latency,
 *   within Apple's accessory connection-parameter rules).
 *
 * Only acts while the link is READY. Not thread-safe: call it from the link's single-threaded scope.
 */
class LinkPower(
    private val request: (priority: Int) -> Boolean,
    private val timer: IdleTimer,
    private val idleMs: Long = IDLE_MS,
    private val log: (String) -> Unit = {},
) {
    /** Runs [action] after [delayMs]; the returned function cancels it. */
    fun interface IdleTimer {
        fun schedule(delayMs: Long, action: () -> Unit): () -> Unit
    }

    private var ready = false
    private var foreground = false
    private var current: Int? = null
    private var cancelIdle: (() -> Unit)? = null

    /** The priority last granted, or null before the link is up (for tests and diagnostics). */
    val priority: Int? get() = current

    fun onReady() {
        ready = true
        busy()
    }

    fun onDown() {
        ready = false
        cancelIdle?.invoke()
        cancelIdle = null
        current = null // a new link starts from the iPhone's defaults
    }

    /** Something crossed the link (either way). */
    fun activity() {
        if (ready) busy()
    }

    /** A Bridge screen is (or is no longer) showing: stay responsive while the user is looking. */
    fun setForeground(on: Boolean) {
        foreground = on
        if (ready) busy()
    }

    private fun busy() {
        set(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
        cancelIdle?.invoke()
        cancelIdle = if (foreground) null else timer.schedule(idleMs) {
            cancelIdle = null
            if (ready && !foreground) set(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
        }
    }

    private fun set(priority: Int) {
        if (current == priority) return
        if (request(priority)) {
            current = priority
            log("link: priority ${if (priority == BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER) "low power" else "balanced"}")
        }
    }

    companion object {
        /** Long enough to cover a notification's attribute fetches and a burst of media taps. */
        const val IDLE_MS = 8_000L
    }
}
