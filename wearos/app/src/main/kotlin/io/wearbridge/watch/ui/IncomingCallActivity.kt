package io.wearbridge.watch.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.wearbridge.ancs.ActionId
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.R
import io.wearbridge.watch.calls.CallUi
import io.wearbridge.watch.calls.Caller
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * The iPhone call surface (call CONTROL only: audio stays wherever the iPhone routes it).
 *
 * Ringing, laid out for a round screen: the source on top, then the caller, then Decline – avatar –
 * Answer across the widest part of the circle, and Silence at the bottom. The bezel answers (3 clicks
 * clockwise) or declines (3 anticlockwise) with a filling edge arc; a deliberate shake declines; Back or
 * a swipe silences the watch. Every action button carries iOS's own label. Connected: caller, live timer
 * and iOS's End Call. Afterwards: a short summary, then the screen closes by itself.
 */
class IncomingCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (savedInstanceState == null) performRequestedAction(intent)

        // Back (the button or a swipe) while ringing silences the watch, like a phone's volume key.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (BridgeGraph.calls.ui.value is CallUi.Ringing) BridgeGraph.calls.silence()
                finish()
            }
        })

        setContent {
            io.wearbridge.watch.ui.theme.BridgeTheme {
                val pairing by BridgeGraph.pairing.state.collectAsStateWithLifecycle()
                val link by BridgeGraph.link.collectAsStateWithLifecycle()
                val currentUi by BridgeGraph.calls.ui.collectAsStateWithLifecycle()
                val available = pairing.canAccess(link)
                val ui = if (available) currentUi else CallUi.None
                LaunchedEffect(available) { if (!available) finish() }
                LaunchedEffect(ui) {
                    // Nothing to show: wait briefly (answering removes the ringing call just before iOS
                    // posts the connected one), then close.
                    if (ui is CallUi.None) {
                        delay(1_200)
                        if (BridgeGraph.calls.ui.value is CallUi.None) finish()
                    }
                }
                AnimatedContent(
                    targetState = ui,
                    contentKey = { it::class },
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                    label = "call",
                ) { state ->
                    when (state) {
                        is CallUi.Ringing -> RingingScreen(state)
                        is CallUi.Answering -> AnsweringScreen(state)
                        is CallUi.Active -> ActiveScreen(state)
                        is CallUi.PhoneHandled -> PhoneHandledScreen(state)
                        is CallUi.Ended -> EndedScreen(state)
                        CallUi.None -> Box(Modifier.fillMaxSize().background(Color.Black))
                    }
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
            val ringing = BridgeGraph.calls.ui.value as? CallUi.Ringing
            // Through the controller when it's the ringing call, so the screen shows "Answering on iPhone…".
            if (action == ActionId.POSITIVE && ringing?.call?.uid == uid) BridgeGraph.calls.answer() else BridgeGraph.performAction(uid, action)
            intent?.removeExtra(EXTRA_UID)
        }
    }

    companion object {
        const val EXTRA_UID = "uid"
        const val EXTRA_ACTION = "action"
    }
}

private val Green = io.wearbridge.watch.ui.theme.BridgePalette.CallGreen
private val Red = io.wearbridge.watch.ui.theme.BridgePalette.CallRed

/**
 * Ringing. Answer and Decline sit at 3 and 9 o'clock (the widest part of the circle, beside the bezel
 * arcs that fill as you turn it); the caller is in the middle; Silence is the edge button.
 */
