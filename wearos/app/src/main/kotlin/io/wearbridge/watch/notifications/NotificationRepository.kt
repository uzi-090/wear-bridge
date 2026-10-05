package io.wearbridge.watch.notifications

import io.wearbridge.ancs.ActionId
import io.wearbridge.ancs.AncsNotification
import io.wearbridge.ancs.Category
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.ZoneId

/** An iPhone notification as the watch holds it. */
data class WatchNotification(
    val ancs: AncsNotification,
    val receivedAtMillis: Long,
    /** True after a disconnect: iOS will replay it on reconnect, and until then its actions can't work. */
    val stale: Boolean = false,
    /** Content hidden on the watch until the user reveals it (policy: finance apps, or per-app rule). */
    val masked: Boolean = false,
    /** History only: iOS removed it from the iPhone. It has no actions any more. */
    val clearedOnPhone: Boolean = false,
    /** When iOS removed it (wall clock), for history expiry. */
    val clearedAtMillis: Long = 0,
) {
    val uid: Long get() = ancs.uid
    val app: AppInfo get() = AppCatalog.resolve(ancs.appIdentifier, ancs.appDisplayName, ancs.category)
    val appLabel: String get() = app.name
    val isIncomingCall: Boolean get() = ancs.category == Category.INCOMING_CALL
    val isActiveCall: Boolean get() = ancs.category == Category.ACTIVE_CALL
    val hasActions: Boolean get() = ancs.positiveActionLabel != null || ancs.negativeActionLabel != null

    /** The iPhone's timestamp, interpreted in the watch's zone (ANCS carries none). */
    val timeMillis: Long
        get() = ancs.date?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: receivedAtMillis

    /**
     * Notifications from one conversation share a key: same app and same title (iOS puts the sender
     * or chat name in the title). Masked ones group by app only, so the key never depends on hidden text.
     */
    val threadKey: String get() = (ancs.appIdentifier ?: "?") + "\u0000" + if (masked) "" else ancs.title
}

/** One conversation in the inbox, newest first. */
data class NotificationThread(val items: List<WatchNotification>) {
    val latest: WatchNotification get() = items.first()
    val count: Int get() = items.size
}

data class ActionStatus(val uid: Long, val action: ActionId, val state: State) {
    enum class State { SENDING, DONE, FAILED, UNAVAILABLE }
}

/**
 * In-memory store. Nothing is persisted, so retention is minimal by design: the inbox mirrors what is
 * on the iPhone right now. With [historyEnabled], notifications the iPhone clears stay for
 * [HISTORY_TTL_MS] (never calls, and never hidden-content apps such as banking or OTP).
 * Safe to call from any thread.
 */
