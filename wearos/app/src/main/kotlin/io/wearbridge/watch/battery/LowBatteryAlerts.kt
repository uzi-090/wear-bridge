package io.wearbridge.watch.battery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.R
import io.wearbridge.watch.ui.MainActivity

/** Unknown readings never create a warning; confirmed charging or recovery rearms it. */
object LowBatteryPolicy {
    // The iPhone warning is about this watch; do not mirror it back as a second alert.
    fun isOwnWatchWarning(appIdentifier: String?, title: String?): Boolean =
        appIdentifier == "io.wearbridge.pairingpoc" && title == "Watch battery low"

    enum class Decision { IGNORE, REARM, ALERT }
    fun decide(percent: Int?, alerted: Boolean, charging: Boolean? = false): Decision = when {
        charging == true -> Decision.REARM
        percent == null || percent !in 0..100 -> Decision.IGNORE
        percent > 10 -> Decision.REARM
        charging == null -> Decision.IGNORE
        alerted -> Decision.IGNORE
        else -> Decision.ALERT
    }
}

/** Consumes only fresh readings from the current phone session. No polling or wake locks. */
object LowBatteryAlerts {
    private const val CHANNEL = "phone_low_battery"
    private const val TAG = "phone-low-battery"

    fun disconnected(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(TAG, 3)
    }

    @Synchronized
    @android.annotation.SuppressLint("MissingPermission")
    fun receive(context: Context, peer: String, percent: Int?, charging: Boolean?) {
        val prefs = context.getSharedPreferences("battery_alerts", Context.MODE_PRIVATE)
        val key = "low.$peer"
        val manager = context.getSystemService(NotificationManager::class.java)
        when (LowBatteryPolicy.decide(percent, prefs.getBoolean(key, false), charging)) {
            LowBatteryPolicy.Decision.IGNORE -> Unit
            LowBatteryPolicy.Decision.REARM -> {
                prefs.edit().putBoolean(key, false).apply()
                manager.cancel(TAG, 3)
            }
            LowBatteryPolicy.Decision.ALERT -> {
                if (!BridgeGraph.notifier.canPost || !manager.areNotificationsEnabled()) return
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "iPhone low battery", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Warns when your iPhone battery reaches 10% or below"
                    enableVibration(true)
                })
                if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
                val open = PendingIntent.getActivity(context, 203, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.notify(TAG, 3, NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_bridge)
                    .setContentTitle("iPhone battery low")
                    .setContentText("Your iPhone battery is at $percent%. Charge your iPhone soon.")
                    .setCategory(NotificationCompat.CATEGORY_STATUS).setContentIntent(open)
                    .setAutoCancel(true).setLocalOnly(true).build())
                prefs.edit().putBoolean(key, true).apply()
            }
        }
    }
}
