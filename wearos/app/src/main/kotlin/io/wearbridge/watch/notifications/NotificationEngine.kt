package io.wearbridge.watch.notifications

import android.os.SystemClock
import io.wearbridge.ancs.ActionId
import io.wearbridge.ancs.AncsNotification
import io.wearbridge.ancs.AncsSession
import io.wearbridge.ancs.Category
import io.wearbridge.ancs.EventId
import io.wearbridge.ancs.NotificationSourceEvent
import io.wearbridge.watch.diagnostics.DiagLog
import io.wearbridge.watch.diagnostics.Redact
import io.wearbridge.watch.settings.BridgeSettings
import io.wearbridge.watch.settings.PolicyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Turns ANCS results into what the user sees and feels: the repository (inbox and call surfaces),
 * posted watch notifications and haptics. All the policy lives here: reconnect grace, burst
 * coalescing, the notification quota and experimental-action gating. Confined to the BLE scope.
 */
class NotificationEngine(
    private val repository: NotificationRepository,
    private val notifier: MirrorNotifier,
    private val settings: BridgeSettings,
    private val policy: PolicyStore,
    private val scope: CoroutineScope,
) : AncsSession.Listener {

    private var lastAlertAt = 0L
    private val sourceArrival = HashMap<Long, Long>()
    private var purgeJob: Job? = null

    /** Called for every raw Notification Source packet, before ANCS processing, for latency and diagnostics. */
    fun onSourcePacket(packet: ByteArray) {
        val event = NotificationSourceEvent.parse(packet) ?: return
        sourceArrival[event.uid] = SystemClock.elapsedRealtime()
        if (event.category == Category.UNKNOWN) {
            DiagLog.i("ns unknown category code=${packet[2].toInt() and 0xFF} flags=${event.flags}")
        }
    }

    /** The link dropped: keep content visible but mark it stale, since its actions can't work until replay. */
    fun onLinkReset() {
        sourceArrival.clear()
        purgeJob?.cancel()
        repository.markAllStale()
        notifier.cancelAll()
    }

    /**
     * Subscribed: iOS now replays every current notification. Whatever isn't replayed within a few
     * seconds was cleared on the iPhone while we were away.
     */
    fun onSubscribed() {
        purgeJob?.cancel()
        purgeJob = scope.launch {
            delay(STALE_PURGE_MS)
            repository.purgeStale().forEach(notifier::cancel)
        }
    }

    /** Only actions whose iOS label the user was shown may be performed. */
    fun canPerform(uid: Long, action: ActionId): Boolean {
        val n = repository.get(uid) ?: return false
        if (n.stale) return false
        return when (action) {
            ActionId.POSITIVE -> n.ancs.positiveActionLabel != null
            ActionId.NEGATIVE -> n.ancs.negativeActionLabel != null
        }
    }

    override fun onNotification(notification: AncsNotification, event: NotificationSourceEvent) {
        if (notification.category in CALL_CATEGORIES) {
            // System call-UI labels (e.g. "Answer"), not user content. Logged to verify action semantics.
            DiagLog.i("call actions cat=${notification.category} positive=${notification.positiveActionLabel} negative=${notification.negativeActionLabel}")
        }
        val gated = if (notification.category == Category.ACTIVE_CALL && !settings.endCallFromWatch.value) {
            notification.copy(negativeActionLabel = null) // experimental End Call is off
        } else notification

        val app = AppCatalog.resolve(gated.appIdentifier, gated.appDisplayName, gated.category)
        gated.appIdentifier?.let { id -> gated.appDisplayName?.let { policy.noteApp(id, it) } }
        val decision = NotificationPolicy.decide(gated.category, event.isImportant, app.kind, policy.rule(gated.appIdentifier), policy.mode.value)
        if (!decision.keep) {
            // The user turned this app off: don't store, post or buzz. Drop anything already shown.
            repository.remove(gated.uid)
            notifier.cancel(gated.uid)
            sourceArrival.remove(gated.uid)
            DiagLog.i("notif FILTERED app=${gated.appIdentifier} uid=${Redact.uid(gated.uid)}")
            return
        }

        val wn = WatchNotification(gated, receivedAtMillis = System.currentTimeMillis(), masked = decision.mask)
        repository.upsert(wn)

        // Reconnect grace: never buzz for replayed (pre-existing) notifications, and coalesce bursts.
        // A connected call is a state change the user just caused, not news, so it never buzzes.
        val elapsed = SystemClock.elapsedRealtime()
        val isNew = event.eventId == EventId.ADDED && !event.isPreExisting
        val alert = decision.surface && decision.vibrate &&
            !wn.isActiveCall && !event.isSilent && (isNew || wn.isIncomingCall && !event.isPreExisting) &&
            (wn.isIncomingCall || elapsed - lastAlertAt > ALERT_COALESCE_MS)
        if (alert) lastAlertAt = elapsed

        // Replayed notifications go to the in-app inbox only. Posting them all floods the watch and
        // Android's 50-per-app notification quota. A ringing call is the exception.
        val surface = decision.surface && (!event.isPreExisting || wn.isIncomingCall || notifier.isPosted(gated.uid))
        if (surface) notifier.show(wn, alert) else notifier.cancel(gated.uid)

        val latency = sourceArrival.remove(gated.uid)?.let { elapsed - it }
        DiagLog.i(
            "notif ${event.eventId} app=${gated.appIdentifier} cat=${gated.category} " +
                "uid=${Redact.uid(gated.uid)} alert=$alert surface=$surface masked=${decision.mask} latency=${latency ?: "?"}ms",
        )
    }

    override fun onNotificationRemoved(uid: Long) {
        DiagLog.i("notif REMOVED uid=${Redact.uid(uid)}")
        repository.remove(uid)
        notifier.cancel(uid)
        sourceArrival.remove(uid)
    }

    override fun onAppName(appIdentifier: String, displayName: String) {
        policy.noteApp(appIdentifier, displayName)
        repository.updateAppName(appIdentifier, displayName)
        // Re-post visible notifications from this app so they show the proper name (silently).
        repository.items.value
            .filter { it.ancs.appIdentifier == appIdentifier && !it.stale && notifier.isPosted(it.uid) }
            .forEach { notifier.show(it, alert = false) }
    }

    override fun onActionResult(uid: Long, action: ActionId, success: Boolean) {
        repository.setActionStatus(ActionStatus(uid, action, if (success) ActionStatus.State.DONE else ActionStatus.State.FAILED))
        DiagLog.i("action $action uid=${Redact.uid(uid)} success=$success")
    }

    override fun onProtocolIssue(reason: String) = DiagLog.w("ancs: $reason")

    private companion object {
        val CALL_CATEGORIES = setOf(Category.INCOMING_CALL, Category.MISSED_CALL, Category.ACTIVE_CALL)
        const val ALERT_COALESCE_MS = 1_500L
        const val STALE_PURGE_MS = 5_000L
    }
}
