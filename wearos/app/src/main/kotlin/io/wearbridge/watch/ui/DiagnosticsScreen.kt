package io.wearbridge.watch.ui

import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.diagnostics.DiagLog

/** Developer diagnostics. Redacted by construction: DiagLog never receives content. */
@Composable
fun DiagnosticsScreen() {
    val link by BridgeGraph.link.collectAsStateWithLifecycle()
    val entries by DiagLog.entries.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()
    val facts = listOf(
        "state" to link.state.name,
        "issue" to (link.issue?.name ?: "none"),
        "attempt" to link.attempt.toString(),
        "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "model" to Build.MODEL,
        "solicitation" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S).toString(),
        "post notifications" to BridgeGraph.notifier.canPost.toString(),
        "full-screen calls" to BridgeGraph.notifier.canUseFullScreen.toString(),
    )

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader { Text("Diagnostics") } }
            facts.forEach { (k, v) ->
                item { Text("$k: $v", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth()) }
            }
            item { ListHeader { Text("Events") } }
            entries.asReversed().forEach { line ->
                item { Text(line, style = MaterialTheme.typography.bodyExtraSmall, modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}
