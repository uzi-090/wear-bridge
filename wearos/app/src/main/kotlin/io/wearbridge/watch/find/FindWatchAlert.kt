package io.wearbridge.watch.find

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.wearbridge.protocol.BridgeMessageType
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.R
import io.wearbridge.watch.diagnostics.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * "Find watch", started from the iPhone app: the watch rings (alarm sound) and vibrates until the user
 * taps Found it, the iPhone stops it, or [MAX_RING_MS] passes. Everything runs on the main thread.
 */
class FindWatchAlert(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private var ringtone: Ringtone? = null

    private val _ringing = MutableStateFlow(false)
    val ringing: StateFlow<Boolean> = _ringing

    private val timeout = Runnable { stop(fromUser = false) }

    fun createChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Find watch", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When your iPhone is looking for this watch"
                setSound(null, null) // Bridge plays the alarm sound itself, so it can loop and stop
                enableVibration(false)
            },
        )
    }

    fun start() {
        main.post(::startOnMain)
    }

    private fun startOnMain() {
        if (_ringing.value) return
        _ringing.value = true
        DiagLog.i("find watch: ringing")
        playSound()
        vibrate()
        post()
        main.postDelayed(timeout, MAX_RING_MS)
    }

    /** [fromUser]: the user found it on the watch, so tell the iPhone app to stop showing "Ringing". */
    fun stop(fromUser: Boolean) {
        main.post { stopOnMain(fromUser) }
    }

    private fun stopOnMain(fromUser: Boolean) {
        if (!_ringing.value) return
        _ringing.value = false
        main.removeCallbacks(timeout)
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        DiagLog.i("find watch: stopped fromUser=$fromUser")
        if (fromUser) BridgeGraph.sendEvent(BridgeMessageType.FIND_WATCH_STOPPED)
    }

    private fun playSound() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
            play()
        }
        if (ringtone == null) DiagLog.w("find watch: no alarm sound available, vibrating only")
    }

    private fun vibrate() {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator?.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    @SuppressLint("MissingPermission") // canPost is checked
    private fun post() {
        if (!BridgeGraph.notifier.canPost) return
        val open = PendingIntent.getActivity(
            context, 40, Intent(context, FindWatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val found = PendingIntent.getBroadcast(
            context, 41, Intent(context, FindWatchReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle("Finding your watch")
            .setContentText("Your iPhone is looking for this watch")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true) // shown full screen only if the user allowed it, otherwise heads-up
            .addAction(0, "Found it", found)
            .setLocalOnly(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
    }

    companion object {
        const val CHANNEL = "find_watch"
        private const val NOTIFICATION_ID = 40
        private const val MAX_RING_MS = 60_000L
    }
}

/** "Found it" from the notification. Not exported. */
class FindWatchReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BridgeGraph.findWatch.stop(fromUser = true)
    }
}
