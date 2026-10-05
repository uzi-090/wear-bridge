package io.wearbridge.watch.contacts

import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.wearbridge.protocol.Contact
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.R
import io.wearbridge.watch.ui.theme.BridgeList
import io.wearbridge.watch.ui.theme.BridgePalette
import io.wearbridge.watch.ui.theme.NavButton
import io.wearbridge.watch.ui.theme.Note
import io.wearbridge.watch.ui.theme.PastelAvatar
import io.wearbridge.watch.ui.theme.SectionHeader
import io.wearbridge.watch.ui.theme.morph
import io.wearbridge.watch.ui.theme.surface

/** Contacts synced from the iPhone: favourites as a grid of faces, then search, then everyone A–Z. */
@Composable
fun ContactsScreen(onOpen: (String) -> Unit) {
    val all by BridgeGraph.contacts.contacts.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searchUnavailable by remember { mutableStateOf(false) }

    // The system's Wear keyboard / voice input, through the standard RemoteInput activity.
    val search = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(SEARCH_KEY)?.toString()
        if (text != null) query = text.trim()
    }
    val startSearch = {
        val intent = Intent(ACTION_REMOTE_INPUT)
            .putExtra(EXTRA_REMOTE_INPUTS, arrayOf(RemoteInput.Builder(SEARCH_KEY).setLabel("Search contacts").build()))
        try {
            search.launch(intent)
        } catch (_: ActivityNotFoundException) {
            searchUnavailable = true
        }
    }

    val filtered = remember(all, query) {
        if (query.isBlank()) all else all.filter { c ->
            c.name.contains(query, ignoreCase = true) || c.phones.any { it.contains(query.filter(Char::isDigit).ifEmpty { "\u0000" }) }
        }
    }
    val favorites = remember(all) { all.filter { it.favorite }.take(6) }

    BridgeList { spec ->
        item { SectionHeader("Contacts", spec) }
        if (all.isEmpty()) {
            item { Icon(painterResource(R.drawable.ic_person), contentDescription = null, tint = BridgePalette.TextTertiary, modifier = morph(spec).size(32.dp)) }
            item { Note(spec, "No contacts on your watch", MaterialTheme.colorScheme.onSurface) }
            item { Note(spec, "Add people in the Bridge app on your iPhone: Contacts › Contacts on Watch.") }
            return@BridgeList
        }
        // Favourites as faces, three across, like the Wear OS contacts tile.
        if (query.isBlank() && favorites.isNotEmpty()) {
            favorites.chunked(3).forEach { row ->
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = morph(spec)) {
                        row.forEachIndexed { i, c ->
                            PastelAvatar(
                                c.name,
                                size = 50.dp,
                                square = i % 2 == 1, // alternate circle / rounded square, as in the tile
                                modifier = Modifier
                                    .clip(if (i % 2 == 1) RoundedCornerShape(16.dp) else CircleShape)
                                    .clickable(role = Role.Button) { onOpen(c.id) }
                                    .semantics { contentDescription = c.name },
                            )
                        }
                    }
                }
            }
        }
        item {
            FilledTonalButton(
                onClick = startSearch,
                modifier = morph(spec),
                transformation = surface(spec),
                icon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize)) },
                label = { Text(if (query.isBlank()) "Search" else "“$query”", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = if (query.isBlank()) null else {
                    { Text("${filtered.size} found") }
                },
            )
        }
        if (query.isNotBlank()) item { NavButton(spec, R.drawable.ic_person, "Show everyone") { query = "" } }
        if (searchUnavailable) item { Note(spec, "This watch has no keyboard for search.") }
        var lastLetter: Char? = null
        filtered.forEach { c ->
            val letter = c.name.firstOrNull()?.uppercaseChar()?.takeIf(Char::isLetter) ?: '#'
            if (query.isBlank() && letter != lastLetter) {
                lastLetter = letter
                item(key = "h:$letter") { SectionHeader(letter.toString(), spec) }
            }
            item(key = c.id) {
                FilledTonalButton(
                    onClick = { onOpen(c.id) },
                    modifier = morph(spec),
                    transformation = surface(spec),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    icon = { PastelAvatar(c.name, size = 30.dp) },
                    label = {
                        Text(
                            c.name.ifBlank { "No name" } + if (c.favorite) " ★" else "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content),
                        )
                    },
                    secondaryLabel = c.phones.firstOrNull()?.let { { Text(formatPhone(it), maxLines = 1, color = BridgePalette.TextSecondary) } },
                )
            }
        }
    }
}

@Composable
fun ContactDetailScreen(id: String) {
    val all by BridgeGraph.contacts.contacts.collectAsStateWithLifecycle()
    val request by BridgeGraph.callRequest.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val c = remember(all, id) { all.firstOrNull { it.id == id } }
    LaunchedEffect(id) { BridgeGraph.callRequestFlow.value = CallRequestState.IDLE }

    BridgeList { spec ->
        if (c == null) {
            item { Note(spec, "Contact removed", MaterialTheme.colorScheme.onSurface) }
            return@BridgeList
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = morph(spec)) {
                PastelAvatar(c.name, size = 52.dp)
                Text(
                    c.name.ifBlank { "No name" } + if (c.favorite) " ★" else "",
                    style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 20.dp, end = 20.dp),
                )
            }
        }
        items(c.phones, key = { "p:$it" }) { phone ->
            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    BridgeGraph.callFromIphone(phone, c.name)
                },
                modifier = morph(spec),
                transformation = surface(spec),
                colors = ButtonDefaults.buttonColors(containerColor = BridgePalette.CallGreen, contentColor = androidx.compose.ui.graphics.Color.White),
                icon = { Icon(painterResource(R.drawable.ic_call), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize)) },
                label = { Text(formatPhone(phone), maxLines = 1) },
                secondaryLabel = { Text("Call from iPhone") },
            )
        }
        when (request) {
            CallRequestState.SENT -> item { Note(spec, "Confirm the call on your iPhone.", BridgePalette.Connected) }
            CallRequestState.NO_APP -> item { Note(spec, "Open Bridge on your iPhone, then try again.", BridgePalette.Attention) }
            CallRequestState.IDLE -> if (c.phones.isNotEmpty()) item { Note(spec, "iOS asks you to confirm calls on the iPhone.", BridgePalette.TextTertiary) }
        }
        if (c.emails.isNotEmpty()) {
            item { SectionHeader("Email", spec) }
            items(c.emails, key = { "e:$it" }) { Note(spec, it, MaterialTheme.colorScheme.onSurface) }
        }
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

private const val SEARCH_KEY = "query"
private const val ACTION_REMOTE_INPUT = "android.support.wearable.input.action.REMOTE_INPUT"
private const val EXTRA_REMOTE_INPUTS = "android.support.wearable.input.extra.REMOTE_INPUTS"
