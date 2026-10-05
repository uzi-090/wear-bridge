package io.wearbridge.watch.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import io.wearbridge.ancs.ActionId
import io.wearbridge.ancs.Category
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.notifications.ActionStatus
import io.wearbridge.watch.notifications.WatchNotification
import kotlinx.coroutines.flow.map

@Composable
fun NotificationListScreen(onOpen: (Long) -> Unit) {
    val items by BridgeGraph.repository.items.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()
    val (today, earlier) = items.partition { Copy.isToday(it.timeMillis) }

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader { Text("Notifications") } }
            if (items.isEmpty()) {
                item { Caption("No notifications") }
                item { Caption("iPhone notifications appear here while you're connected.") }
            }
            if (today.isNotEmpty() && earlier.isNotEmpty()) item { ListHeader { Text("Today") } }
            items(today, key = { it.uid }) { NotificationCard(it, onOpen) }
            if (earlier.isNotEmpty()) item { ListHeader { Text("Earlier") } }
            items(earlier, key = { it.uid }) { NotificationCard(it, onOpen) }
        }
    }
}

@Composable
private fun NotificationCard(n: WatchNotification, onOpen: (Long) -> Unit) {
    TitleCard(
        onClick = { onOpen(n.uid) },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppBadge(n.app, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(n.appLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        time = { Text(Copy.relative(n.timeMillis)) },
        subtitle = if (!n.masked && n.ancs.title.isNotBlank()) {
            { Text(n.ancs.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        when {
            n.masked -> Text(Copy.MASKED, maxLines = 1)
            n.ancs.message.isNotBlank() -> Text(n.ancs.message, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun NotificationDetailScreen(uid: Long) {
    val repo = BridgeGraph.repository
    val flow = remember(uid) { repo.items.map { list -> list.firstOrNull { it.uid == uid } } }
    val n by flow.collectAsStateWithLifecycle(initialValue = repo.get(uid))
    val status by repo.actionStatus.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val listState = rememberTransformingLazyColumnState()
    var revealed by remember(uid) { mutableStateOf(false) }

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            val current = n
            if (current == null) {
                // A header, not a caption: the first row on a round screen needs the header's edge-safe insets.
                item { ListHeader { Text(Copy.CLEARED, textAlign = TextAlign.Center) } }
                return@TransformingLazyColumn
            }
            val a = current.ancs
            item {
                ListHeader {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppBadge(current.app, size = 22.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("${current.appLabel} · ${Copy.relative(current.timeMillis)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (current.masked && !revealed) {
                item { Caption(Copy.MASKED_DETAIL) }
                item {
                    FilledTonalButton(onClick = { revealed = true }, modifier = Modifier.fillMaxWidth(), label = { Text("Show content") })
                }
            } else {
                if (a.title.isNotBlank()) item { Text(a.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth()) }
                if (a.subtitle.isNotBlank()) item { Text(a.subtitle, style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth()) }
                if (a.message.isNotBlank()) item { Text(a.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth()) }
                if (a.isMessageTruncated) item { Caption(Copy.TRUNCATED) }
            }

            if (current.stale) {
                item { Caption(Copy.STALE) }
            } else {
                // Labels come straight from iOS. The order is negative then positive, as iOS presents them.
                a.negativeActionLabel?.let { label ->
                    item {
                        FilledTonalButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                BridgeGraph.performAction(uid, ActionId.NEGATIVE)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(label) },
                        )
                    }
                }
                a.positiveActionLabel?.let { label ->
                    item {
                        Button(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                BridgeGraph.performAction(uid, ActionId.POSITIVE)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(label) },
                        )
                    }
                }
                status?.takeIf { it.uid == uid }?.let { item { Caption(actionStatusText(it)) } }
                if (!current.hasActions) item { Caption(Copy.NO_ACTIONS) }
            }
            if (a.category == Category.SOCIAL || a.category == Category.EMAIL) item { Caption(Copy.REPLY_UNAVAILABLE) }
        }
    }
}

fun actionStatusText(s: ActionStatus): String = when (s.state) {
    ActionStatus.State.SENDING -> "Sending to iPhone…"
    ActionStatus.State.DONE -> "Done on iPhone"
    ActionStatus.State.FAILED -> "iPhone didn't accept that"
    ActionStatus.State.UNAVAILABLE -> "Not connected to iPhone"
}

@Composable
fun Caption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
