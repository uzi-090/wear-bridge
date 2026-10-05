package io.wearbridge.watch.contacts

import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
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
import io.wearbridge.protocol.Contact
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.ui.Caption
import io.wearbridge.watch.ui.ContentText

/** Contacts synced from the iPhone: search, favorites, then everyone A–Z. */
@Composable
fun ContactsScreen(onOpen: (String) -> Unit) {
    val all by BridgeGraph.contacts.contacts.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searchUnavailable by remember { mutableStateOf(false) }
    val listState = rememberTransformingLazyColumnState()

    // The system's Wear keyboard / voice input, through the standard RemoteInput activity.
    val search = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(SEARCH_KEY)?.toString()
        if (text != null) query = text.trim()
    }

    val filtered = remember(all, query) {
        if (query.isBlank()) all else all.filter { c ->
            c.name.contains(query, ignoreCase = true) || c.phones.any { it.contains(query.filter(Char::isDigit).ifEmpty { "\u0000" }) }
        }
    }
    val favorites = remember(filtered, query) { if (query.isBlank()) filtered.filter { it.favorite } else emptyList() }

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            item { ListHeader { Text("Contacts") } }
            if (all.isEmpty()) {
                item { Caption("No contacts on your watch") }
                item { Caption("Turn on Contacts in the Bridge app on your iPhone.") }
                return@TransformingLazyColumn
            }
            item {
                FilledTonalButton(
                    onClick = {
                        val intent = Intent(ACTION_REMOTE_INPUT)
                            .putExtra(EXTRA_REMOTE_INPUTS, arrayOf(RemoteInput.Builder(SEARCH_KEY).setLabel("Search contacts").build()))
                        try {
                            search.launch(intent)
                        } catch (_: ActivityNotFoundException) {
                            searchUnavailable = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (query.isBlank()) "Search" else "“$query”", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = if (query.isBlank()) null else {
                        { Text("${filtered.size} found") }
                    },
                )
            }
            if (query.isNotBlank()) {
                item { Button(onClick = { query = "" }, modifier = Modifier.fillMaxWidth(), label = { Text("Show all") }) }
            }
            if (searchUnavailable) item { Caption("This watch has no keyboard for search.") }
            if (favorites.isNotEmpty()) {
                item { ListHeader { Text("Favorites") } }
                items(favorites, key = { "fav:" + it.id }) { ContactRow(it, onOpen) }
                item { ListHeader { Text("All") } }
            }
            var lastLetter: Char? = null
            filtered.forEach { c ->
                val letter = c.name.firstOrNull()?.uppercaseChar()?.takeIf(Char::isLetter) ?: '#'
                if (query.isBlank() && letter != lastLetter) {
                    lastLetter = letter
                    item(key = "h:$letter") { ListHeader { Text(letter.toString()) } }
                }
                item(key = c.id) { ContactRow(c, onOpen) }
            }
        }
    }
}

@Composable
private fun ContactRow(c: Contact, onOpen: (String) -> Unit) {
    FilledTonalButton(
        onClick = { onOpen(c.id) },
        modifier = Modifier.fillMaxWidth(),
        icon = { Avatar(c, 28) },
        label = { Text(c.name.ifBlank { "No name" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content)) },
        secondaryLabel = c.phones.firstOrNull()?.let { { Text(formatPhone(it), maxLines = 1) } },
    )
}

@Composable
fun ContactDetailScreen(id: String) {
    val all by BridgeGraph.contacts.contacts.collectAsStateWithLifecycle()
    val request by BridgeGraph.callRequest.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val c = remember(all, id) { all.firstOrNull { it.id == id } }
    val listState = rememberTransformingLazyColumnState()
    LaunchedEffect(id) { BridgeGraph.callRequestFlow.value = CallRequestState.IDLE }

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            if (c == null) {
                item { ListHeader { Text("Contact removed", textAlign = TextAlign.Center) } }
                return@TransformingLazyColumn
            }
            // ListHeader gives the first row its round-screen inset below the clock.
            item { ListHeader { Avatar(c, 40) } }
            item {
                Text(
                    c.name.ifBlank { "No name" } + if (c.favorite) "  ★" else "",
                    style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
            }
            items(c.phones, key = { "p:$it" }) { phone ->
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        BridgeGraph.callFromIphone(phone, c.name)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(formatPhone(phone), maxLines = 1) },
                    secondaryLabel = { Text("Call from iPhone") },
                )
            }
            when (request) {
                CallRequestState.SENT -> item { Caption("Confirm the call on your iPhone.") }
                CallRequestState.NO_APP -> item { Caption("Open Bridge on your iPhone, then try again.") }
                CallRequestState.IDLE -> if (c.phones.isNotEmpty()) item { Caption("iOS asks you to confirm calls on the iPhone.") }
            }
            if (c.emails.isNotEmpty()) {
                item { ListHeader { Text("Email") } }
                items(c.emails, key = { "e:$it" }) { ContentText(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** Initials on a colour picked from the name, so the same person always looks the same. */
@Composable
private fun Avatar(c: Contact, sizeDp: Int) {
    val initials = c.name.split(' ').filter { it.isNotBlank() }.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("").ifEmpty { "#" }
    val color = AVATAR_COLORS[(c.name.hashCode() and 0x7FFFFFFF) % AVATAR_COLORS.size]
    Box(Modifier.size(sizeDp.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(initials, style = if (sizeDp > 32) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

/** Light grouping for readability: "+91 98765 43210". Display only; matching uses the raw digits. */
fun formatPhone(p: String): String {
    val plus = p.startsWith("+")
    val d = p.filter(Char::isDigit)
    if (d.length < 8) return p
    val tail = d.takeLast(10)
    val head = d.dropLast(10)
    val body = if (tail.length == 10) "${tail.take(5)} ${tail.drop(5)}" else tail
    return listOfNotNull((if (plus) "+" else "") + head, body).filter { it.isNotEmpty() && it != "+" }.joinToString(" ")
}

private val AVATAR_COLORS = listOf(
    Color(0xFF5B8DEF), Color(0xFF34A853), Color(0xFFE8710A), Color(0xFFA142F4), Color(0xFFD93025), Color(0xFF12B5CB), Color(0xFFE52592),
)

private const val SEARCH_KEY = "query"
private const val ACTION_REMOTE_INPUT = "android.support.wearable.input.action.REMOTE_INPUT"
private const val EXTRA_REMOTE_INPUTS = "android.support.wearable.input.extra.REMOTE_INPUTS"
