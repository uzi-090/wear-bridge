package io.wearbridge.watch.calls

import android.app.NotificationManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import io.wearbridge.ancs.ActionId
import io.wearbridge.protocol.Contact
import io.wearbridge.protocol.ContactsCodec
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.diagnostics.DiagLog
import io.wearbridge.watch.notifications.WatchNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** What the call surface shows. Calls are iPhone calls: this is call CONTROL, audio stays on the iPhone. */
sealed interface CallUi {
    data object None : CallUi
    data class Ringing(val call: WatchNotification, val caller: Caller, val silenced: Boolean) : CallUi
    /** Answer was sent; iOS is handing the call over (it removes the ringing call, then posts the connected one). */
    data class Answering(val caller: Caller) : CallUi
    data class Active(val call: WatchNotification, val caller: Caller, val startedAtMillis: Long) : CallUi
    data class Ended(val caller: Caller, val outcome: Outcome, val durationMillis: Long?) : CallUi

    enum class Outcome { ENDED, DECLINED, DECLINED_BY_SHAKE, ANSWERING }
}

/** Who's calling, enriched with the synced contact when there's a match (for the avatar and ★). */
data class Caller(val name: String, val detail: String, val contact: Contact?) {
    val initials: String
        get() = name.split(' ').filter { w -> w.firstOrNull()?.isLetter() == true }.take(2)
            .map { it.first().uppercaseChar() }.joinToString("")
}

/**
 * Drives the call experience on the watch, from the repository's ANCS call state:
 * - a ring-rhythm vibration while a call rings (respecting Do Not Disturb), until answered, declined,
 *   removed or **silenced** (Silence stops the watch only: iOS gives accessories no way to silence the
 *   iPhone's ringer);
 * - shake to decline (and optionally shake to end), with the accelerometer registered only while it can
 *   act, so it costs nothing the rest of the time;
 * - the call timer and the short "Call ended · 2:31" summary.
 * Main-thread confined.
 */
