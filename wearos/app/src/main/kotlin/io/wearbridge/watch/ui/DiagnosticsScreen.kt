package io.wearbridge.watch.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.diagnostics.DiagLog
import io.wearbridge.watch.ui.theme.BridgeList
import io.wearbridge.watch.ui.theme.BridgePalette
import io.wearbridge.watch.ui.theme.SectionHeader
import io.wearbridge.watch.ui.theme.morph
import io.wearbridge.watch.ui.theme.surface

/** Developer diagnostics, behind Settings. Redacted by construction: DiagLog never receives content. */
@Composable
fun DiagnosticsScreen() {
    val link by BridgeGraph.link.collectAsStateWithLifecycle()
    val pairing by BridgeGraph.pairing.state.collectAsStateWithLifecycle()
    val entries by DiagLog.entries.collectAsStateWithLifecycle()
    val media by BridgeGraph.media.collectAsStateWithLifecycle()
    val appConnected by BridgeGraph.phoneAppConnected.collectAsStateWithLifecycle()
    val groups = listOf(
        "Link" to listOf(
            "State" to link.state.name.lowercase().replace('_', ' '),
            "Issue" to (link.issue?.name?.lowercase()?.replace('_', ' ') ?: "none"),
            "Retry" to link.attempt.toString(),
            "iPhone app" to if (appConnected) "connected, sealed" else "not connected",
            "Media (AMS)" to if (media != null) "ready" else "not ready",
        ),
        "Watch" to listOf(
            "Model" to Build.MODEL,
            "Android" to "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
            "Solicitation" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S).toString(),
            "Notifications" to if (BridgeGraph.notifier.canPost) "allowed" else "blocked",
            "Full-screen calls" to if (BridgeGraph.notifier.canUseFullScreen) "allowed" else "not allowed",
        ),
    )

    BridgeList { spec ->
        item { SectionHeader("Diagnostics", spec) }
        groups.forEach { (title, facts) ->
            item { SectionHeader(title, spec) }
            item {
                Card(
                    onClick = {},
                    enabled = false,
                    modifier = morph(spec),
                    transformation = surface(spec),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column {
                        facts.forEach { (k, v) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(k, style = MaterialTheme.typography.labelSmall, color = BridgePalette.TextSecondary, modifier = Modifier.weight(1f))
                                Text(v, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End)
                            }
                        }
                    }
                }
            }
        }
        if (pairing.canAccess(link)) {
        item { SectionHeader("Events", spec) }
        entries.asReversed().take(120).forEachIndexed { i, line ->
            item(key = "e$i") {
                Text(
                    line,
                    style = MaterialTheme.typography.bodyExtraSmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (line.contains("⚠")) BridgePalette.Attention else BridgePalette.TextSecondary,
                    modifier = morph(spec).padding(horizontal = 10.dp, vertical = 1.dp),
                )
            }
        }
        }
    }
}
