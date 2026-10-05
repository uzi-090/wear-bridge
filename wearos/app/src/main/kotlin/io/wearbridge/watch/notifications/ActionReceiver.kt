package io.wearbridge.watch.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.wearbridge.ancs.ActionId
import io.wearbridge.watch.BridgeGraph

/** Receives notification-action taps (not exported) and forwards them to the iPhone via ANCS. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_SILENCE, false)) return BridgeGraph.calls.silence()
        val uid = intent.getLongExtra(EXTRA_UID, -1)
        val action = ActionId.entries.firstOrNull { it.id == intent.getIntExtra(EXTRA_ACTION, -1) } ?: return
        if (uid < 0) return
        BridgeGraph.performAction(uid, action)
    }

    companion object {
        const val EXTRA_UID = "uid"
        const val EXTRA_ACTION = "action"
        /** Watch-only: stop the ring vibration (iOS doesn't let accessories silence the iPhone). */
        const val EXTRA_SILENCE = "silence"
    }
}