@Composable
private fun RingingScreen(state: CallUi.Ringing) {
    val haptics = LocalHapticFeedback.current
    val a = state.call.ancs
    val bezelEnabled by BridgeGraph.settings.bezelToAnswer.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    var clicks by remember { mutableIntStateOf(0) }
    var accumulated by remember { mutableFloatStateOf(0f) }
    var lastTurnAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lastTurnAt) {
        if (lastTurnAt == 0L) return@LaunchedEffect
        delay(BEZEL_RESET_MS)
        clicks = 0
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun answer() {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        BridgeGraph.calls.answer()
    }

    fun decline() {
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        BridgeGraph.calls.decline()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent { e ->
                if (!bezelEnabled) return@onRotaryScrollEvent false
                accumulated += e.verticalScrollPixels
                if (abs(accumulated) >= BEZEL_STEP_PX) {
                    val dir = if (accumulated > 0) 1 else -1
                    accumulated = 0f
                    // Turning back the other way cancels progress rather than counting toward the other action.
                    clicks = if (clicks != 0 && dir != Integer.signum(clicks)) 0 else clicks + dir
                    lastTurnAt = SystemClock.elapsedRealtime()
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    when {
                        clicks >= BEZEL_CLICKS && a.positiveActionLabel != null -> { clicks = 0; answer() }
                        clicks <= -BEZEL_CLICKS && a.negativeActionLabel != null -> { clicks = 0; decline() }
                    }
                }
                true
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        CallerGlow(state.caller)
        BezelArc(clicks)

        // Top: Silence (watch only; the iPhone keeps ringing).
        androidx.wear.compose.material3.CompactButton(
            onClick = { BridgeGraph.calls.silence() },
            enabled = !state.silenced,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF2A2F35), contentColor = Color.White),
            icon = if (state.silenced) null else {
                { Icon(painterResource(R.drawable.ic_silence), contentDescription = null, modifier = Modifier.size(16.dp)) }
            },
            label = { Text(if (state.silenced) "Silenced" else "Silence", maxLines = 1) },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp)
                .semantics { contentDescription = "Silence the watch. The iPhone keeps ringing." },
        )

        // Centre: who's calling. The name is the hero; the number is small beneath it.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center).offset(y = (-14).dp).padding(horizontal = 22.dp),
        ) {
            Text("Incoming call", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.55f))
            Text(
                state.caller.name + if (state.caller.contact?.favorite == true) " ★" else "",
                style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (state.caller.detail.isNotBlank()) {
                Text(state.caller.detail, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
            }
        }

        // Bottom half: Decline · Answer, side by side where the circle still has room for both.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(30.dp),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        ) {
            SideAction(R.drawable.ic_call_end, a.negativeActionLabel, Red, breathe = false, onClick = ::decline)
            SideAction(R.drawable.ic_call, a.positiveActionLabel, Green, breathe = !state.silenced, onClick = ::answer)
        }
    }
}

/**
 * A big round call action. iOS's own label is its TalkBack label (icon and colour say it visually).
 * Absent label = iOS doesn't offer it: no button. [breathe] gives Answer a slow scale pulse (draw phase only).
 */
@Composable
private fun SideAction(icon: Int, label: String?, color: Color, breathe: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(58.dp)) {
        if (label == null) {
            Spacer(Modifier.size(56.dp))
            return@Column
        }
        val reduceMotion = rememberReduceMotion()
        val scale = if (breathe && !reduceMotion) {
            rememberInfiniteTransition(label = "breathe").animateFloat(1f, 1.07f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s")
        } else null
        FilledIconButton(
            onClick = onClick,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White),
            modifier = Modifier.size(56.dp).graphicsLayer {
                val s = scale?.value ?: 1f
                scaleX = s
                scaleY = s
            },
        ) {
            Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(26.dp))
        }
    }
}

/** A soft wash of the caller's pastel colour behind everything (subtle, so text stays crisp). */
@Composable
private fun CallerGlow(caller: Caller) {
    val key = caller.contact?.name ?: caller.name
    val color = if (caller.initials.isEmpty()) Color(0xFF3A3F46) else io.wearbridge.watch.ui.theme.Avatars.colors(key).first
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(color.copy(alpha = 0.20f), Color.Transparent),
                center = Offset(size.width / 2, size.height * 0.42f),
                radius = size.minDimension * 0.55f,
            ),
        )
    }
}

