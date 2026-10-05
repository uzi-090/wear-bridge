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
import androidx.compose.ui.draw.alpha
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
            MaterialTheme {
                val ui by BridgeGraph.calls.ui.collectAsStateWithLifecycle()
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

private val Green = Color(0xFF34C759)
private val Red = Color(0xFFFF3B30)

@Composable
private fun RingingScreen(state: CallUi.Ringing) {
    val haptics = LocalHapticFeedback.current
    val a = state.call.ancs
    val bezelEnabled by BridgeGraph.settings.bezelToAnswer.collectAsStateWithLifecycle()
    val shakeEnabled by BridgeGraph.settings.shakeToDecline.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    // Bezel progress: +1..+3 toward Answer, -1..-3 toward Decline. Resets after a pause.
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
        BezelArc(clicks)
        // Sized for a ~214 dp round screen: everything stays inside the circle, and the three call
        // controls sit across its widest part.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            // The top line teaches the bezel gesture (it's the least obvious); otherwise it names the source.
            Text(
                if (bezelEnabled && a.positiveActionLabel != null) "Turn bezel to answer" else "iPhone call",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                state.caller.name + if (state.caller.contact?.favorite == true) " ★" else "",
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp),
            )
            if (state.caller.detail.isNotBlank()) {
                Text(
                    state.caller.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.66f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            // Decline – avatar – Answer, across the widest part of the circle.
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CallButton(R.drawable.ic_call_end, a.negativeActionLabel, Red, ::decline)
                Avatar(state.caller, ringing = !state.silenced, size = 50.dp)
                CallButton(R.drawable.ic_call, a.positiveActionLabel, Green, ::answer)
            }
            Spacer(Modifier.height(6.dp))
            if (state.silenced) {
                Text("Silenced on watch", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.height(36.dp).padding(top = 10.dp))
            } else {
                SilenceButton { BridgeGraph.calls.silence() }
            }
            if (shakeEnabled && a.negativeActionLabel != null) {
                Spacer(Modifier.semantics { contentDescription = "You can also shake your wrist to decline" })
            }
        }
    }
}

/** A round action button with iOS's label under it. Absent label = iOS doesn't offer it: no button. */
@Composable
private fun CallButton(icon: Int, label: String?, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(60.dp).padding(top = 5.dp)) {
        if (label == null) {
            Spacer(Modifier.size(50.dp))
            return@Column
        }
        FilledIconButton(
            onClick = onClick,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White),
            modifier = Modifier.size(50.dp),
        ) {
            Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(24.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun SilenceButton(onClick: () -> Unit) {
    androidx.wear.compose.material3.CompactButton(
        onClick = onClick,
        icon = { Icon(painterResource(R.drawable.ic_silence), contentDescription = null, modifier = Modifier.size(16.dp)) },
        label = { Text("Silence") },
        colors = androidx.wear.compose.material3.ButtonDefaults.filledTonalButtonColors(),
        modifier = Modifier.semantics { contentDescription = "Silence the watch. The iPhone keeps ringing." },
    )
}

/** Initials (or a phone glyph) on the contact's colour, with slow ripples while ringing. */
@Composable
private fun Avatar(caller: Caller, ringing: Boolean, size: Dp = 50.dp) {
    val reduceMotion = rememberReduceMotion()
    val t = rememberInfiniteTransition(label = "ripple")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1_800, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val color = avatarColor(caller)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size + 12.dp)) {
        if (ringing && !reduceMotion) {
            for (offset in listOf(0f, 0.5f)) {
                val p = (phase + offset) % 1f
                Box(
                    Modifier
                        .size(size)
                        .scale(1f + 0.24f * p)
                        .alpha(0.45f * (1f - p))
                        .background(color, CircleShape),
                )
            }
        }
        Box(Modifier.size(size).background(color, CircleShape), contentAlignment = Alignment.Center) {
            if (caller.initials.isNotEmpty()) {
                Text(caller.initials, style = MaterialTheme.typography.titleLarge, color = Color.White)
            } else {
                Icon(painterResource(R.drawable.ic_call), contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }
}

/** Edge arc that fills toward the action as the bezel turns: green on the right for Answer, red on the left for Decline. */
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
        // Centred on 3 o'clock (0°) for Answer, 9 o'clock (180°) for Decline.
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(start = 24.dp, end = 24.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(Green, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text("On iPhone", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(6.dp))
        Avatar(state.caller, ringing = false, size = 40.dp)
        Text(
            state.caller.name,
            style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            duration(now - state.startedAtMillis),
            style = MaterialTheme.typography.displaySmall,
            color = Green,
            modifier = Modifier.semantics { contentDescription = "Call time ${duration(now - state.startedAtMillis)}" },
        )
        Text("Audio on your iPhone", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.55f))
        Spacer(Modifier.height(8.dp))
        if (endLabel != null) {
            FilledIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    BridgeGraph.calls.end()
                },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Red, contentColor = Color.White),
                modifier = Modifier.size(width = 72.dp, height = 42.dp),
            ) {
                Icon(painterResource(R.drawable.ic_call_end), contentDescription = endLabel, modifier = Modifier.size(26.dp))
            }
            if (shakeToEnd) {
                Text("Or shake to end", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.42f), modifier = Modifier.padding(top = 4.dp))
            }
        } else {
            Text("End the call on your iPhone", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center)
        }
    }
}

/** Between Answer and the connected call: the caller stays put, so the hand-over feels continuous. */
@Composable
private fun AnsweringScreen(state: CallUi.Answering) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 24.dp),
    ) {
        Avatar(state.caller, ringing = false, size = 50.dp)
        Spacer(Modifier.height(4.dp))
        Text(state.caller.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Answering on iPhone…", style = MaterialTheme.typography.bodySmall, color = Green)
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
        Icon(painterResource(R.drawable.ic_call_end), contentDescription = null, tint = color, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = color, textAlign = TextAlign.Center)
        Text(state.caller.name, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        state.durationMillis?.let { Text(duration(it), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f)) }
    }
}

@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** The contact's colour, the same one the contacts screens use, so a person always looks the same. */
private fun avatarColor(caller: Caller): Color {
    val palette = listOf(
        Color(0xFF5B8DEF), Color(0xFF34A853), Color(0xFFE8710A), Color(0xFFA142F4), Color(0xFFD93025), Color(0xFF12B5CB), Color(0xFFE52592),
    )
    val key = caller.contact?.name ?: caller.name
    return if (caller.initials.isEmpty()) Color(0xFF3A3A3C) else palette[(key.hashCode() and 0x7FFFFFFF) % palette.size]
}

private fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private const val BEZEL_CLICKS = 3
private const val BEZEL_STEP_PX = 48f
private const val BEZEL_RESET_MS = 1_500L
