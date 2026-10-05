package io.wearbridge.watch.ui

import android.os.SystemClock
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.LevelIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Stepper
import androidx.wear.compose.material3.StepperLevelIndicator
import androidx.wear.compose.material3.Text
import io.wearbridge.ams.NowPlaying
import io.wearbridge.ams.RemoteCommand
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.R
import io.wearbridge.watch.ble.LinkState
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Now Playing on the iPhone, over Apple Media Service: works with any app iOS shows in its Now Playing
 * controls (Apple Music, Spotify, podcasts, YouTube…). Laid out like the Wear OS media player: track
 * info on top, transport controls in the middle with progress around play/pause, a volume button at the
 * bottom, and the bezel or crown changes the iPhone's volume with the level shown on the screen edge.
 */
@Composable
fun MediaScreen(onOpenVolume: () -> Unit) {
    val media by BridgeGraph.media.collectAsStateWithLifecycle()
    val link by BridgeGraph.link.collectAsStateWithLifecycle()
    val np = media

    ScreenScaffold {
        when {
            np == null && link.state != LinkState.READY -> EmptyMedia("Not connected", "Connect to your iPhone to control what it's playing.")
            np == null -> EmptyMedia("Media unavailable", "Your iPhone isn't sharing media controls right now.")
            !np.hasPlayer -> EmptyMedia("Nothing playing", "Start music or a podcast on your iPhone, then control it here.")
            else -> Player(np, onOpenVolume)
        }
    }
}

