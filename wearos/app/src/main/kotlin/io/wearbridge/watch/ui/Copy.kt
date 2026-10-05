package io.wearbridge.watch.ui

import io.wearbridge.watch.ble.LinkIssue
import io.wearbridge.watch.ble.LinkSnapshot
import io.wearbridge.watch.ble.LinkState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Plain-language status copy. Technical details only appear on the Diagnostics screen. */
object Copy {
    fun headline(s: LinkSnapshot): String = when (s.state) {
        LinkState.READY -> "Connected"
        LinkState.RECONNECTING -> "Reconnecting…"
        LinkState.ADVERTISING -> "Waiting for iPhone"
        LinkState.PAIRING -> "Pairing"
        LinkState.CONNECTED, LinkState.DISCOVERING_SERVICES, LinkState.SUBSCRIBING -> "Connecting…"
        LinkState.DISCONNECTED -> when (s.issue) {
            LinkIssue.BLUETOOTH_OFF -> "Bluetooth is off"
            LinkIssue.PERMISSION_MISSING -> "Permission needed"
            else -> "Not connected"
        }
        LinkState.ERROR -> when (s.issue) {
            LinkIssue.ADVERTISING_UNSUPPORTED -> "Can't connect"
            LinkIssue.ADVERTISING_FAILED -> "Couldn't become visible"
            LinkIssue.PAIRING_FAILED -> "Pairing didn't finish"
            LinkIssue.ANCS_UNAVAILABLE -> "Notifications not shared"
            else -> "Watch connection failed"
        }
    }

    fun guidance(s: LinkSnapshot): String? = when (s.state) {
        LinkState.READY -> null
        LinkState.ADVERTISING -> "Keep your iPhone nearby with Bluetooth on."
        LinkState.RECONNECTING -> "Your iPhone will reconnect when it's in range."
        LinkState.PAIRING -> "Check that the codes match on your iPhone and watch, then confirm."
        LinkState.CONNECTED, LinkState.DISCOVERING_SERVICES, LinkState.SUBSCRIBING -> null
        LinkState.DISCONNECTED -> when (s.issue) {
            LinkIssue.BLUETOOTH_OFF -> "Turn on Bluetooth in watch settings."
            LinkIssue.PERMISSION_MISSING -> "Bridge needs Bluetooth access to talk to your iPhone."
            else -> null
        }
        LinkState.ERROR -> when (s.issue) {
            LinkIssue.ADVERTISING_UNSUPPORTED -> "This watch's Bluetooth can't host the iPhone connection."
            LinkIssue.PAIRING_FAILED -> "On your iPhone, open Settings › Bluetooth, forget this watch, then pair again from the Bridge app."
            LinkIssue.ANCS_UNAVAILABLE -> "On iPhone, open Settings › Bluetooth, tap ⓘ next to this watch and turn on Share System Notifications."
            else -> "The Bluetooth connection became unavailable."
        }
    }

    const val REPLY_UNAVAILABLE = "Reply isn't available for this notification."
    const val NO_ACTIONS = "Your iPhone doesn't offer actions for this notification."
    const val STALE = "Reconnecting. Actions are paused."
    const val CLEARED = "This notification was cleared on your iPhone."
    const val TRUNCATED = "Continued on iPhone"
    const val MASKED = "Content hidden"
    const val MASKED_DETAIL = "Content is hidden on your watch for this app."
    const val CLEARED_SHORT = "Cleared on iPhone"
    const val CLEARED_HISTORY = "Cleared on your iPhone. Kept here as history."
    const val DELETE = "Delete from watch"
    const val DELETE_DETAIL = "Stays on your iPhone"
    const val CLEAR_ALL = "Clear all"
    const val CLEAR_ALL_DETAIL = "Removes notifications from your watch. They stay on your iPhone."

    fun moreMessages(n: Int) = if (n == 1) "+1 more" else "+$n more"

    private val TIME = DateTimeFormatter.ofPattern("H:mm")
    private val DAY = DateTimeFormatter.ofPattern("d MMM")

    /** Compact, watch-friendly relative time: now, 2m, 3h, 5 Oct. */
    fun relative(timeMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val minutes = (nowMillis - timeMillis) / 60_000
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            minutes < 12 * 60 -> "${minutes / 60}h"
            else -> Instant.ofEpochMilli(timeMillis).atZone(ZoneId.systemDefault()).let {
                if (it.toLocalDate() == LocalDate.now()) it.format(TIME) else it.format(DAY)
            }
        }
    }

    fun isToday(timeMillis: Long): Boolean =
        Instant.ofEpochMilli(timeMillis).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
}
