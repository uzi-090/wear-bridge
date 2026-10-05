package io.wearbridge.watch.ui

import android.os.SystemClock
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.LevelIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import io.wearbridge.ams.NowPlaying
import io.wearbridge.ams.RemoteCommand
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.R
import io.wearbridge.watch.ble.LinkState
import io.wearbridge.watch.notifications.AppCatalog
import io.wearbridge.watch.notifications.AppInfo
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Now Playing on the iPhone, over Apple Media Service: any app iOS shows in its Now Playing controls
 * (Apple Music, Spotify, podcasts, YouTube…).
 *
 * Layout for a round screen, top to bottom on one centre line: the playing app's logo and name, the
 * title (scrolls if long) and artist, then previous / play-pause / next with the track progress as a
 * ring around play-pause, then elapsed and total time. The palette comes from the playing app's brand
 * colour: a faint glow behind the content (mostly black, so OLED-friendly), and the accent for the ring
 * and play button. Volume is the bezel (or crown) only: one click = one iOS volume step, with the level
 * on the screen edge and in words at the bottom while it changes.
 */
@Composable
fun MediaScreen() {
    val media by BridgeGraph.media.collectAsStateWithLifecycle()
    val link by BridgeGraph.link.collectAsStateWithLifecycle()
    val np = media
    val seen by BridgeGraph.policy.seenApps.collectAsStateWithLifecycle()
    val app = remember(np?.playerName, seen) { np?.takeIf { it.hasPlayer }?.let { AppCatalog.forPlayerName(it.playerName, seen) } }

    ScreenScaffold {
        when {
            np == null && link.state != LinkState.READY -> EmptyMedia("Not connected", "Connect to your iPhone to control what it's playing.")
            np == null -> EmptyMedia("Media unavailable", "Your iPhone isn't sharing media controls right now.")
            !np.hasPlayer || app == null -> EmptyMedia("Nothing playing", "Start music or a podcast on your iPhone, then control it here.")
            else -> Player(np, app)
        }
    }
}

@Composable
private fun Player(np: NowPlaying, app: AppInfo) {
    val haptics = LocalHapticFeedback.current
    // Only a known app has a real brand colour; otherwise stay neutral rather than inventing one.
    val known = app.bundleId != null || app.logo != null
    val palette = remember(app.color, known) { if (known) MediaPalette.from(Color(app.color)) else MediaPalette.NEUTRAL }
    val volume = rememberVolume(np)
    var volumeChangedAt by remember { mutableLongStateOf(0L) }
    val reduceMotion = rememberReduceMotion()

    // Position only ticks while this screen is visible and playing: a UI timer, never a Bluetooth poll.
    val now by produceState(SystemClock.elapsedRealtime(), np.isPlaying, np.elapsedAtMillis) {
        value = SystemClock.elapsedRealtime()
        while (np.isPlaying) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }
    // While the bezel turns, the level shows on the edge and at the bottom, then fades back to the times.
    val showVolume by produceState(false, volumeChangedAt) {
        if (volumeChangedAt == 0L) return@produceState
        value = true
        delay(VOLUME_VISIBLE_MS)
        value = false
    }

    fun send(command: RemoteCommand) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        BridgeGraph.mediaCommand(command)
    }

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                // A soft wash of the app's colour from the top edge, fading to pure black (OLED) by mid-screen.
                if (palette.glow != Color.Transparent) {
                    drawRect(Brush.radialGradient(listOf(palette.glow, Color.Transparent), center = Offset(size.width / 2, 0f), radius = size.height * 0.75f))
                }
            }
            .volumeRotary { up ->
                volume.step(up)
                volumeChangedAt = SystemClock.elapsedRealtime()
            },
    ) {
        // The top region gets a little more room than the bottom (it holds the app, title and artist under
        // the clock), which puts the controls just below the centre line, where the thumb rests.
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier.weight(1.22f).fillMaxWidth().padding(start = 34.dp, end = 34.dp, bottom = 6.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // The playing app, at the top: its official icon when the iPhone has sent one.
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 3.dp)) {
                        if (app.bundleId != null || app.logo != null) {
                            AppBadge(app, size = 15.dp)
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            app.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        np.title.ifBlank { "Unknown title" },
                        style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = if (reduceMotion) TextOverflow.Ellipsis else TextOverflow.Clip,
                        modifier = Modifier.fillMaxWidth().then(if (reduceMotion) Modifier else Modifier.basicMarquee(iterations = 2)),
                    )
                    if (np.artist.isNotBlank()) {
                        Text(
                            np.artist,
                            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                            color = Color.White.copy(alpha = 0.66f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkipButton(R.drawable.ic_skip_previous, "Previous", np.can(RemoteCommand.PREVIOUS_TRACK)) { send(RemoteCommand.PREVIOUS_TRACK) }
                PlayPause(np, now, palette) { send(RemoteCommand.TOGGLE_PLAY_PAUSE) }
                SkipButton(R.drawable.ic_skip_next, "Next", np.can(RemoteCommand.NEXT_TRACK)) { send(RemoteCommand.NEXT_TRACK) }
            }
            Box(Modifier.weight(0.78f).fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.TopCenter) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when {
                            showVolume -> "Volume ${volume.percentText}"
                            np.durationSeconds > 0 -> "${clock(np.positionSeconds(now))} / ${clock(np.durationSeconds)}"
                            else -> " "
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (showVolume) palette.accent else Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                    )
                }
            }
        }
        if (showVolume) {
            LevelIndicator(value = { volume.fraction }, modifier = Modifier.align(Alignment.CenterStart))
        }
    }
}

