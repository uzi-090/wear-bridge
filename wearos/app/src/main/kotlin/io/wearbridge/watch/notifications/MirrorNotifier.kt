package io.wearbridge.watch.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.wearbridge.ancs.ActionId
import io.wearbridge.ancs.EventFlags
import io.wearbridge.watch.R
import io.wearbridge.watch.ui.IncomingCallActivity
import io.wearbridge.watch.ui.MainActivity

/**
 * Posts mirrored iPhone notifications as native Wear OS notifications, so they get the system's heads-up,
 * stacking and swipe-to-dismiss behaviour. Action buttons use the labels iOS supplied, verbatim.
 */
class MirrorNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_ALERT, "iPhone notifications", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Notifications mirrored from your iPhone"
                    enableVibration(true)
                },
                NotificationChannel(CHANNEL_QUIET, "Silent iPhone notifications", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Mirrored without sound or vibration"
                },
                NotificationChannel(CHANNEL_CALLS, "iPhone calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Incoming calls on your iPhone"
                    enableVibration(true)
                },
                NotificationChannel(CHANNEL_SERVICE, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Keeps the iPhone connection running"
                    setShowBadge(false)
                },
            ),
        )
    }

    val canPost: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    val canUseFullScreen: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    @android.annotation.SuppressLint("MissingPermission") // checked via canPost
    fun show(n: WatchNotification, alert: Boolean) {
        if (!canPost) return
        val a = n.ancs
        val body = listOf(a.subtitle, a.message).filter { it.isNotBlank() }.joinToString("\n")
        // The channel (importance) follows iOS's own Silent flag and stays stable across updates.
        // [alert] only decides whether this particular post buzzes.
        val channel = when {
            n.isIncomingCall -> CHANNEL_CALLS
            a.flags and EventFlags.SILENT != 0 -> CHANNEL_QUIET
            else -> CHANNEL_ALERT
        }
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(a.title.ifBlank { n.appLabel })
            .setContentText(body)
            .setSubText(n.appLabel)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setWhen(n.timeMillis)
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .setSilent(!alert)
            .setGroup(GROUP_PREFIX + (a.appIdentifier ?: "unknown"))
            .setContentIntent(openIntent(n.uid))
            .setLocalOnly(true)

        a.negativeActionLabel?.let { builder.addAction(0, it, actionIntent(n.uid, ActionId.NEGATIVE)) }
        a.positiveActionLabel?.let { builder.addAction(0, it, actionIntent(n.uid, ActionId.POSITIVE)) }

        if (n.isIncomingCall) {
            builder.setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setFullScreenIntent(callIntent(), true)
        } else {
            builder.setPriority(if (channel == CHANNEL_ALERT) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
        }
        manager.notify(TAG, n.uid.toInt(), builder.build())
    }

    fun cancel(uid: Long) = manager.cancel(TAG, uid.toInt())

    fun cancelAll() = manager.cancelAll()

    fun serviceNotification(statusText: String) =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle("Bridge")
            .setContentText(statusText)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent(null))
            .build()

    private fun openIntent(uid: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java) // singleTask: reuses the existing task
        if (uid != null) intent.putExtra(MainActivity.EXTRA_UID, uid)
        return PendingIntent.getActivity(context, uid?.toInt() ?: 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun callIntent(): PendingIntent = PendingIntent.getActivity(
        context, 1,
        Intent(context, IncomingCallActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(uid: Long, action: ActionId): PendingIntent = PendingIntent.getBroadcast(
        context,
        (uid.toInt() shl 1) or action.id,
        Intent(context, ActionReceiver::class.java)
            .putExtra(ActionReceiver.EXTRA_UID, uid)
            .putExtra(ActionReceiver.EXTRA_ACTION, action.id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ALERT = "mirror_alert"
        const val CHANNEL_QUIET = "mirror_quiet"
        const val CHANNEL_CALLS = "calls"
        const val CHANNEL_SERVICE = "service"
        const val SERVICE_NOTIFICATION_ID = 1
        private const val TAG = "ios"
        private const val GROUP_PREFIX = "ios:"
    }
}
