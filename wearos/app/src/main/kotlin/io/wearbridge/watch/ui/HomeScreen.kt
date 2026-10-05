package io.wearbridge.watch.ui

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.ble.LinkSnapshot
import io.wearbridge.watch.ble.LinkState
import io.wearbridge.watch.ble.PhoneServicesClient

@Composable
fun HomeScreen(
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenMedia: () -> Unit,
    onOpenFindPhone: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val link by BridgeGraph.link.collectAsStateWithLifecycle()
    val items by BridgeGraph.repository.items.collectAsStateWithLifecycle()
    val phone by BridgeGraph.phoneName.collectAsStateWithLifecycle()
    val model by BridgeGraph.phoneModel.collectAsStateWithLifecycle()
    val battery by BridgeGraph.phoneBattery.collectAsStateWithLifecycle()
    val media by BridgeGraph.media.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()
    val context = LocalContext.current

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader { Text("Bridge") } }
            item { StatusRow(link) }
            if (link.state == LinkState.READY && (phone != null || model != null)) {
                item {
                    // "iPhone 16 Pro · 72%": the model from Device Information, the level from Battery Service.
                    val name = if (model != null) PhoneServicesClient.marketingName(model) else phone!!
                    Text(
                        listOfNotNull(name, battery?.let { "$it%" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Copy.guidance(link)?.let { text ->
                item {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            if (!hasPermissions) {
                item {
                    Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth(), label = { Text("Allow Bluetooth") })
                }
            }
            if (hasPermissions && !BridgeGraph.notifier.canUseFullScreen) {
                item {
                    FilledTonalButton(
                        onClick = { openFullScreenSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Allow full-screen calls") },
                        secondaryLabel = { Text("Show iPhone calls over the watch face") },
                    )
                }
            }
            if (link.state == LinkState.ERROR) {
                item {
                    Button(onClick = BridgeGraph::reconnect, modifier = Modifier.fillMaxWidth(), label = { Text("Reconnect") })
                }
            }
            item {
                FilledTonalButton(
                    onClick = onOpenNotifications,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Notifications") },
                    secondaryLabel = { Text(if (items.isEmpty()) "None" else "${items.size}") },
                )
            }
            item {
                val np = media
                FilledTonalButton(
                    onClick = onOpenMedia,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Now playing") },
                    secondaryLabel = {
                        Text(
                            when {
                                np == null -> "iPhone media controls"
                                !np.hasPlayer -> "Nothing playing"
                                else -> np.title.ifBlank { np.playerName }
                            },
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    },
                    icon = { androidx.wear.compose.material3.Icon(androidx.compose.ui.res.painterResource(io.wearbridge.watch.R.drawable.ic_music_note), contentDescription = null) },
                )
            }
            item {
                FilledTonalButton(
                    onClick = onOpenFindPhone,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Find iPhone") },
                )
            }
            item {
                FilledTonalButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Settings") },
                )
            }
            item {
                FilledTonalButton(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Diagnostics") },
                )
            }
        }
    }
}

/** Status dot + words. The dot's shape also changes (filled vs ring), so colour isn't the only signal. */
@Composable
private fun StatusRow(link: LinkSnapshot) {
    val headline = Copy.headline(link)
    val ok = link.state == LinkState.READY
    val busy = link.state in setOf(LinkState.RECONNECTING, LinkState.CONNECTED, LinkState.DISCOVERING_SERVICES, LinkState.SUBSCRIBING, LinkState.PAIRING)
    val color = when {
        ok -> Color(0xFF4CD964)
        busy || link.state == LinkState.ADVERTISING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    val context = LocalContext.current
    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val pulse = if (ok && !reduceMotion) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(1f, 0.45f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "alpha").value
    } else 1f

    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clearAndSetSemantics { contentDescription = "Status: $headline" },
    ) {
        Box(
            Modifier
                .size(10.dp)
                .alpha(pulse)
                .then(if (ok) Modifier.background(color, CircleShape) else Modifier.border(2.dp, color, CircleShape)),
        )
        Text(
            headline,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
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