class CallController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val shake = ShakeDetector()

    private val _ui = MutableStateFlow<CallUi>(CallUi.None)
    val ui: StateFlow<CallUi> = _ui

    private var ringingUid: Long? = null
    private var silencedUid: Long? = null
    private var activeUid: Long? = null
    private var activeSince = 0L
    private var lastCaller: Caller? = null
    /** Set when the user acted from the watch, so the summary says what happened. */
    private var pendingOutcome: CallUi.Outcome? = null
    private var endedJob: Job? = null
    private var listening = false

    fun start() {
        scope.launch {
            combine(BridgeGraph.repository.incomingCall, BridgeGraph.repository.activeCall) { r, a -> r to a }
                .collect { (ringing, active) -> onCalls(ringing, active) }
        }
        scope.launch {
            combine(BridgeGraph.settings.shakeToDecline, BridgeGraph.settings.shakeToEnd, BridgeGraph.settings.endCallFromWatch) { _, _, _ -> }
                .collect { updateSensor() }
        }
    }

    /** Stops the watch ringing. The iPhone keeps ringing, and the call can still be answered or declined. */
    fun silence() {
        val uid = ringingUid ?: return
        silencedUid = uid
        vibrator?.cancel()
        DiagLog.i("call: silenced on watch")
        (_ui.value as? CallUi.Ringing)?.let { _ui.value = it.copy(silenced = true) }
    }

    fun answer() = act(ActionId.POSITIVE, CallUi.Outcome.ANSWERING)

    fun decline(byShake: Boolean = false) =
        act(ActionId.NEGATIVE, if (byShake) CallUi.Outcome.DECLINED_BY_SHAKE else CallUi.Outcome.DECLINED)

    /** iOS's "End Call" on a connected call (experimental, undocumented ANCS category). */
    fun end() {
        val a = _ui.value as? CallUi.Active ?: return
        if (a.call.ancs.negativeActionLabel == null) return
        pendingOutcome = CallUi.Outcome.ENDED
        BridgeGraph.performAction(a.call.uid, ActionId.NEGATIVE)
    }

    private fun act(action: ActionId, outcome: CallUi.Outcome?) {
        val r = _ui.value as? CallUi.Ringing ?: return
        val label = if (action == ActionId.POSITIVE) r.call.ancs.positiveActionLabel else r.call.ancs.negativeActionLabel
        if (label == null) return
        vibrator?.cancel()
        pendingOutcome = outcome
        BridgeGraph.performAction(r.call.uid, action)
    }

    private fun onCalls(ringing: WatchNotification?, active: WatchNotification?) {
        when {
            ringing != null -> {
                endedJob?.cancel()
                val caller = callerFor(ringing)
                lastCaller = caller
                if (ringingUid != ringing.uid) {
                    ringingUid = ringing.uid
                    pendingOutcome = null
                    if (silencedUid != ringing.uid) ring()
                }
                _ui.value = CallUi.Ringing(ringing, caller, silenced = silencedUid == ringing.uid)
            }
            active != null -> {
                endedJob?.cancel()
                stopRinging()
                if (activeUid != active.uid) {
                    activeUid = active.uid
                    activeSince = System.currentTimeMillis()
                }
                val caller = lastCaller?.takeIf { it.name == active.ancs.title || active.ancs.title.isBlank() } ?: callerFor(active)
                lastCaller = caller
                _ui.value = CallUi.Active(active, caller, activeSince)
            }
            else -> {
                val was = _ui.value
                stopRinging()
                val summary = when (was) {
                    is CallUi.Active -> CallUi.Ended(was.caller, CallUi.Outcome.ENDED, System.currentTimeMillis() - was.startedAtMillis)
                    // Answered from the watch: keep the caller on screen while iOS connects. Declined: say so.
                    // Otherwise (handled on the iPhone, or missed) no summary: the screen just closes.
                    is CallUi.Ringing -> when (pendingOutcome) {
                        CallUi.Outcome.ANSWERING -> CallUi.Answering(was.caller)
                        CallUi.Outcome.DECLINED, CallUi.Outcome.DECLINED_BY_SHAKE -> CallUi.Ended(was.caller, pendingOutcome!!, null)
                        else -> null
                    }
                    is CallUi.Answering -> null // the connected call never appeared
                    else -> null
                }
                activeUid = null
                pendingOutcome = null
                if (summary == null) {
                    _ui.value = CallUi.None
                } else {
                    _ui.value = summary
                    endedJob = scope.launch {
                        delay(if (summary is CallUi.Answering) ANSWER_TIMEOUT_MS else SUMMARY_MS)
                        _ui.value = CallUi.None
                    }
                }
            }
        }
        updateSensor()
    }

    private fun stopRinging() {
        if (ringingUid != null) vibrator?.cancel()
        ringingUid = null
    }

    /** A phone-like ring rhythm, repeating until stopped. Not while Do Not Disturb filters calls. */
    private fun ring() {
        val filter = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        if (filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
            DiagLog.i("call: Do Not Disturb, not vibrating")
            return
        }
        val effect = VibrationEffect.createWaveform(RING_PATTERN, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator?.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(effect, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
        }
    }

    // ---- shake ----

    private fun shakeCanAct(): Boolean = when (val u = _ui.value) {
        is CallUi.Ringing -> BridgeGraph.settings.shakeToDecline.value && u.call.ancs.negativeActionLabel != null
        is CallUi.Active -> BridgeGraph.settings.shakeToEnd.value && BridgeGraph.settings.endCallFromWatch.value &&
            u.call.ancs.negativeActionLabel != null
        else -> false
    }

    private fun updateSensor() {
        val want = shakeCanAct()
        if (want == listening) return
        val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (want && accel != null) {
            shake.reset(SystemClock.elapsedRealtime())
            listening = sensors.registerListener(sensorListener, accel, SensorManager.SENSOR_DELAY_GAME)
            DiagLog.i("call: shake detection on=$listening")
        } else if (!want && listening) {
            sensors?.unregisterListener(sensorListener)
            listening = false
            DiagLog.i("call: shake detection off")
        }
    }

    private val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    private var lastStatsAt = 0L

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val now = e.timestamp / 1_000_000
            val fired = shake.feed(e.values[0], e.values[1], e.values[2], now)
            // Debug builds: a line every 250 ms to tune the detector against real wrists (movement only).
            if (debuggable && now - lastStatsAt >= 250) {
                lastStatsAt = now
                val max = shake.readMax()
                if (max >= 5f) DiagLog.i("shake max=%.1f peaks=%d".format(max, shake.peaksInWindow))
            }
            if (!fired) return
            scope.launch { onShake() }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun onShake() {
        if (!shakeCanAct()) return
        confirmHaptic()
        when (_ui.value) {
            is CallUi.Ringing -> {
                DiagLog.i("call: declined by shake")
                decline(byShake = true)
            }
            is CallUi.Active -> {
                DiagLog.i("call: ended by shake")
                end()
            }
            else -> Unit
        }
    }

    private fun confirmHaptic() {
        vibrator?.cancel()
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 70, 90, 70), -1))
    }

    /** Name from iOS (it resolves contacts itself); avatar and ★ from the synced contacts when they match. */
    private fun callerFor(n: WatchNotification): Caller {
        val title = n.ancs.title.trim()
        val subtitle = n.ancs.subtitle.trim()
        val contacts = BridgeGraph.contacts
        val byName = contacts.contacts.value.firstOrNull { it.name.equals(title, ignoreCase = true) }
        val number = listOf(title, subtitle).firstOrNull { s -> s.count(Char::isDigit) >= 7 && s.all { it.isDigit() || it in "+-() " } }
        val byNumber = number?.let { contacts.findByPhone(ContactsCodec.normalizePhone(it)) }
        val contact = byName ?: byNumber
        val name = when {
            title.isNotBlank() && byNumber != null && title == number -> byNumber.name
            title.isNotBlank() -> title
            else -> "Unknown caller"
        }
        val detail = when {
            subtitle.isNotBlank() -> subtitle
            contact?.phones?.size == 1 -> io.wearbridge.watch.contacts.formatPhone(contact.phones.first())
            else -> ""
        }
        return Caller(name, detail, contact)
    }

    private companion object {
        /** Two pulses, then a pause: reads as "phone ringing" rather than a notification buzz. */
        val RING_PATTERN = longArrayOf(0, 450, 180, 450, 1_300)
        const val SUMMARY_MS = 2_200L
        /** iOS posts the connected call within ~100 ms of removing the ringing one (measured); 4 s is generous. */
        const val ANSWER_TIMEOUT_MS = 4_000L
    }
}
