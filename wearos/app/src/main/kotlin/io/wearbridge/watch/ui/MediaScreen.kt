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
import androidx.wear.compose.material3.Text
import io.wearbridge.ams.NowPlaying
import io.wearbridge.ams.RemoteCommand
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.R
import io.wearbridge.watch.ble.LinkState
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Now Playing on the iPhone, over Apple Media Service: works with any app iOS shows in its Now Playing
 * controls (Apple Music, Spotify, podcasts, YouTube…). The crown changes the iPhone's volume.
 */
@Composable
fun MediaScreen() {
    val media by BridgeGraph.media.collectAsStateWithLifecycle()
    val link by BridgeGraph.link.collectAsStateWithLifecycle()
    val np = media

    ScreenScaffold {
        when {
            np == null && link.state != LinkState.READY -> EmptyMedia("Not connected", "Connect to your iPhone to control what it's playing.")
            np == null -> EmptyMedia("Media unavailable", "Your iPhone isn't sharing media controls right now.")
            !np.hasPlayer -> EmptyMedia("Nothing playing", "Start music or a podcast on your iPhone, then control it here.")
            else -> Player(np)
        }
    }
}

@Composable
private fun Player(np: NowPlaying) {
    val haptics = LocalHapticFeedback.current
    val focus = remember { FocusRequester() }
    var rotaryAccumulator by remember { mutableFloatStateOf(0f) }
    var volumeShownAt by remember { mutableLongStateOf(0L) }

    // Position only ticks while this screen is visible and playing: a UI timer, never a Bluetooth poll.
    val now by produceState(SystemClock.elapsedRealtime(), np.isPlaying, np.elapsedAtMillis) {
        value = SystemClock.elapsedRealtime()
        while (np.isPlaying) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }
    val showVolume by produceState(false, volumeShownAt) {
        if (volumeShownAt == 0L) return@produceState
        value = true
        delay(VOLUME_VISIBLE_MS)
        value = false
    }

    fun send(command: RemoteCommand) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        BridgeGraph.mediaCommand(command)
    }

    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .onRotaryScrollEvent { e ->
                rotaryAccumulator += e.verticalScrollPixels
                if (abs(rotaryAccumulator) >= ROTARY_STEP_PX) {
                    val up = rotaryAccumulator > 0
                    rotaryAccumulator = 0f
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    BridgeGraph.mediaCommand(if (up) RemoteCommand.VOLUME_UP else RemoteCommand.VOLUME_DOWN)
                    volumeShownAt = SystemClock.elapsedRealtime()
                }
                true
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        if (np.durationSeconds > 0) {
            val fraction = (np.positionSeconds(now) / np.durationSeconds).toFloat()
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxSize().padding(2.dp).semantics { contentDescription = "Track progress" },
                strokeWidth = 4.dp,
            )
        }
        if (showVolume && np.volume != null) {
            LevelIndicator(value = { np.volume ?: 0f }, modifier = Modifier.align(Alignment.CenterStart))
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        ) {
            Text(
                np.playerName.ifBlank { "iPhone" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                np.title.ifBlank { "Unknown title" },
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MediaIcon(R.drawable.ic_skip_previous, "Previous", np.can(RemoteCommand.PREVIOUS_TRACK)) { send(RemoteCommand.PREVIOUS_TRACK) }
                FilledIconButton(
                    onClick = { send(RemoteCommand.TOGGLE_PLAY_PAUSE) },
                    enabled = np.can(RemoteCommand.TOGGLE_PLAY_PAUSE) || np.can(RemoteCommand.PLAY) || np.can(RemoteCommand.PAUSE),
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        painterResource(if (np.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = if (np.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(30.dp),
                    )
                }
                MediaIcon(R.drawable.ic_skip_next, "Next", np.can(RemoteCommand.NEXT_TRACK)) { send(RemoteCommand.NEXT_TRACK) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                MediaIcon(R.drawable.ic_volume_down, "Volume down", np.can(RemoteCommand.VOLUME_DOWN), small = true) {
                    send(RemoteCommand.VOLUME_DOWN)
                    volumeShownAt = SystemClock.elapsedRealtime()
                }
                MediaIcon(R.drawable.ic_volume_up, "Volume up", np.can(RemoteCommand.VOLUME_UP), small = true) {
                    send(RemoteCommand.VOLUME_UP)
                    volumeShownAt = SystemClock.elapsedRealtime()
                }
            }
        }
    }
}

/** iOS lists the commands the current player supports. An empty list means unknown: allow everything. */
private fun NowPlaying.can(c: RemoteCommand) = supported.isEmpty() || c in supported

@Composable
private fun MediaIcon(icon: Int, label: String, enabled: Boolean, small: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(if (small) 40.dp else 48.dp)) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(if (small) 20.dp else 26.dp))
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

private const val ROTARY_STEP_PX = 48f
private const val VOLUME_VISIBLE_MS = 2_000L
