package io.wearbridge.watch.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.wearbridge.ancs.ActionId
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.notifications.WatchNotification
import kotlinx.coroutines.delay

/**
 * The iPhone call surface: ringing (iOS's Answer/Decline), then in progress (iOS's "End Call",
 * from the undocumented active-call category). Every button is an action iOS exposes, with iOS's
 * own label. This is call CONTROL only: audio stays wherever the iPhone routes it.
 */
class IncomingCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (savedInstanceState == null) performRequestedAction(intent)

        setContent {
            MaterialTheme {
                val repo = BridgeGraph.repository
                val incoming by repo.incomingCall.collectAsStateWithLifecycle()
                val active by repo.activeCall.collectAsStateWithLifecycle()
                var acted by remember { mutableStateOf(false) }
                val call = incoming ?: active

                LaunchedEffect(call == null) {
                    if (call == null) {
                        // Wait briefly: answering removes the ringing call just before iOS posts the active one.
                        delay(if (acted) 1_500 else 1_200)
                        if (repo.incomingCall.value == null && repo.activeCall.value == null) finish()
                    }
                }
                when {
                    call != null -> CallScreen(call, inProgress = incoming == null, onActed = { acted = true })
                    acted -> Status("Connecting on iPhone…")
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        performRequestedAction(intent)
    }

    /** "Answer" from the notification opens this screen with the action attached. */
    private fun performRequestedAction(intent: Intent?) {
        val uid = intent?.getLongExtra(EXTRA_UID, -1) ?: -1
        val action = ActionId.entries.firstOrNull { it.id == intent?.getIntExtra(EXTRA_ACTION, -1) }
        if (uid >= 0 && action != null) {
            BridgeGraph.performAction(uid, action)
            intent?.removeExtra(EXTRA_UID)
        }
    }

    companion object {
        const val EXTRA_UID = "uid"
        const val EXTRA_ACTION = "action"
    }
}

@Composable
private fun CallScreen(call: WatchNotification, inProgress: Boolean, onActed: () -> Unit) {
    val status by BridgeGraph.repository.actionStatus.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val a = call.ancs

    fun act(action: ActionId) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onActed()
        BridgeGraph.performAction(call.uid, action)
    }

    Box(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (inProgress) "On iPhone call" else "iPhone · ${call.appLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                a.title.ifBlank { if (inProgress) "Call in progress" else "Incoming call" },
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (a.subtitle.isNotBlank()) {
                Text(a.subtitle, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, maxLines = 1)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                a.negativeActionLabel?.let { label ->
                    FilledTonalButton(
                        onClick = { act(ActionId.NEGATIVE) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        label = { Text(label, maxLines = 1) },
                    )
                }
                a.positiveActionLabel?.let { label ->
                    Button(onClick = { act(ActionId.POSITIVE) }, modifier = Modifier.weight(1f), label = { Text(label, maxLines = 1) })
                }
            }
            val s = status?.takeIf { it.uid == call.uid }
            Text(
                when {
                    s != null -> actionStatusText(s)
                    inProgress -> "Audio is on your iPhone"
                    !call.hasActions -> "Answer on your iPhone"
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Status(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}
