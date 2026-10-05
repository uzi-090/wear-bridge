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
class MirrorNotifier(private val context: Context, private val settings: io.wearbridge.watch.settings.BridgeSettings) {

    private val manager = NotificationManagerCompat.from(context)

    /**
     * Mirrors currently posted, oldest first. Android drops everything past 50 notifications per app
     * (found on hardware: a reconnect replay filled the quota and an incoming call was rejected), so we
     * keep well below that and evict the oldest non-call first.
     */
    private val posted = LinkedHashSet<Long>()
    private val callUids = HashSet<Long>()

    @Synchronized
    fun isPosted(uid: Long): Boolean = uid in posted

    private val badges = HashMap<String, android.graphics.Bitmap>()

    /** The app's monogram badge (see [AppCatalog]). Rendered once per app and cached. */
    private fun badge(app: io.wearbridge.watch.notifications.AppInfo): android.graphics.Bitmap = synchronized(badges) {
        badges.getOrPut("${app.color}:${app.initial}") {
            val size = 96
            val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = app.color.toInt()
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            paint.color = android.graphics.Color.WHITE
            paint.textSize = size * 0.48f
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            paint.textAlign = android.graphics.Paint.Align.CENTER
            val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(app.initial, size / 2f, y, paint)
            bmp
        }
    }

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_ALERT, "iPhone notifications", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Notifications mirrored from your iPhone"
                    enableVibration(true)
                },
                NotificationChannel(CHANNEL_SUBTLE, "iPhone notifications (subtle)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Vibrates without a full-screen pop-up"
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
        val body = if (n.masked) MASKED_TEXT else listOf(a.subtitle, a.message).filter { it.isNotBlank() }.joinToString("\n")
        val title = if (n.masked) n.appLabel else a.title.ifBlank { n.appLabel }
        // The channel (importance) follows iOS's own Silent flag and stays stable across updates.
        // [alert] only decides whether this particular post buzzes.
        val channel = when {
            n.isIncomingCall || n.isActiveCall -> CHANNEL_CALLS
            a.flags and EventFlags.SILENT != 0 -> CHANNEL_QUIET
            settings.subtlePopups.value -> CHANNEL_SUBTLE
            else -> CHANNEL_ALERT
        }
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setLargeIcon(badge(n.app))
            .setContentTitle(title)
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
        a.positiveActionLabel?.let {
            // Answering a call opens the call screen (it becomes the in-call screen with "End Call").
            // An activity PendingIntent is allowed from a notification, whereas a broadcast can't start UI.
            val intent = if (n.isIncomingCall) callIntent(n.uid, ActionId.POSITIVE) else actionIntent(n.uid, ActionId.POSITIVE)
            builder.addAction(0, it, intent)
        }

        if (n.isIncomingCall) {
            builder.setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setFullScreenIntent(callIntent(), true)
        } else if (n.isActiveCall) {
            builder.setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(callIntent())
                .setPriority(NotificationCompat.PRIORITY_HIGH)
        } else {
            builder.setPriority(if (channel == CHANNEL_ALERT) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
        }
        synchronized(this) {
            posted.remove(n.uid)
            posted.add(n.uid)
            if (n.isIncomingCall) callUids.add(n.uid)
            while (posted.size > MAX_POSTED) {
                val victim = posted.firstOrNull { it !in callUids } ?: break
                posted.remove(victim)
                manager.cancel(TAG, victim.toInt())
            }
        }
        manager.notify(TAG, n.uid.toInt(), builder.build())
    }

    @Synchronized
    fun cancel(uid: Long) {
        posted.remove(uid)
        callUids.remove(uid)
        manager.cancel(TAG, uid.toInt())
    }

    /** Cancels mirrored notifications only. The foreground-service notification stays. */
    @Synchronized
    fun cancelAll() {
        posted.forEach { manager.cancel(TAG, it.toInt()) }
        posted.clear()
        callUids.clear()
    }

    /** Clears mirrors left behind by a previous process (they can't be acted on any more). */
    fun clearOrphans() {
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.tag == TAG }
            .forEach { manager.cancel(TAG, it.id) }
    }

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

    private fun callIntent(uid: Long? = null, action: ActionId? = null): PendingIntent {
        val intent = Intent(context, IncomingCallActivity::class.java)
        if (uid != null && action != null) {
            intent.putExtra(IncomingCallActivity.EXTRA_UID, uid).putExtra(IncomingCallActivity.EXTRA_ACTION, action.id)
        }
        val requestCode = if (uid == null) 1 else (uid.toInt() shl 2) or 2
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

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
        const val CHANNEL_SUBTLE = "mirror_subtle"
        const val CHANNEL_CALLS = "calls"
        const val CHANNEL_SERVICE = "service"
        const val SERVICE_NOTIFICATION_ID = 1
        private const val MAX_POSTED = 24
        const val MASKED_TEXT = "Content hidden · tap to view"
        private const val TAG = "ios"
        private const val GROUP_PREFIX = "ios:"
    }
}