/** Edge arc that fills toward the action as the bezel turns: green at 3 o'clock for Answer, red at 9 for Decline. */
@Composable
private fun BezelArc(clicks: Int) {
    if (clicks == 0) return
    val fraction = abs(clicks) / BEZEL_CLICKS.toFloat()
    val answer = clicks > 0
    Canvas(Modifier.fillMaxSize().padding(3.dp)) {
        val stroke = 6.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val sweep = 100f * fraction
        val start = if (answer) -sweep / 2 else 180f - sweep / 2
        drawArc(
            color = if (answer) Green else Red,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** Connected: who, a big timer, where the audio is, and End as the edge button. */
@Composable
private fun ActiveScreen(state: CallUi.Active) {
    val haptics = LocalHapticFeedback.current
    val endLabel = state.call.ancs.negativeActionLabel
    val shakeToEnd by BridgeGraph.settings.shakeToEnd.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            // Leaves the bottom curve to the End edge button.
            modifier = Modifier.fillMaxSize().padding(start = 26.dp, end = 26.dp, top = 6.dp, bottom = if (endLabel != null) 62.dp else 0.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(Green, CircleShape))
                Spacer(Modifier.width(5.dp))
                Text("On iPhone", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                state.caller.name,
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                duration(now - state.startedAtMillis),
                style = MaterialTheme.typography.numeralSmall,
                color = Color.White,
                modifier = Modifier.semantics { contentDescription = "Call time ${duration(now - state.startedAtMillis)}" },
            )
            Text(
                if (endLabel != null && shakeToEnd) "Audio on iPhone · shake to end" else "Audio on your iPhone",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
            )
            if (endLabel == null) {
                Text("End the call on your iPhone", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (endLabel != null) {
            EdgeButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    BridgeGraph.calls.end()
                },
                buttonSize = EdgeButtonSize.Small,
                colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Icon(painterResource(R.drawable.ic_call_end), contentDescription = endLabel, modifier = Modifier.size(28.dp))
            }
        }
    }
}

/** Between Answer and the connected call: the caller stays put, with a spinning ring, so it feels continuous. */
@Composable
private fun AnsweringScreen(state: CallUi.Answering) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 24.dp),
    ) {
        androidx.wear.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(36.dp),
            colors = androidx.wear.compose.material3.ProgressIndicatorDefaults.colors(indicatorColor = Green, trackColor = Color.Transparent),
            strokeWidth = 3.dp,
        )
        Spacer(Modifier.height(10.dp))
        Text(state.caller.name, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text("Answering on iPhone…", style = MaterialTheme.typography.labelMedium, color = Green)
    }
}

@Composable
private fun PhoneHandledScreen(state: CallUi.PhoneHandled) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 28.dp),
    ) {
        Icon(painterResource(R.drawable.ic_phone_iphone), contentDescription = null,
            tint = if (state.answered) Green else Color.White, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text(if (state.answered) "Answered on iPhone" else "Call handled on iPhone",
            style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center)
    }
}

@Composable
private fun EndedScreen(state: CallUi.Ended) {
    val (title, color) = when (state.outcome) {
        CallUi.Outcome.ENDED -> "Call ended" to Color.White
        CallUi.Outcome.DECLINED -> "Declined" to Red
        CallUi.Outcome.DECLINED_BY_SHAKE -> "Declined by shake" to Red
        CallUi.Outcome.ANSWERING -> "Answered" to Green
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 24.dp),
    ) {
        Box(Modifier.size(52.dp).background(color.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_call_end), contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = color, textAlign = TextAlign.Center)
        Text(state.caller.name, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        state.durationMillis?.let { Text(duration(it), style = MaterialTheme.typography.numeralExtraSmall, color = Color.White.copy(alpha = 0.85f)) }
    }
}

@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

private fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private const val BEZEL_CLICKS = 3
private const val BEZEL_STEP_PX = 48f
private const val BEZEL_RESET_MS = 1_500L
