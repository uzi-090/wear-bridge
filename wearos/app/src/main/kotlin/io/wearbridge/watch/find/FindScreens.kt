package io.wearbridge.watch.find

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import io.wearbridge.watch.BridgeGraph

enum class FindPhoneState { IDLE, RINGING, FOUND, NO_APP, NO_LINK }

/** Watch → iPhone: ring the iPhone through the Bridge iPhone app (best effort, and the UI says so). */
@Composable
fun FindPhoneScreen() {
    val state by BridgeGraph.findPhone.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val ringing = state == FindPhoneState.RINGING

    ScreenScaffold {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
        ) {
            Pulse(active = ringing)
            Spacer(Modifier.height(10.dp))
            Text(
                when (state) {
                    FindPhoneState.RINGING -> "Ringing your iPhone…"
                    FindPhoneState.FOUND -> "Found it"
                    FindPhoneState.NO_APP -> "Can't reach the iPhone app"
                    FindPhoneState.NO_LINK -> "Not connected"
                    FindPhoneState.IDLE -> "Find iPhone"
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                when (state) {
                    FindPhoneState.RINGING -> "Plays a sound on your iPhone, unless it's on silent."
                    FindPhoneState.NO_APP -> "Open Bridge on your iPhone once, then try again. iOS stops it after you swipe it away."
                    FindPhoneState.NO_LINK -> "Your iPhone needs to be connected to ring."
                    else -> "Plays a sound on your iPhone."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            if (ringing) {
                FilledTonalButton(
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); BridgeGraph.findPhone(false) },
                    label = { Text("Stop") },
                )
            } else {
                Button(
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); BridgeGraph.findPhone(true) },
                    label = { Text("Ring") },
                )
            }
        }
    }
}

/** Expanding ring while active; static otherwise. Respects the system's animation setting. */
@Composable
private fun Pulse(active: Boolean) {
    val context = LocalContext.current
    val reduceMotion = remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val scale = if (active && !reduceMotion) {
        rememberInfiniteTransition(label = "find").animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "s").value
    } else 1f
    Box(
        Modifier.size(44.dp).scale(scale).border(3.dp, MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("📱", style = MaterialTheme.typography.titleMedium)
    }
}

/** Shown while the iPhone is ringing the watch: one big "Found it". */
class FindWatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                val ringing by BridgeGraph.findWatch.ringing.collectAsStateWithLifecycle()
                LaunchedEffect(ringing) { if (!ringing) finish() }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                ) {
                    Pulse(active = true)
                    Spacer(Modifier.height(10.dp))
                    Text("Your iPhone is looking for this watch", style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { BridgeGraph.findWatch.stop(fromUser = true) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(),
                        label = { Text("Found it") },
                    )
                }
            }
        }
    }
}
