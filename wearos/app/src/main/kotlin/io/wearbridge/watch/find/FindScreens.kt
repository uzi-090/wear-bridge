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
import io.wearbridge.watch.ui.theme.BridgePalette
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.alpha
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon

enum class FindPhoneState { IDLE, RINGING, FOUND, NO_APP, NO_LINK }

/** Watch → iPhone: ring the iPhone through the Bridge iPhone app (best effort, and the UI says so). */
@Composable
fun FindPhoneScreen() {
    val state by BridgeGraph.findPhone.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val ringing = state == FindPhoneState.RINGING

    ScreenScaffold {
        Box(Modifier.fillMaxSize()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                // Content sits in the space above the edge button (Medium ≈ 70 dp plus its margin).
                modifier = Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 84.dp),
            ) {
                Pulse(active = ringing)
                Spacer(Modifier.height(6.dp))
                Text(
                    when (state) {
                        FindPhoneState.RINGING -> "Ringing…"
                        FindPhoneState.FOUND -> "Found it"
                        FindPhoneState.NO_APP -> "Can't reach iPhone"
                        FindPhoneState.NO_LINK -> "Not connected"
                        FindPhoneState.IDLE -> "Find iPhone"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = when (state) {
                        FindPhoneState.FOUND -> BridgePalette.Connected
                        FindPhoneState.NO_APP, FindPhoneState.NO_LINK -> BridgePalette.Attention
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.Center,
                )
                // Idle needs no explanation ("Ring iPhone" says it); other states do.
                val detail = when (state) {
                    FindPhoneState.RINGING -> "Your iPhone is playing a sound."
                    FindPhoneState.NO_APP -> "Open Bridge on your iPhone once, then try again."
                    FindPhoneState.NO_LINK -> "Your iPhone needs to be connected."
                    else -> null
                }
                if (detail != null) Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = BridgePalette.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // The one action, hugging the bottom curve.
            EdgeButton(
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); BridgeGraph.findPhone(!ringing) },
                buttonSize = EdgeButtonSize.Medium,
                modifier = Modifier.align(Alignment.BottomCenter),
                colors = if (ringing) {
                    ButtonDefaults.buttonColors(containerColor = BridgePalette.CallRed, contentColor = Color.White)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) { Text(if (ringing) "Stop" else "Ring iPhone") }
        }
    }
}

/** The phone in a soft disc, with ripples while it rings. Respects the system's animation setting. */
@Composable
private fun Pulse(active: Boolean) {
    val context = LocalContext.current
    val reduceMotion = remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val phase = rememberInfiniteTransition(label = "find").animateFloat(0f, 1f, infiniteRepeatable(tween(1_600)), label = "p").value
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(84.dp)) {
        if (active && !reduceMotion) {
            for (offset in listOf(0f, 0.5f)) {
                val p = (phase + offset) % 1f
                Box(Modifier.size(58.dp).scale(1f + 0.4f * p).alpha(0.5f * (1f - p)).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
        }
        Box(
            Modifier.size(58.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(io.wearbridge.watch.R.drawable.ic_phone_iphone),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(30.dp),
            )
        }
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
            io.wearbridge.watch.ui.theme.BridgeTheme {
                val ringing by BridgeGraph.findWatch.ringing.collectAsStateWithLifecycle()
                LaunchedEffect(ringing) { if (!ringing) finish() }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                ) {
                    Pulse(active = true)
                    Spacer(Modifier.height(10.dp))
                    Text("Your iPhone is looking for this watch", style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
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