@Composable
private fun Player(np: NowPlaying, onOpenVolume: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val volume = rememberVolume(np)
    var volumeShownAt by remember { mutableLongStateOf(0L) }

    // Position only ticks while this screen is visible and playing: a UI timer, never a Bluetooth poll.
    val now by produceState(SystemClock.elapsedRealtime(), np.isPlaying, np.elapsedAtMillis) {
        value = SystemClock.elapsedRealtime()
        while (np.isPlaying) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }
    // While the bezel turns, the level shows on the screen edge, then fades away (the Wear OS pattern).
    val showLevel by produceState(false, volumeShownAt) {
        if (volumeShownAt == 0L) return@produceState
        value = true
        delay(LEVEL_VISIBLE_MS)
        value = false
    }

    fun send(command: RemoteCommand) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        BridgeGraph.mediaCommand(command)
    }

    Box(
        Modifier
            .fillMaxSize()
            .volumeRotary { up ->
                volume.step(up)
                volumeShownAt = SystemClock.elapsedRealtime()
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            // Top inset keeps the player name clear of the system clock on a round screen.
            modifier = Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 26.dp),
        ) {
            Text(
                np.playerName.ifBlank { "iPhone" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                np.title.ifBlank { "Unknown title" },
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (np.artist.isNotBlank()) {
                Text(
                    np.artist,
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MediaIcon(R.drawable.ic_skip_previous, "Previous", np.can(RemoteCommand.PREVIOUS_TRACK)) { send(RemoteCommand.PREVIOUS_TRACK) }
                PlayPause(np, now) { send(RemoteCommand.TOGGLE_PLAY_PAUSE) }
                MediaIcon(R.drawable.ic_skip_next, "Next", np.can(RemoteCommand.NEXT_TRACK)) { send(RemoteCommand.NEXT_TRACK) }
            }
            Spacer(Modifier.height(6.dp))
            IconButton(
                onClick = onOpenVolume,
                modifier = Modifier.size(44.dp).semantics { stateDescription = volume.percentText },
            ) {
                Icon(painterResource(R.drawable.ic_volume_up), contentDescription = "Volume", modifier = Modifier.size(22.dp))
            }
        }
        if (showLevel) {
            LevelIndicator(value = { volume.fraction }, modifier = Modifier.align(Alignment.CenterStart))
        }
    }
}

/** Play/pause with the track's progress as a ring around it, as Wear OS media players draw it. */
@Composable
private fun PlayPause(np: NowPlaying, now: Long, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(68.dp)) {
        if (np.durationSeconds > 0) {
            val fraction = (np.positionSeconds(now) / np.durationSeconds).toFloat()
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxSize().semantics { contentDescription = "Track progress" },
                strokeWidth = 3.dp,
            )
        }
        FilledIconButton(
            onClick = onClick,
            enabled = np.can(RemoteCommand.TOGGLE_PLAY_PAUSE) || np.can(RemoteCommand.PLAY) || np.can(RemoteCommand.PAUSE),
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                painterResource(if (np.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = if (np.isPlaying) "Pause" else "Play",
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

/**
 * The iPhone's volume, Wear OS style: a stepper (+ at the top, − at the bottom) with the level on the
 * screen edge, and the bezel or crown to change it. iOS moves its volume in 16 steps, and each AMS
 * command is exactly one step, so the stepper uses those steps.
 */
@Composable
fun VolumeScreen() {
    val media by BridgeGraph.media.collectAsStateWithLifecycle()
    val np = media
    val volume = rememberVolume(np)
    val enabled = np != null

    ScreenScaffold {
        Box(Modifier.fillMaxSize().volumeRotary(volume::step)) {
            Stepper(
                value = volume.level,
                onValueChange = { next -> if (next != volume.level) volume.step(next > volume.level) },
                valueProgression = 0..STEPS,
                decreaseIcon = { Icon(painterResource(R.drawable.ic_volume_down), contentDescription = "Volume down") },
                increaseIcon = { Icon(painterResource(R.drawable.ic_volume_up), contentDescription = "Volume up") },
                enabled = enabled,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("iPhone volume", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(volume.percentText, style = MaterialTheme.typography.displaySmall)
                    if (!enabled) Text("Not connected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            StepperLevelIndicator(value = { volume.level }, valueProgression = 0..STEPS)
        }
    }
}

/**
 * Volume as the UI shows it: optimistic on each step (so the bezel feels instant), then corrected by the
 * level iOS reports back over AMS.
 */
private class VolumeState(private val haptic: () -> Unit) {
    var level by mutableIntStateOf(0)
    var known by androidx.compose.runtime.mutableStateOf(false)
    /** iOS's exact level (it needn't sit on a step), or the optimistic one right after a step. */
    var fraction by mutableFloatStateOf(0f)

    val percentText: String get() = if (known) "${(fraction * 100).roundToInt()}%" else "–"

    fun step(up: Boolean) {
        val next = (level + if (up) 1 else -1).coerceIn(0, STEPS)
        if (known && next == level) return // already at the end: no command, no tick
        level = next
        fraction = next / STEPS.toFloat()
        haptic()
        BridgeGraph.mediaCommand(if (up) RemoteCommand.VOLUME_UP else RemoteCommand.VOLUME_DOWN)
    }
}

@Composable
private fun rememberVolume(np: NowPlaying?): VolumeState {
    val haptics = LocalHapticFeedback.current
    val state = remember { VolumeState { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) } }
    LaunchedEffect(np?.volume) {
        np?.volume?.let {
            state.level = (it * STEPS).roundToInt()
            state.fraction = it
            state.known = true
        }
    }
    return state
}

/**
 * Bezel and crown input. A Galaxy Watch4 Classic bezel click arrives as one ±136 px event (measured), so
 * one click is one volume step. Smaller crown events accumulate to a step.
 */
@Composable
private fun Modifier.volumeRotary(onStep: (up: Boolean) -> Unit): Modifier {
    val focus = remember { FocusRequester() }
    var accumulated by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    return this
        .onRotaryScrollEvent { e ->
            accumulated += e.verticalScrollPixels
            if (abs(accumulated) >= ROTARY_STEP_PX) {
                onStep(accumulated > 0)
                accumulated = 0f
            }
            true
        }
        .focusRequester(focus)
        .focusable()
}

/** iOS lists the commands the current player supports. An empty list means unknown: allow everything. */
private fun NowPlaying.can(c: RemoteCommand) = supported.isEmpty() || c in supported

@Composable
private fun MediaIcon(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun EmptyMedia(title: String, detail: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_music_note),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/** iOS's volume steps (each VolumeUp/VolumeDown command moves one). */
private const val STEPS = 16
private const val ROTARY_STEP_PX = 48f
private const val LEVEL_VISIBLE_MS = 2_000L
