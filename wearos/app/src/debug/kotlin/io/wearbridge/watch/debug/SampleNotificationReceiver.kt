package io.wearbridge.watch.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import io.wearbridge.ancs.AncsNotification
import io.wearbridge.ancs.Category
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.notifications.WatchNotification
import java.time.LocalDateTime

/**
 * Debug-only. `adb shell am broadcast -a io.wearbridge.watch.debug.SAMPLE -p io.wearbridge.watch`
 * posts a mirrored-looking WhatsApp notification with fake content through the real notifier,
 * so pop-up behaviour can be tested and screenshotted without real messages.
 */
class SampleNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uid = 900_000L + (System.currentTimeMillis() / 1000 % 1000)
        val sample = AncsNotification(
            uid = uid,
            category = Category.SOCIAL,
            flags = 0x10,
            appIdentifier = "net.whatsapp.WhatsApp",
            appDisplayName = "WhatsApp",
            title = intent.getStringExtra("title") ?: "Sample Sender",
            subtitle = "",
            message = intent.getStringExtra("text") ?: "This is a sample message to test how notifications appear.",
            messageSize = null,
            date = LocalDateTime.now(),
            positiveActionLabel = null,
            negativeActionLabel = "Clear",
        )
        val n = WatchNotification(sample, System.currentTimeMillis())
        val post = {
            BridgeGraph.repository.upsert(n)
            BridgeGraph.notifier.show(n, alert = true)
        }
        // Extra "wake_ms": switch the screen on first, then post after that many ms.
        val wakeMs = intent.getLongExtra("wake_ms", -1)
        if (wakeMs < 0) return post()
        val pm = context.getSystemService(PowerManager::class.java)
        Log.i("Bridge", "debug sample: interactive=${pm.isInteractive}, waking then posting in ${wakeMs}ms")
        @Suppress("DEPRECATION")
        val lock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "Bridge:sample-wake",
        )
        lock.acquire(3_000)
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            Log.i("Bridge", "debug sample: interactive=${pm.isInteractive} at post")
            post()
            pending.finish()
        }, wakeMs)
    }
}
