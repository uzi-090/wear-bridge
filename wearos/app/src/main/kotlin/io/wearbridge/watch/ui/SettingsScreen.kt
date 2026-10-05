package io.wearbridge.watch.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.settings.PopupStyle

@Composable
fun SettingsScreen() {
    val settings = BridgeGraph.settings
    val popup by settings.popupStyle.collectAsStateWithLifecycle()
    val endCall by settings.endCallFromWatch.collectAsStateWithLifecycle()
    val history by settings.history.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader { Text("Settings") } }
            item { Caption("Choose which apps appear on your wrist in the Bridge app on your iPhone.") }
            item {
                FilledTonalButton(
                    onClick = { settings.setPopupStyle(PopupStyle.entries[(popup.ordinal + 1) % PopupStyle.entries.size]) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pop-up style: ${popup.label}") },
                    secondaryLabel = { Text(popup.description) },
                )
            }
            item {
                SwitchButton(
                    checked = history,
                    onCheckedChange = BridgeGraph::setHistory,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Keep history") },
                    secondaryLabel = { Text("Keep notifications for 24 hours after you clear them on iPhone. Never for hidden apps.") },
                )
            }
            item { ListHeader { Text("Security") } }
            item {
                FilledTonalButton(
                    onClick = BridgeGraph::forgetIphoneApps,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Forget iPhone app") },
                    secondaryLabel = { Text("The Bridge iPhone app will need your OK again") },
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