/** Play/pause in the app's accent colour, with the track progress as a ring around it. */
@Composable
private fun PlayPause(np: NowPlaying, now: Long, palette: MediaPalette, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(70.dp)) {
        val fraction = if (np.durationSeconds > 0) (np.positionSeconds(now) / np.durationSeconds).toFloat() else 0f
        // Read through state: the indicator keeps the first lambda it gets, so a captured value would freeze
        // the ring at whatever it showed first (found on hardware: stuck at 0 after a track without a duration).
        val progress = androidx.compose.runtime.rememberUpdatedState(fraction)
        CircularProgressIndicator(
            progress = { progress.value },
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Track progress" },
            strokeWidth = 3.dp,
            colors = ProgressIndicatorDefaults.colors(indicatorColor = palette.accent, trackColor = palette.accent.copy(alpha = 0.22f)),
        )
        FilledIconButton(
            onClick = onClick,
            enabled = np.can(RemoteCommand.TOGGLE_PLAY_PAUSE) || np.can(RemoteCommand.PLAY) || np.can(RemoteCommand.PAUSE),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = palette.accent, contentColor = palette.onAccent),
            modifier = Modifier.size(58.dp),
        ) {
            Icon(
                painterResource(if (np.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = if (np.isPlaying) "Pause" else "Play",
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

@Composable
private fun SkipButton(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
        modifier = Modifier.size(48.dp),
    ) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(28.dp))
    }
}

/**
 * Colours derived from the playing app's brand colour. The accent is kept bright enough to read on black
 * (very dark brands such as X are lifted toward white), and the icon on it is black or white, whichever
 * contrasts more. The glow is the accent at low strength, so the screen stays mostly black.
 */
private class MediaPalette(val accent: Color, val onAccent: Color, val glow: Color) {
    companion object {
        fun from(brand: Color): MediaPalette {
            val accent = if (brand.luminance() < 0.08f) lerp(brand, Color.White, 0.7f) else brand
            val onAccent = if (accent.luminance() > 0.45f) Color.Black else Color.White
            return MediaPalette(accent, onAccent, accent.copy(alpha = 0.18f))
        }

        val NEUTRAL = MediaPalette(Color(0xFFE6E6EB), Color.Black, Color.Transparent)
    }
}

/**
 * Volume as the UI shows it: optimistic on each bezel click (so it feels instant), then corrected by the
 * level iOS reports back over AMS. iOS has 16 volume steps, and each command moves exactly one.
 */
private class VolumeState(private val haptic: () -> Unit) {
    var level by mutableIntStateOf(0)
    var known by mutableStateOf(false)
    /** iOS's exact level (it needn't sit on a step), or the optimistic one right after a click. */
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

@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** 74.0 → "1:14", 3725.0 → "1:02:05". */
private fun clock(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** iOS lists the commands the current player supports. An empty list means unknown: allow everything. */
private fun NowPlaying.can(c: RemoteCommand) = supported.isEmpty() || c in supported

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
private const val VOLUME_VISIBLE_MS = 1_500L
