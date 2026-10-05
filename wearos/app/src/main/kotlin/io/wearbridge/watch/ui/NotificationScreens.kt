package io.wearbridge.watch.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LocalTextStyle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwipeToReveal
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import io.wearbridge.ancs.ActionId
import io.wearbridge.ancs.Category
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.R
import io.wearbridge.watch.notifications.ActionStatus
import io.wearbridge.watch.notifications.NotificationRepository
import io.wearbridge.watch.notifications.NotificationThread
import io.wearbridge.watch.notifications.WatchNotification
import io.wearbridge.watch.ui.theme.BridgeList
import io.wearbridge.watch.ui.theme.BridgePalette
import io.wearbridge.watch.ui.theme.Note
import io.wearbridge.watch.ui.theme.SectionHeader
import io.wearbridge.watch.ui.theme.morph
import io.wearbridge.watch.ui.theme.surface
import kotlinx.coroutines.flow.map

/**
 * The inbox: one card per conversation (app + sender/chat), Today then Earlier. Swipe a card left to
 * delete it from the watch (the iPhone keeps it); Clear all sits on the bottom edge.
 */
@Composable
fun NotificationListScreen(onOpen: (Long) -> Unit, onOpenThread: (Long) -> Unit) {
    val items by BridgeGraph.repository.items.collectAsStateWithLifecycle()
    val threads = remember(items) { NotificationRepository.threads(items) }
    val (today, earlier) = threads.partition { Copy.isToday(it.latest.timeMillis) }
    val deletable = items.any { !it.isIncomingCall && !it.isActiveCall }
    var confirmClear by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val open: (NotificationThread) -> Unit = { t -> if (t.count > 1) onOpenThread(t.latest.uid) else onOpen(t.latest.uid) }

    BridgeList(
        edgeButton = if (deletable) {
            {
                EdgeButton(
                    onClick = { confirmClear = true },
                    buttonSize = EdgeButtonSize.Small,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                ) {
                    Icon(painterResource(R.drawable.ic_clear_all), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(Copy.CLEAR_ALL)
                }
            }
        } else null,
    ) { spec ->
        item { SectionHeader("Notifications", spec) }
        if (items.isEmpty()) {
            item {
                Icon(
                    painterResource(R.drawable.ic_notifications),
                    contentDescription = null,
                    tint = BridgePalette.TextTertiary,
                    modifier = morph(spec).size(32.dp),
                )
            }
            item { Note(spec, "You're all caught up", MaterialTheme.colorScheme.onSurface) }
            item { Note(spec, "iPhone notifications appear here while you're connected.") }
        }
        if (today.isNotEmpty() && earlier.isNotEmpty()) item { SectionHeader("Today", spec) }
        items(today, key = { it.latest.uid }) { ThreadCard(it, open, Modifier.then(morph(spec)), surface(spec)) }
        if (earlier.isNotEmpty()) item { SectionHeader("Earlier", spec) }
        items(earlier, key = { it.latest.uid }) { ThreadCard(it, open, Modifier.then(morph(spec)), surface(spec)) }
    }

    AlertDialog(
        visible = confirmClear,
        onDismissRequest = { confirmClear = false },
        icon = { Icon(painterResource(R.drawable.ic_clear_all), contentDescription = null) },
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
private fun ThreadCard(
    thread: NotificationThread,
    onOpen: (NotificationThread) -> Unit,
    modifier: Modifier,
    transformation: androidx.wear.compose.material3.SurfaceTransformation,
) {
    val n = thread.latest
    val haptics = LocalHapticFeedback.current
    val deletable = !n.isIncomingCall && !n.isActiveCall
    val card = @Composable {
        TitleCard(
            onClick = { onOpen(thread) },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppBadge(n.app, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(n.appLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, color = BridgePalette.TextSecondary, style = MaterialTheme.typography.labelMedium)
                }
            },
            time = { Text(Copy.relative(n.timeMillis), color = BridgePalette.TextTertiary) },
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            transformation = transformation,
            // Cleared-on-iPhone history is dimmed; "Cleared" is also said in words, so it isn't colour-only.
            modifier = Modifier.fillMaxWidth().dimmed(thread.items.all { it.clearedOnPhone }),
        ) {
            // Sender (or chat) first, then the message: the order people read a conversation in.
            if (!n.masked && n.ancs.title.isNotBlank()) {
                ContentText(n.ancs.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
            when {
                n.masked -> Text(Copy.MASKED, maxLines = 1, color = BridgePalette.TextSecondary)
                n.ancs.message.isNotBlank() -> ContentText(n.ancs.message, color = BridgePalette.TextSecondary, maxLines = 2)
            }
            val more = thread.count - 1
            val footer = listOfNotNull(if (more > 0) Copy.moreMessages(more) else null, if (n.clearedOnPhone) Copy.CLEARED_SHORT else null)
            if (footer.isNotEmpty()) {
                Text(footer.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    if (!deletable) {
        Column(modifier) { card() }
        return
    }
    SwipeToReveal(
        primaryAction = {
            PrimaryActionButton(
                onClick = { delete(thread, haptics) },
                icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete from watch") },
                text = { Text("Delete") },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        },
        onSwipePrimaryAction = { delete(thread, haptics) },
        modifier = modifier,
    ) { card() }
}

private fun delete(thread: NotificationThread, haptics: androidx.compose.ui.hapticfeedback.HapticFeedback) {
    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    thread.items.forEach { BridgeGraph.delete(it.uid) }
}

/**
 * Fades cleared history. Alpha is applied per draw (no offscreen buffer): Modifier.alpha renders each card
 * into its own layer every frame, which the Watch4's GPU felt in long lists and transitions.
 */
private fun Modifier.dimmed(dim: Boolean): Modifier =
    if (!dim) this else graphicsLayer {
        alpha = 0.62f
        compositingStrategy = CompositingStrategy.ModulateAlpha
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

    BridgeList { spec ->
        val first = thread.firstOrNull()
        if (first == null) {
            item { Note(spec, Copy.CLEARED, MaterialTheme.colorScheme.onSurface) }
            return@BridgeList
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = morph(spec)) {
                AppBadge(first.app, size = 24.dp)
                Text(
                    if (first.masked) first.appLabel else first.ancs.title.ifBlank { first.appLabel },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
                )
                Text("${thread.size} messages", style = MaterialTheme.typography.labelSmall, color = BridgePalette.TextSecondary)
            }
        }
        items(thread, key = { it.uid }) { n ->
            Card(
                onClick = { onOpen(n.uid) },
                modifier = morph(spec).dimmed(n.clearedOnPhone),
                transformation = surface(spec),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                // The message is the headline; when it arrived is secondary.
                ContentText(
                    when {
                        n.masked -> Copy.MASKED
                        n.ancs.message.isNotBlank() -> n.ancs.message
                        else -> n.ancs.subtitle
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                )
                Text(Copy.relative(n.timeMillis), style = MaterialTheme.typography.labelSmall, color = BridgePalette.TextTertiary, modifier = Modifier.padding(top = 4.dp))
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
    var revealed by remember(uid) { mutableStateOf(false) }
    var deletedHere by remember(uid) { mutableStateOf(false) }

    BridgeList { spec ->
        val current = n
        if (current == null) {
            if (!deletedHere) item { Note(spec, Copy.CLEARED, MaterialTheme.colorScheme.onSurface) } // leaving after a delete: show nothing
            return@BridgeList
        }
        val a = current.ancs
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = morph(spec)) {
                AppBadge(current.app, size = 24.dp)
                Text(
                    "${current.appLabel} · ${Copy.relative(current.timeMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = BridgePalette.TextSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (current.masked && !revealed) {
            item { Note(spec, Copy.MASKED_DETAIL) }
            item {
                FilledTonalButton(
                    onClick = { revealed = true },
                    modifier = morph(spec),
                    transformation = surface(spec),
                    icon = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize)) },
                    label = { Text("Show content") },
                )
            }
        } else {
            if (a.title.isNotBlank()) item { ContentText(a.title, modifier = morph(spec), style = MaterialTheme.typography.titleMedium) }
            if (a.subtitle.isNotBlank()) item { ContentText(a.subtitle, modifier = morph(spec), style = MaterialTheme.typography.labelMedium, color = BridgePalette.TextSecondary) }
            if (a.message.isNotBlank()) item { ContentText(a.message, modifier = morph(spec), style = MaterialTheme.typography.bodyLarge) }
            if (a.isMessageTruncated) item { Note(spec, Copy.TRUNCATED) }
        }

        when {
            current.clearedOnPhone -> item { Note(spec, Copy.CLEARED_HISTORY) }
            current.stale -> item { Note(spec, Copy.STALE, BridgePalette.Attention) }
            else -> {
                // Labels come straight from iOS: positive is the screen's primary action, negative is tonal.
                a.positiveActionLabel?.let { label ->
                    item {
                        Button(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                BridgeGraph.performAction(uid, ActionId.POSITIVE)
                            },
                            modifier = morph(spec),
                            transformation = surface(spec),
                            icon = { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize)) },
                            label = { Text(label) },
                        )
                    }
                }
                a.negativeActionLabel?.let { label ->
                    item {
                        FilledTonalButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                BridgeGraph.performAction(uid, ActionId.NEGATIVE)
                            },
                            modifier = morph(spec),
                            transformation = surface(spec),
                            icon = { Icon(painterResource(R.drawable.ic_clear_all), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize)) },
                            label = { Text(label) },
                            secondaryLabel = { Text("On iPhone too") },
                        )
                    }
                }
                status?.takeIf { it.uid == uid }?.let { item { Note(spec, actionStatusText(it), MaterialTheme.colorScheme.primary) } }
                if (!current.hasActions) item { Note(spec, Copy.NO_ACTIONS) }
            }
        }
        if (!current.clearedOnPhone && (a.category == Category.SOCIAL || a.category == Category.EMAIL)) {
            item { Note(spec, Copy.REPLY_UNAVAILABLE, BridgePalette.TextTertiary) }
        }
        if (!current.isIncomingCall && !current.isActiveCall) {
            item {
                FilledTonalButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        deletedHere = true
                        BridgeGraph.delete(uid)
                        onDeleted()
                    },
                    modifier = morph(spec),
                    transformation = surface(spec),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = BridgePalette.Destructive, modifier = Modifier.size(ButtonDefaults.IconSize)) },
                    label = { Text(Copy.DELETE) },
                    secondaryLabel = if (current.clearedOnPhone) null else {
                        { Text(Copy.DELETE_DETAIL) }
                    },
                )
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

