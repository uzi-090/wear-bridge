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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest

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
    private var lastReplayActivity = 0L

    init {
        scope.launch {
            repository.items.collectLatest { items ->
                val deadline = items.filter { it.clearedOnPhone }.minOfOrNull { it.clearedAtMillis + NotificationRepository.HISTORY_TTL_MS + 1 }
                    ?: return@collectLatest
                delay((deadline - System.currentTimeMillis()).coerceAtLeast(1))
                repository.expireHistory()
            }
        }
        scope.launch {
            combine(policy.rules, policy.mode) { _, _ -> Unit }.collect { reconcilePolicies() }
        }
    }

    private fun reconcilePolicies() {
        for (n in repository.items.value + listOfNotNull(repository.activeCall.value)) {
            val d = NotificationPolicy.decide(n.ancs.category, n.ancs.flags and 2 != 0,
                n.app.kind, policy.rule(n.ancs.appIdentifier), policy.mode.value)
            if (!d.keep || (n.clearedOnPhone && d.mask)) {
                repository.discard(n.uid); notifier.cancel(n.uid)
            } else {
                repository.setMasked(n.uid, d.mask)
                if (!d.surface) notifier.cancel(n.uid)
                else if (!n.stale && notifier.isPosted(n.uid)) notifier.show(n.copy(masked = d.mask), alert = false)
            }
        }
    }

    /** Called for every raw Notification Source packet, before ANCS processing, for latency and diagnostics. */
    fun onSourcePacket(packet: ByteArray) {
        val event = NotificationSourceEvent.parse(packet) ?: return
        lastReplayActivity = SystemClock.elapsedRealtime()
        sourceArrival[event.uid] = SystemClock.elapsedRealtime()
        if (event.category == Category.UNKNOWN) {
            DiagLog.i("ns unknown category code=${packet[2].toInt() and 0xFF} flags=${event.flags}")
        }
    }

    /** The link dropped: retain stale entries for replay reconciliation; the UI hides all peer content. */
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
            val started = SystemClock.elapsedRealtime()
            lastReplayActivity = started
            delay(STALE_PURGE_MS)
            while (SystemClock.elapsedRealtime() - started < REPLAY_MAX_MS &&
                (sourceArrival.isNotEmpty() || SystemClock.elapsedRealtime() - lastReplayActivity < REPLAY_QUIET_MS)) {
                delay(REPLAY_QUIET_MS)
            }
            repository.purgeStale().forEach(notifier::cancel) // kept as history when that's on
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
        if (!io.wearbridge.watch.BridgeGraph.featuresAvailable) return
        lastReplayActivity = SystemClock.elapsedRealtime()
        if (io.wearbridge.watch.BridgeGraph.demoMode) return // debug screenshots: real content stays off screen
        if (io.wearbridge.watch.battery.LowBatteryPolicy.isOwnWatchWarning(notification.appIdentifier, notification.title)) {
            sourceArrival.remove(notification.uid)
            repository.discard(notification.uid)
            notifier.cancel(notification.uid)
            return
        }
        if (repository.isDeleted(notification.uid)) {
            // Deleted on the watch earlier. iOS still has it and replays or updates it: stay quiet.
            sourceArrival.remove(notification.uid)
            return
        }
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
            repository.discard(gated.uid)
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
        // CallController posts connected calls only when Answer came from the watch.
        val surface = !wn.isActiveCall && decision.surface && (!event.isPreExisting || wn.isIncomingCall || notifier.isPosted(gated.uid))
        if (surface) notifier.show(wn, alert) else notifier.cancel(gated.uid)

        val latency = sourceArrival.remove(gated.uid)?.let { elapsed - it }
        DiagLog.i(
            "notif ${event.eventId} app=${gated.appIdentifier} cat=${gated.category} " +
                "uid=${Redact.uid(gated.uid)} alert=$alert surface=$surface masked=${decision.mask} latency=${latency ?: "?"}ms",
        )
    }

    override fun onNotificationRemoved(uid: Long) {
        if (!io.wearbridge.watch.BridgeGraph.featuresAvailable) return
        lastReplayActivity = SystemClock.elapsedRealtime()
        DiagLog.i("notif REMOVED uid=${Redact.uid(uid)}")
        repository.remove(uid)
        notifier.cancel(uid)
        sourceArrival.remove(uid)
    }

    override fun onAppName(appIdentifier: String, displayName: String) {
        if (!io.wearbridge.watch.BridgeGraph.featuresAvailable) return
        policy.noteApp(appIdentifier, displayName)
        repository.updateAppName(appIdentifier, displayName)
        // Re-post visible notifications from this app so they show the proper name (silently).
        repository.items.value
            .filter { it.ancs.appIdentifier == appIdentifier && !it.stale && notifier.isPosted(it.uid) }
            .forEach { notifier.show(it, alert = false) }
    }

    override fun onActionResult(uid: Long, action: ActionId, success: Boolean) {
        if (!io.wearbridge.watch.BridgeGraph.featuresAvailable) return
        repository.setActionStatus(ActionStatus(uid, action, if (success) ActionStatus.State.DONE else ActionStatus.State.FAILED))
        DiagLog.i("action $action uid=${Redact.uid(uid)} success=$success")
    }

    override fun onProtocolIssue(reason: String) = DiagLog.w("ancs: $reason")

    private companion object {
        val CALL_CATEGORIES = setOf(Category.INCOMING_CALL, Category.MISSED_CALL, Category.ACTIVE_CALL)
        const val ALERT_COALESCE_MS = 1_500L
        const val STALE_PURGE_MS = 5_000L
        const val REPLAY_QUIET_MS = 1_500L
        const val REPLAY_MAX_MS = 60_000L
    }
}
