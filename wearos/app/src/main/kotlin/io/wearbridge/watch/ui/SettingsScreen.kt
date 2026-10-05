package io.wearbridge.watch.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import io.wearbridge.ancs.Category
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.notifications.AppCatalog
import io.wearbridge.watch.notifications.AppKind
import io.wearbridge.watch.notifications.AppRule
import io.wearbridge.watch.notifications.FilterMode

@Composable
fun SettingsScreen(onOpenApps: () -> Unit) {
    val settings = BridgeGraph.settings
    val policy = BridgeGraph.policy
    val mode by policy.mode.collectAsStateWithLifecycle()
    val subtle by settings.subtlePopups.collectAsStateWithLifecycle()
    val endCall by settings.endCallFromWatch.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader { Text("Settings") } }
            item {
                // Tap to cycle: a short list of four doesn't need its own picker screen on a watch.
                FilledTonalButton(
                    onClick = { policy.setMode(FilterMode.entries[(mode.ordinal + 1) % FilterMode.entries.size]) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("On your wrist") },
                    secondaryLabel = { Text(mode.label) },
                )
            }
            item {
                FilledTonalButton(
                    onClick = onOpenApps,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Apps") },
                    secondaryLabel = { Text("Per-app notifications") },
                )
            }
            item {
                SwitchButton(
                    checked = subtle,
                    onCheckedChange = settings::setSubtlePopups,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Subtle pop-ups") },
                    secondaryLabel = { Text("Vibrate and show a dot instead of a full-screen pop-up") },
                )
            }
            item { ListHeader { Text("Experimental") } }
            item {
                SwitchButton(
                    checked = endCall,
                    onCheckedChange = settings::setEndCallFromWatch,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("End calls from watch") },
                    secondaryLabel = { Text("Uses iPhone behaviour Apple doesn't document. May stop working after an iOS update.") },
                )
            }
        }
    }
}

@Composable
fun AppsScreen(onOpenApp: (String) -> Unit) {
    val policy = BridgeGraph.policy
    val seen by policy.seenApps.collectAsStateWithLifecycle()
    val rules by policy.rules.collectAsStateWithLifecycle()
    val apps = seen.entries.sortedBy { it.value.lowercase() }
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader { Text("Apps") } }
            if (apps.isEmpty()) item { Caption("Apps appear here after their first notification.") }
            items(apps, key = { it.key }) { (bundleId, name) ->
                val info = AppCatalog.resolve(bundleId, name, Category.OTHER)
                val rule = rules[bundleId] ?: AppRule()
                FilledTonalButton(
                    onClick = { onOpenApp(bundleId) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { AppBadge(info, size = 24.dp) },
                    label = { Text(info.name, maxLines = 1) },
                    secondaryLabel = { Text(ruleSummary(rule, info.kind)) },
                )
            }
        }
    }
}

@Composable
fun AppSettingsScreen(bundleId: String) {
    val policy = BridgeGraph.policy
    val seen by policy.seenApps.collectAsStateWithLifecycle()
    val rules by policy.rules.collectAsStateWithLifecycle()
    val info = AppCatalog.resolve(bundleId, seen[bundleId], Category.OTHER)
    val rule = rules[bundleId] ?: AppRule()
    val hidden = rule.hideContent ?: (info.kind == AppKind.FINANCE)
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader { Text(info.name) } }
            item {
                SwitchButton(
                    checked = rule.enabled,
                    onCheckedChange = { policy.setRule(bundleId, rule.copy(enabled = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Show on watch") },
                )
            }
            item {
                SwitchButton(
                    checked = rule.vibrate,
                    enabled = rule.enabled,
                    onCheckedChange = { policy.setRule(bundleId, rule.copy(vibrate = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Vibrate") },
                )
            }
            item {
                SwitchButton(
                    checked = hidden,
                    enabled = rule.enabled,
                    onCheckedChange = { policy.setRule(bundleId, rule.copy(hideContent = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Hide content") },
                    secondaryLabel = { Text("Show only the app name until you tap") },
                )
            }
        }
    }
}

private fun ruleSummary(rule: AppRule, kind: AppKind): String = when {
    !rule.enabled -> "Off"
    rule.hideContent ?: (kind == AppKind.FINANCE) -> if (rule.vibrate) "On · content hidden" else "Silent · content hidden"
    !rule.vibrate -> "Silent"
    else -> "On"
}
