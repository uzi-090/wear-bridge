package io.wearbridge.watch.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        // Optional extra "style": BANNER / FULL / SUBTLE, to test pop-up styles from adb.
        intent.getStringExtra("style")?.let { name ->
            io.wearbridge.watch.settings.PopupStyle.entries.firstOrNull { it.name == name }
                ?.let(BridgeGraph.settings::setPopupStyle)
        }
        if (intent.getBooleanExtra("style_only", false)) return
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
        BridgeGraph.repository.upsert(n)
        BridgeGraph.notifier.show(n, alert = true)
    }
}
