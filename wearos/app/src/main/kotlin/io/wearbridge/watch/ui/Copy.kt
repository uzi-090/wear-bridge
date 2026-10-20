package io.wearbridge.watch.ui

import io.wearbridge.watch.ble.LinkIssue
import io.wearbridge.watch.ble.LinkSnapshot
import io.wearbridge.watch.ble.LinkState
import io.wearbridge.watch.ble.DevicePairing
import io.wearbridge.watch.ble.PairingPhase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Plain-language status copy. Technical details only appear on the Diagnostics screen. */
object Copy {
    fun pairingPhase(pairing: DevicePairing, forgetting: Boolean = false): String = when (pairing.phase) {
        PairingPhase.UNPAIRED -> "Unpaired"
        PairingPhase.PAIRING -> "Pairing"
        PairingPhase.PAIRED -> "Paired"
        PairingPhase.REMOVING -> if (forgetting) "Forgetting…" else "Unpairing…"
    }

    /** A working Bluetooth link alone must never look like completed app approval. */
    fun pairingConnection(pairing: DevicePairing, link: LinkSnapshot): String? = when {
        !pairing.enabled -> null
        // Notifications wait for approval, so a bonded setup link stops at SUBSCRIBING until then.
        pairing.phase == PairingPhase.PAIRING && (link.state == LinkState.SUBSCRIBING || link.state == LinkState.READY) -> "Waiting for approval"
        else -> headline(link)
    }

    fun pairLabel(pairing: DevicePairing) = if (pairing.address == null) "Pair iPhone" else "Pair again"
    // The iPhone may have kept or forgotten this watch, so name the start of either button.
    fun pairInstructions(pairing: DevicePairing) = if (pairing.address == null)
        "On your iPhone, open Bridge, tap Pair Watch and select this watch."
    else "On your iPhone, open Bridge and tap Pair Watch (or Pair Watch Again)."

    fun removalExplanation(forget: Boolean) = if (forget)
        "Clears data and removes saved iPhone."
    else "Clears synced data. Keeps iPhone saved."

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
            LinkIssue.BACKGROUND_START_BLOCKED -> "Open Bridge to reconnect"
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
            LinkIssue.BACKGROUND_START_BLOCKED -> "Your watch paused background startup. Open Bridge on the watch to restore the connection."
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
