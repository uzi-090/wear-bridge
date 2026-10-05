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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.LocalTextStyle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import io.wearbridge.ancs.ActionId
import io.wearbridge.ancs.Category
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.notifications.ActionStatus
import io.wearbridge.watch.notifications.NotificationRepository
import io.wearbridge.watch.notifications.NotificationThread
import io.wearbridge.watch.notifications.WatchNotification
import kotlinx.coroutines.flow.map

/** The inbox: one card per conversation (app + sender/chat), grouped into Today and Earlier. */
@Composable
fun NotificationListScreen(onOpen: (Long) -> Unit, onOpenThread: (Long) -> Unit) {
    val items by BridgeGraph.repository.items.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()
    val threads = remember(items) { NotificationRepository.threads(items) }
    val (today, earlier) = threads.partition { Copy.isToday(it.latest.timeMillis) }
    val deletable = items.any { !it.isIncomingCall && !it.isActiveCall }
    var confirmClear by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val open: (NotificationThread) -> Unit = { t -> if (t.count > 1) onOpenThread(t.latest.uid) else onOpen(t.latest.uid) }

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            if (deletable) {
                EdgeButton(onClick = { confirmClear = true }, buttonSize = EdgeButtonSize.Small) { Text(Copy.CLEAR_ALL) }
            }
        },
    ) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader { Text("Notifications") } }
            if (items.isEmpty()) {
                item { Caption("No notifications") }
                item { Caption("iPhone notifications appear here while you're connected.") }
            }
            if (today.isNotEmpty() && earlier.isNotEmpty()) item { ListHeader { Text("Today") } }
            items(today, key = { it.latest.uid }) { ThreadCard(it, open) }
            if (earlier.isNotEmpty()) item { ListHeader { Text("Earlier") } }
            items(earlier, key = { it.latest.uid }) { ThreadCard(it, open) }
        }
    }

    AlertDialog(
        visible = confirmClear,
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear watch?") },
        text = { Text(Copy.CLEAR_ALL_DETAIL, textAlign = TextAlign.Center) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                BridgeGraph.deleteAll()
                confirmClear = false
            })
        },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = { confirmClear = false }) },
    )
}

@Composable
private fun ThreadCard(thread: NotificationThread, onOpen: (NotificationThread) -> Unit) {
    val n = thread.latest
    TitleCard(
        onClick = { onOpen(thread) },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppBadge(n.app, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(n.appLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        time = { Text(Copy.relative(n.timeMillis)) },
        // Cleared-on-iPhone history is dimmed. "Cleared" is also said in words below, so it isn't colour-only.
        modifier = Modifier.fillMaxWidth().alpha(if (thread.items.all { it.clearedOnPhone }) 0.62f else 1f),
    ) {
        // Sender (or chat) first, then the message: the order people read a conversation in.
        if (!n.masked && n.ancs.title.isNotBlank()) {
            ContentText(
                n.ancs.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        when {
            n.masked -> Text(Copy.MASKED, maxLines = 1)
            n.ancs.message.isNotBlank() -> ContentText(n.ancs.message, maxLines = 2)
        }
        val more = thread.count - 1
        val footer = listOfNotNull(
            if (more > 0) Copy.moreMessages(more) else null,
            if (n.clearedOnPhone) Copy.CLEARED_SHORT else null,
        )
        if (footer.isNotEmpty()) {
            Text(
                footer.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Every notification in one conversation, newest first. [anchorUid] is any notification in it. */
@Composable
fun ThreadScreen(anchorUid: Long, onOpen: (Long) -> Unit) {
    val repo = BridgeGraph.repository
    val items by repo.items.collectAsStateWithLifecycle()
    // Saved (not just remembered), so the screen keeps working after the anchor itself is deleted while
    // a message detail is on top of it (destinations in the back stack lose plain remember state).
    val key = rememberSaveable(anchorUid) { repo.get(anchorUid)?.threadKey.orEmpty() }
    val thread = remember(items, key) { items.filter { it.threadKey == key }.sortedByDescending { it.timeMillis } }
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            val first = thread.firstOrNull()
            if (first == null) {
                item { ListHeader { Text(Copy.CLEARED, textAlign = TextAlign.Center) } }
                return@TransformingLazyColumn
            }
            item {
                ListHeader {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppBadge(first.app, size = 20.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (first.masked) first.appLabel else first.ancs.title.ifBlank { first.appLabel },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            items(thread, key = { it.uid }) { n ->
                TitleCard(
                    onClick = { onOpen(n.uid) },
                    title = { Text(Copy.relative(n.timeMillis)) },
                    modifier = Modifier.fillMaxWidth().alpha(if (n.clearedOnPhone) 0.62f else 1f),
                ) {
                    ContentText(
                        when {
                            n.masked -> Copy.MASKED
                            n.ancs.message.isNotBlank() -> n.ancs.message
                            else -> n.ancs.subtitle
                        },
                        maxLines = 3,
                    )
                }
            }
        }
    }
}

@Composable
fun NotificationDetailScreen(uid: Long, onDeleted: () -> Unit) {
    val repo = BridgeGraph.repository
    val flow = remember(uid) { repo.items.map { list -> list.firstOrNull { it.uid == uid } } }
    val n by flow.collectAsStateWithLifecycle(initialValue = repo.get(uid))
    val status by repo.actionStatus.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val listState = rememberTransformingLazyColumnState()
    var revealed by remember(uid) { mutableStateOf(false) }
    var deletedHere by remember(uid) { mutableStateOf(false) }

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            val current = n
            if (current == null) {
                if (deletedHere) return@TransformingLazyColumn // leaving: don't flash "cleared on iPhone"
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
                if (a.title.isNotBlank()) item { ContentText(a.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth()) }
                if (a.subtitle.isNotBlank()) item { ContentText(a.subtitle, style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth()) }
                if (a.message.isNotBlank()) item { ContentText(a.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth()) }
                if (a.isMessageTruncated) item { Caption(Copy.TRUNCATED) }
            }

            when {
                current.clearedOnPhone -> item { Caption(Copy.CLEARED_HISTORY) }
                current.stale -> item { Caption(Copy.STALE) }
                else -> {
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
            }
            if (!current.clearedOnPhone && (a.category == Category.SOCIAL || a.category == Category.EMAIL)) {
                item { Caption(Copy.REPLY_UNAVAILABLE) }
            }
            if (!current.isIncomingCall && !current.isActiveCall) {
                item {
                    OutlinedButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            deletedHere = true
                            BridgeGraph.delete(uid)
                            onDeleted()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(Copy.DELETE) },
                        secondaryLabel = if (current.clearedOnPhone) null else {
                            { Text(Copy.DELETE_DETAIL) }
                        },
                    )
                }
            }
        }
    }
}

fun actionStatusText(s: ActionStatus): String = when (s.state) {
    ActionStatus.State.SENDING -> "Sending to iPhone…"
    ActionStatus.State.DONE -> "Done on iPhone"
    ActionStatus.State.FAILED -> "iPhone didn't accept that"
    ActionStatus.State.UNAVAILABLE -> "Not connected to iPhone"
}

/**
 * Text from the iPhone (names, messages). Its direction comes from the text itself, so Arabic or Hebrew
 * aligns right even though the watch UI is left-to-right. Content never gets mixed into UI strings.
 */
@Composable
fun ContentText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text,
        modifier = modifier.fillMaxWidth(),
        style = style.copy(textDirection = TextDirection.Content),
        color = color,
        textAlign = TextAlign.Start,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
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
