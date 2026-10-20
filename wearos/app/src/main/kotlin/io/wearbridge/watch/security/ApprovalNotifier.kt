package io.wearbridge.watch.security

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.R
import io.wearbridge.watch.ui.MainActivity

/**
 * "Allow Bridge on iPhone?" with the 6-digit code. Shown as a notification (so it appears even when Bridge
 * isn't open) and as a card on the watch home screen. Allow/Deny go to [SecureLink.approve].
 */
class ApprovalNotifier(private val context: Context) : SecureLink.ApprovalPrompt {

    fun createChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "iPhone app pairing", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks you to allow the Bridge app on your iPhone"
            },
        )
    }

    @SuppressLint("MissingPermission") // canPost is checked
    override fun show(code: String) {
        BridgeGraph.pendingApprovalFlow.value = code
        if (!BridgeGraph.notifier.canPost) return
        val open = PendingIntent.getActivity(
            context, 50, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val displayedCode = code.chunked(3).joinToString(" ")
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle("Allow Bridge on iPhone?")
            .setContentText("Check that your iPhone shows $displayedCode")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Check that your iPhone shows $displayedCode, then allow it."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Deny", answer(false))
            .addAction(0, "Allow", answer(true))
            .setLocalOnly(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
    }

    override fun dismiss() {
        BridgeGraph.pendingApprovalFlow.value = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun answer(allow: Boolean) = PendingIntent.getBroadcast(
        context, if (allow) 51 else 52,
        Intent(context, ApprovalReceiver::class.java).putExtra(EXTRA_ALLOW, allow),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL = "iphone_app_pairing"
        const val EXTRA_ALLOW = "allow"
        private const val NOTIFICATION_ID = 50
    }
}

/** Allow / Deny from the notification. Not exported, so only our own PendingIntents reach it. */
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BridgeGraph.approveIphoneApp(intent.getBooleanExtra(ApprovalNotifier.EXTRA_ALLOW, false))
    }
}
