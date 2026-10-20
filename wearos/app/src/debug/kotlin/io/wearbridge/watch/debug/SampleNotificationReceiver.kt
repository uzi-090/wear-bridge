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
 *
 * Extras: `--es title/text/app/name`, `--el uid`, `--es style FULL|SUBTLE`, `--ez quiet true` (no post),
 * `--ez cleared true` (then removed by "iOS", to test history), and `--ez demo true`: clears the watch
 * inbox and ignores real notifications until the app restarts (`am force-stop`), so screenshots only
 * ever show fake content.
 */
class SampleNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Debug-only native alert probe; never changes the real phone battery reading.
        if (intent.hasExtra("phone_battery")) {
            io.wearbridge.watch.battery.LowBatteryAlerts.receive(context, "qa-phone", intent.getIntExtra("phone_battery", -1), intent.getBooleanExtra("phone_charging", false))
            return
        }
        // Optional extra "style": BANNER / FULL / SUBTLE, to test pop-up styles from adb.
        intent.getStringExtra("style")?.let { name ->
            io.wearbridge.watch.settings.PopupStyle.entries.firstOrNull { it.name == name }
                ?.let(BridgeGraph.settings::setPopupStyle)
        }
        if (intent.getBooleanExtra("demo", false)) {
            BridgeGraph.demoMode = true
            BridgeGraph.deleteAll()
            io.wearbridge.watch.diagnostics.DiagLog.i("demo mode ON")
        }
        // `--ez find_phone true|false` rings/stops the iPhone; `--ez find_watch true|false` rings/stops this watch.
        if (intent.hasExtra("find_phone")) {
            BridgeGraph.findPhone(intent.getBooleanExtra("find_phone", false))
            return
        }
        if (intent.hasExtra("find_watch")) {
            if (intent.getBooleanExtra("find_watch", false)) BridgeGraph.findWatch.start() else BridgeGraph.findWatch.stop(fromUser = true)
            return
        }
        // `--es media VOLUME_UP` sends a real AMS command. `--ez fake_media true` shows fake Now Playing (demo mode), `--ez no_media true` clears it.
        intent.getStringExtra("media")?.let { name ->
            io.wearbridge.ams.RemoteCommand.entries.firstOrNull { it.name == name }?.let(BridgeGraph::mediaCommand)
            return
        }
        // `--ez fake_contacts true` (demo mode only): replaces the watch's contacts with fake ones. Restart to
        // leave demo mode; the iPhone then sees a generation mismatch and resyncs the real contacts.
        if (intent.getBooleanExtra("fake_contacts", false) && BridgeGraph.demoMode) {
            val fake = listOf(
                io.wearbridge.protocol.Contact("f1", "Alex Sample", listOf("+15550100001"), listOf("alex@example.com"), favorite = true),
                io.wearbridge.protocol.Contact("f2", "Blair Test", listOf("+15550100002", "+15550100003"), emptyList(), favorite = false),
                io.wearbridge.protocol.Contact("f3", "Casey Demo", listOf("+15550100004"), emptyList(), favorite = true),
                io.wearbridge.protocol.Contact("f4", "مثال تجريبي", listOf("+15550100005"), emptyList(), favorite = false),
                io.wearbridge.protocol.Contact("f5", "Drew Placeholder", emptyList(), listOf("drew@example.com"), favorite = false),
            )
            BridgeGraph.contacts.commit(fake.associateBy { it.id }, generation = io.wearbridge.watch.contacts.ContactsStore.DEMO_GENERATION)
            return
        }
        // Demo mode only: `--ez fake_call true` (ringing), `--ez fake_active true` (connected), `--ez fake_hangup true`.
        if (BridgeGraph.demoMode && (intent.hasExtra("fake_call") || intent.hasExtra("fake_active") || intent.hasExtra("fake_hangup"))) {
            val repo = BridgeGraph.repository
            fun call(uid: Long, cat: Category, pos: String?, neg: String?) = WatchNotification(
                AncsNotification(uid, cat, 0x1A, "com.apple.mobilephone", "Phone", intent.getStringExtra("title") ?: "Alex Sample",
                    intent.getStringExtra("text") ?: "mobile", "", null, LocalDateTime.now(), pos, neg),
                System.currentTimeMillis(),
            )
            when {
                intent.getBooleanExtra("fake_call", false) -> {
                    repo.upsert(call(990_001, Category.INCOMING_CALL, "Answer", "Decline"))
                    context.startActivity(
                        android.content.Intent(context, io.wearbridge.watch.ui.IncomingCallActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                intent.getBooleanExtra("fake_active", false) -> {
                    repo.remove(990_001)
                    repo.upsert(call(990_002, Category.ACTIVE_CALL, null, "End Call"))
                }
                else -> { repo.remove(990_001); repo.remove(990_002) }
            }
            return
        }
        if (intent.getBooleanExtra("clear_contacts", false)) {
            BridgeGraph.contacts.clear()
            return
        }
        // `--es fake_link reconnecting|bt_off|error` previews the offline UI (demo mode); `restore` puts the real state back.
        intent.getStringExtra("fake_link")?.takeIf { BridgeGraph.demoMode }?.let { which ->
            if (which == "restore") {
                realLink?.let { BridgeGraph.linkFlow.value = it }
                realLink = null
            } else {
                if (realLink == null) realLink = BridgeGraph.link.value
                BridgeGraph.linkFlow.value = when (which) {
                    "bt_off" -> io.wearbridge.watch.ble.LinkSnapshot(io.wearbridge.watch.ble.LinkState.DISCONNECTED, io.wearbridge.watch.ble.LinkIssue.BLUETOOTH_OFF)
                    "error" -> io.wearbridge.watch.ble.LinkSnapshot(io.wearbridge.watch.ble.LinkState.ERROR, io.wearbridge.watch.ble.LinkIssue.SETUP_FAILED)
                    else -> io.wearbridge.watch.ble.LinkSnapshot(io.wearbridge.watch.ble.LinkState.RECONNECTING)
                }
            }
            return
        }
        if (intent.getBooleanExtra("no_media", false) && BridgeGraph.demoMode) {
            BridgeGraph.mediaFlow.value = null
            return
        }
        if (intent.getBooleanExtra("fake_media", false) && BridgeGraph.demoMode) {
            BridgeGraph.mediaFlow.value = io.wearbridge.ams.NowPlaying(
                playerName = intent.getStringExtra("player") ?: "Music", state = io.wearbridge.ams.PlaybackState.PLAYING, rate = 1.0,
                elapsedSeconds = 74.0, elapsedAtMillis = android.os.SystemClock.elapsedRealtime(),
                title = "Sample Track Title", artist = "Demo Artist", durationSeconds = 215.0, volume = 0.6f,
            )
            return
        }
        if (intent.getBooleanExtra("style_only", false)) return
        val uid = intent.getLongExtra("uid", 900_000L + (System.currentTimeMillis() / 1000 % 1000))
        val sample = AncsNotification(
            uid = uid,
            category = Category.SOCIAL,
            flags = 0x10,
            appIdentifier = intent.getStringExtra("app") ?: "net.whatsapp.WhatsApp",
            appDisplayName = intent.getStringExtra("name") ?: "WhatsApp",
            title = intent.getStringExtra("title") ?: "Sample Sender",
            subtitle = "",
            message = intent.getStringExtra("text") ?: "This is a sample message to test how notifications appear.",
            messageSize = null,
            date = LocalDateTime.now().minusMinutes(intent.getLongExtra("ago", 0)),
            positiveActionLabel = null,
            negativeActionLabel = "Clear",
        )
        val n = WatchNotification(sample, System.currentTimeMillis())
        BridgeGraph.repository.upsert(n)
        if (!intent.getBooleanExtra("quiet", false)) BridgeGraph.notifier.show(n, alert = true)
        if (intent.getBooleanExtra("cleared", false)) {
            BridgeGraph.repository.remove(uid)
            BridgeGraph.notifier.cancel(uid)
        }
    }
}

/** The real link state while `fake_link` overrides it (receivers are created per broadcast, so it lives here). */
private var realLink: io.wearbridge.watch.ble.LinkSnapshot? = null
