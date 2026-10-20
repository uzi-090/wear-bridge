package io.wearbridge.watch.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.R
import io.wearbridge.watch.ble.LinkSnapshot
import io.wearbridge.watch.ble.LinkState
import io.wearbridge.watch.ble.PhoneServicesClient
import io.wearbridge.watch.notifications.AppCatalog
import io.wearbridge.watch.ui.theme.BridgeList
import io.wearbridge.watch.ui.theme.BridgePalette
import io.wearbridge.watch.ui.theme.LiveRing
import io.wearbridge.watch.ui.theme.NavButton
import io.wearbridge.watch.ui.theme.Note
import io.wearbridge.watch.ui.theme.PrimaryButton
import io.wearbridge.watch.ui.theme.morph
import io.wearbridge.watch.ui.theme.surface

/**
 * Home, in the spirit of Wear OS's Material 3 Expressive tiles: one glance answers "is my iPhone
 * connected?", then big, thumb-sized tiles for what people do most. Everything technical lives in
 * Settings › Diagnostics.
 */
@Composable
fun HomeScreen(
    hasPermissions: Boolean,
    bluetoothEnabled: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenMedia: () -> Unit,
    onOpenFindPhone: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDevice: () -> Unit,
) {
    val link by BridgeGraph.link.collectAsStateWithLifecycle()
    val items by BridgeGraph.repository.items.collectAsStateWithLifecycle()
    val phone by BridgeGraph.phoneName.collectAsStateWithLifecycle()
    val model by BridgeGraph.phoneModel.collectAsStateWithLifecycle()
    val battery by BridgeGraph.phoneBattery.collectAsStateWithLifecycle()
    val charging by BridgeGraph.phoneCharging.collectAsStateWithLifecycle()
    val media by BridgeGraph.media.collectAsStateWithLifecycle()
    val seen by BridgeGraph.policy.seenApps.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pairing by BridgeGraph.pairing.state.collectAsStateWithLifecycle()
    val cleanupPending by BridgeGraph.pairing.cleanupPending.collectAsStateWithLifecycle()
    val pairingNotice by BridgeGraph.pairing.notice.collectAsStateWithLifecycle()
    val ready = pairing.canAccess(link)

    BridgeList { spec ->
        item { Note(spec, Copy.pairingPhase(pairing, BridgeGraph.pairing.pendingRemovalForget == true)) }
        if (pairing.enabled && !(pairing.phase == io.wearbridge.watch.ble.PairingPhase.PAIRING && pairingNotice != null)) {
            item { StatusLine(link, Copy.pairingConnection(pairing, link) ?: "Not connected", ready, Modifier.then(morph(spec))) }
        }
        pairingNotice?.let { item { Note(spec, it) } }
        if (pairing.phase == io.wearbridge.watch.ble.PairingPhase.UNPAIRED && hasPermissions && bluetoothEnabled && !cleanupPending) {
            item { PrimaryButton(spec, Copy.pairLabel(pairing), R.drawable.ic_bluetooth) { BridgeGraph.pairIphone(context) } }
            item { Note(spec, Copy.pairInstructions(pairing)) }
        }
        if (pairing.phase == io.wearbridge.watch.ble.PairingPhase.PAIRING && pairingNotice == null && hasPermissions && bluetoothEnabled) {
            item { Note(spec, Copy.pairInstructions(pairing)) }
        }

        if (ready) item {
            HeroCard(
                link = link,
                name = if (model != null) PhoneServicesClient.marketingName(model) else phone ?: "iPhone",
                battery = battery,
                charging = charging == true,
                modifier = morph(spec),
                transformation = surface(spec),
            )
        }

        if (pairing.phase != io.wearbridge.watch.ble.PairingPhase.REMOVING) {
            if (!hasPermissions) item { PrimaryButton(spec, "Allow Bluetooth", R.drawable.ic_bluetooth, onRequestPermissions) }
            else if (!bluetoothEnabled) item { PrimaryButton(spec, "Turn on Bluetooth", R.drawable.ic_bluetooth) {
                runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            } }
            else if (pairing.enabled && link.state == LinkState.ERROR) item { PrimaryButton(spec, "Reconnect", R.drawable.ic_rotate, BridgeGraph::reconnect) }
        }
        if (cleanupPending && pairing.phase != io.wearbridge.watch.ble.PairingPhase.REMOVING) item { Note(spec, "Finish removing saved data in Pairing & Devices before pairing again.") }

        if (ready) {
        // Two big tiles: the notification count as a numeral, and what's playing.
        item {
            val np = media
            val playing = np?.takeIf { it.hasMediaSession }
            val app = playing?.let { AppCatalog.forPlayerName(it.playerName, seen) }
            ButtonGroup(modifier = morph(spec), transformation = surface(spec)) {
                val alertsSource = remember { MutableInteractionSource() }
                val mediaSource = remember { MutableInteractionSource() }
                Tile(
                    onClick = onOpenNotifications,
                    interactionSource = alertsSource,
                    modifier = Modifier.weight(1f).animateWidth(alertsSource),
                    description = if (items.isEmpty()) "No notifications" else "${items.size} notifications",
                ) {
                    if (items.isEmpty()) {
                        Icon(painterResource(R.drawable.ic_notifications), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                    } else {
                        Text(if (items.size > 99) "99+" else "${items.size}", style = MaterialTheme.typography.numeralSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text("Alerts", style = MaterialTheme.typography.labelSmall, color = BridgePalette.TextSecondary)
                }
                Tile(
                    onClick = onOpenMedia,
                    interactionSource = mediaSource,
                    modifier = Modifier.weight(1f).animateWidth(mediaSource),
                    description = if (playing == null) "Music controls" else "Now playing on ${app?.name}",
                ) {
                    if (app != null && (app.bundleId != null || app.logo != null)) {
                        AppBadge(app, size = 26.dp)
                    } else {
                        Icon(painterResource(R.drawable.ic_music_note), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                    }
                    Text(
                        when {
                            playing == null -> "Music"
                            playing.isPlaying -> "Playing"
                            else -> "Paused"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = BridgePalette.TextSecondary,
                        maxLines = 1,
                    )
                }
            }
        }

        // Round buttons for the rest, like a tile's action row.
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = morph(spec)) {
                RoundAction(R.drawable.ic_phone_iphone, "Find iPhone", onOpenFindPhone)
                RoundAction(R.drawable.ic_person, "Contacts", onOpenContacts)
                RoundAction(R.drawable.ic_settings, "Settings", onOpenSettings)
            }
        }

        if (hasPermissions && !BridgeGraph.notifier.canUseFullScreen) {
            item {
                NavButton(spec, R.drawable.ic_call, "Full-screen calls", "Show iPhone calls over the watch face") {
                    openFullScreenSettings(context)
                }
            }
        }
        }
        item { NavButton(spec, R.drawable.ic_phone_iphone, "Pairing & Devices", "Pair, unpair or forget",
            labelMaxLines = 2, secondaryMaxLines = 2, onClick = onOpenDevice) }
        Copy.guidance(link)?.takeIf { pairing.enabled && !ready }?.let { text -> item { Note(spec, text) } }
    }
}

/** Status in words with a dot whose shape (filled vs ring) also changes, so it's never colour alone. */
@Composable
private fun StatusLine(link: LinkSnapshot, headline: String, ok: Boolean, modifier: Modifier) {
    val busy = link.state in setOf(LinkState.RECONNECTING, LinkState.CONNECTED, LinkState.DISCOVERING_SERVICES, LinkState.SUBSCRIBING, LinkState.PAIRING, LinkState.ADVERTISING)
    val color = when {
        ok -> BridgePalette.Connected
        busy -> MaterialTheme.colorScheme.primary
        else -> BridgePalette.Attention
    }
    val context = LocalContext.current
    val reduceMotion = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    // Two gentle pulses when the link comes up, then still: an endless animation would redraw the
    // screen every frame (jank, and battery on a watch). Once per connection, not every time Home is
    // shown again (that kept the screen redrawing for ~2 s after each back). Animated in the draw layer.
    val pulse = remember { androidx.compose.animation.core.Animatable(1f) }
    var pulsed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(ok) {
        if (!ok) {
            pulsed = false
            pulse.snapTo(1f)
        } else if (!pulsed && !reduceMotion) {
            pulsed = true
            repeat(2) {
                pulse.animateTo(0.35f, tween(450))
                pulse.animateTo(1f, tween(450))
            }
        }
    }
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(top = 4.dp, bottom = 2.dp).clearAndSetSemantics { contentDescription = "Status: $headline" },
    ) {
        Box(
            Modifier.size(8.dp).graphicsLayer {
                alpha = pulse.value
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha // one shape: no offscreen layer
            }
                .then(if (ok) Modifier.background(color, CircleShape) else Modifier.border(2.dp, color, CircleShape)),
        )
        Spacer(Modifier.width(6.dp))
        Text(headline, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The iPhone at a glance: battery as a big numeral with an arc, like the Steps tile. */
@Composable
private fun HeroCard(
    link: LinkSnapshot,
    name: String,
    battery: Int?,
    charging: Boolean,
    modifier: Modifier,
    transformation: androidx.wear.compose.material3.SurfaceTransformation,
) {
    val pairing by BridgeGraph.pairing.state.collectAsStateWithLifecycle()
    val ready = pairing.canAccess(link)
    Card(
        onClick = { if (link.state == LinkState.ERROR) BridgeGraph.reconnect() },
        modifier = modifier,
        transformation = transformation,
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (ready && battery != null) {
                    Text("$battery%", style = MaterialTheme.typography.numeralSmall, color = MaterialTheme.colorScheme.onSurface)
                } else {
                    Text(if (ready) "—" else "Offline", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                if (ready && charging) {
                    Text("Charging", style = MaterialTheme.typography.labelSmall, color = BridgePalette.Connected, maxLines = 1)
                } else {
                    Text(name, style = MaterialTheme.typography.labelSmall, color = BridgePalette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(46.dp)) {
                val level = (if (ready) battery ?: 0 else 0) / 100f
                val low = ready && battery != null && battery <= 20
                LiveRing(
                    progress = if (ready) level else 0f,
                    color = if (low) BridgePalette.Attention else BridgePalette.Connected,
                    modifier = Modifier.size(46.dp).semantics {
                        contentDescription = (if (ready && battery != null) "iPhone battery $battery percent" else "iPhone battery unknown") +
                            if (ready && charging) ", charging" else ""
                    },
                )
                Icon(
                    painterResource(if (ready && charging) R.drawable.ic_bolt else R.drawable.ic_phone_iphone),
                    contentDescription = null,
                    tint = when {
                        ready && charging -> BridgePalette.Connected
                        ready -> MaterialTheme.colorScheme.onSurface
                        else -> BridgePalette.TextTertiary
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** A big rounded tile (ButtonGroup child): icon or numeral on top, a short label below. */
@Composable
private fun Tile(
    onClick: () -> Unit,
    interactionSource: MutableInteractionSource,
    modifier: Modifier,
    description: String,
    content: @Composable () -> Unit,
) {
    Card(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier.height(76.dp).semantics { contentDescription = description },
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().height(60.dp),
        ) { content() }
    }
}

@Composable
private fun RoundAction(icon: Int, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(52.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(24.dp))
    }
}

/** Android 14+ settings screen for full-screen notifications. Falls back to the app's info page. */
private fun openFullScreenSettings(context: android.content.Context) {
    val pkg = android.net.Uri.fromParts("package", context.packageName, null)
    val intents = buildList {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(android.content.Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
        }
        add(android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
    }
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: android.content.ActivityNotFoundException) {
            // try the next one
        }
    }
}