class NotificationRepository(
    private val historyEnabled: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _items = MutableStateFlow<List<WatchNotification>>(emptyList())
    val items: StateFlow<List<WatchNotification>> = _items

    private val _incomingCall = MutableStateFlow<WatchNotification?>(null)
    val incomingCall: StateFlow<WatchNotification?> = _incomingCall

    /** The connected call (undocumented ANCS category 12), whose only action is iOS's "End Call". */
    private val _activeCall = MutableStateFlow<WatchNotification?>(null)
    val activeCall: StateFlow<WatchNotification?> = _activeCall

    private val _actionStatus = MutableStateFlow<ActionStatus?>(null)
    val actionStatus: StateFlow<ActionStatus?> = _actionStatus

    /**
     * UIDs the user deleted on the watch. iOS replays them on every reconnect (it still has them),
     * so they're remembered for this process and not shown again. Bounded, oldest dropped first.
     */
    private val deleted = LinkedHashSet<Long>()

    fun upsert(n: WatchNotification) {
        _items.update { list ->
            (listOf(n) + list.filterNot { it.uid == n.uid })
                .let(::expire)
                .sortedByDescending { it.timeMillis }
                .take(MAX_ITEMS)
        }
        if (n.isIncomingCall) _incomingCall.value = n
        if (n.isActiveCall) _activeCall.value = n
    }

    /** iOS removed it (cleared on the iPhone, or the call ended). Kept as history when that's on. */
    fun remove(uid: Long) {
        val keep = historyEnabled()
        _items.update { list ->
            list.mapNotNull { n ->
                when {
                    n.uid != uid -> n
                    keep && n.keepsHistory() -> n.asCleared()
                    else -> null
                }
            }.let(::expire)
        }
        if (_incomingCall.value?.uid == uid) _incomingCall.value = null
        if (_activeCall.value?.uid == uid) _activeCall.value = null
        synchronized(deleted) { deleted.remove(uid) }
    }

    fun get(uid: Long): WatchNotification? = _items.value.firstOrNull { it.uid == uid }

    /** Removes it from the watch only. The iPhone keeps it (that needs iOS's own action, e.g. "Clear"). */
    fun delete(uid: Long) {
        val n = get(uid) ?: return
        if (n.isIncomingCall || n.isActiveCall) return // the call surfaces own these
        if (!n.clearedOnPhone) rememberDeleted(uid)
        _items.update { list -> list.filterNot { it.uid == uid } }
    }

    /** Clears the watch inbox, except for calls in progress. Returns the UIDs removed. */
    fun deleteAll(): List<Long> {
        val removed = _items.value.filterNot { it.isIncomingCall || it.isActiveCall }
        removed.filterNot { it.clearedOnPhone }.forEach { rememberDeleted(it.uid) }
        val ids = removed.map { it.uid }.toSet()
        _items.update { list -> list.filterNot { it.uid in ids } }
        return ids.toList()
    }

    fun isDeleted(uid: Long): Boolean = synchronized(deleted) { uid in deleted }

    /** History was turned off: forget everything the iPhone has already cleared. */
    fun clearHistory() {
        _items.update { list -> list.filterNot { it.clearedOnPhone } }
    }

    fun updateAppName(appIdentifier: String, displayName: String) {
        _items.update { list ->
            list.map { if (it.ancs.appIdentifier == appIdentifier) it.copy(ancs = it.ancs.copy(appDisplayName = displayName)) else it }
        }
        _incomingCall.update { c ->
            if (c?.ancs?.appIdentifier == appIdentifier) c.copy(ancs = c.ancs.copy(appDisplayName = displayName)) else c
        }
    }

    /** On disconnect: keep the content visible, but actions are disabled until iOS replays it. */
    fun markAllStale() {
        _items.update { list -> list.map { if (it.clearedOnPhone) it else it.copy(stale = true) } }
        _incomingCall.value = null // a ringing call can't be acted on any more
        _activeCall.value = null
    }

    /** After reconnect and replay: whatever iOS didn't re-announce was cleared on the iPhone meanwhile. */
    fun purgeStale(): List<Long> {
        val gone = _items.value.filter { it.stale }.map { it.uid }
        gone.forEach(::remove)
        return gone
    }

    fun setActionStatus(status: ActionStatus?) {
        _actionStatus.value = status
    }

    private fun WatchNotification.keepsHistory(): Boolean =
        !masked && ancs.category !in NO_HISTORY_CATEGORIES

    private fun WatchNotification.asCleared(): WatchNotification = copy(
        stale = false,
        clearedOnPhone = true,
        clearedAtMillis = now(),
        ancs = ancs.copy(positiveActionLabel = null, negativeActionLabel = null),
    )

    private fun expire(list: List<WatchNotification>): List<WatchNotification> {
        if (list.none { it.clearedOnPhone }) return list
        val cutoff = now() - HISTORY_TTL_MS
        return list.filterNot { it.clearedOnPhone && it.clearedAtMillis < cutoff }
    }

    private fun rememberDeleted(uid: Long) = synchronized(deleted) {
        deleted.add(uid)
        while (deleted.size > MAX_DELETED) deleted.remove(deleted.first())
    }

    companion object {
        const val MAX_ITEMS = 50
        const val HISTORY_TTL_MS = 24 * 60 * 60 * 1000L
        private const val MAX_DELETED = 256

        /** Ringing and connected calls are states, not messages. Missed calls stay (they're useful). */
        private val NO_HISTORY_CATEGORIES = setOf(Category.INCOMING_CALL, Category.ACTIVE_CALL)

        /** Groups notifications into conversations, newest conversation first. */
        fun threads(items: List<WatchNotification>): List<NotificationThread> =
            items.sortedByDescending { it.timeMillis }
                .groupBy { it.threadKey }
                .values
                .map(::NotificationThread)
    }
}

/** Last-resort label derived from the bundle ID, when neither iOS nor [AppCatalog] knows the app. */
object AppLabels {
    fun fallback(bundleId: String?): String =
        bundleId?.substringAfterLast('.')?.replaceFirstChar(Char::uppercase) ?: "iPhone"
}
