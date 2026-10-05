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
}

data class ActionStatus(val uid: Long, val action: ActionId, val state: State) {
    enum class State { SENDING, DONE, FAILED, UNAVAILABLE }
}

/**
 * In-memory store (Phase 1: nothing is persisted, so retention is minimal by design).
 * Safe to call from any thread.
 */
class NotificationRepository {
    private val _items = MutableStateFlow<List<WatchNotification>>(emptyList())
    val items: StateFlow<List<WatchNotification>> = _items

    private val _incomingCall = MutableStateFlow<WatchNotification?>(null)
    val incomingCall: StateFlow<WatchNotification?> = _incomingCall

    /** The connected call (undocumented ANCS category 12), whose only action is iOS's "End Call". */
    private val _activeCall = MutableStateFlow<WatchNotification?>(null)
    val activeCall: StateFlow<WatchNotification?> = _activeCall

    private val _actionStatus = MutableStateFlow<ActionStatus?>(null)
    val actionStatus: StateFlow<ActionStatus?> = _actionStatus

    fun upsert(n: WatchNotification) {
        _items.update { list ->
            (listOf(n) + list.filterNot { it.uid == n.uid })
                .sortedByDescending { it.timeMillis }
                .take(MAX_ITEMS)
        }
        if (n.isIncomingCall) _incomingCall.value = n
        if (n.isActiveCall) _activeCall.value = n
    }

    fun remove(uid: Long) {
        _items.update { list -> list.filterNot { it.uid == uid } }
        if (_incomingCall.value?.uid == uid) _incomingCall.value = null
        if (_activeCall.value?.uid == uid) _activeCall.value = null
    }

    fun get(uid: Long): WatchNotification? = _items.value.firstOrNull { it.uid == uid }

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
        _items.update { list -> list.map { it.copy(stale = true) } }
        _incomingCall.value = null // a ringing call can't be acted on any more
        _activeCall.value = null
    }

    /** After reconnect and replay: whatever iOS didn't re-announce is gone on the iPhone. */
    fun purgeStale(): List<Long> {
        val removed = _items.value.filter { it.stale }.map { it.uid }
        _items.update { list -> list.filterNot { it.stale } }
        return removed
    }

    fun setActionStatus(status: ActionStatus?) {
        _actionStatus.value = status
    }

    private companion object {
        const val MAX_ITEMS = 50
    }
}

/** Last-resort label derived from the bundle ID, when neither iOS nor [AppCatalog] knows the app. */
object AppLabels {
    fun fallback(bundleId: String?): String =
        bundleId?.substringAfterLast('.')?.replaceFirstChar(Char::uppercase) ?: "iPhone"
}
