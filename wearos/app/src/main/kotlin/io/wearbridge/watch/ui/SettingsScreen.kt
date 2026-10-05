package io.wearbridge.watch.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import io.wearbridge.watch.BridgeGraph

@Composable
fun SettingsScreen() {
    val settings = BridgeGraph.settings
    val subtle by settings.subtlePopups.collectAsStateWithLifecycle()
    val endCall by settings.endCallFromWatch.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader { Text("Settings") } }
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
