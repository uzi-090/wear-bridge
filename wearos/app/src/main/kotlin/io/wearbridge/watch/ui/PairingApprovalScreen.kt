package io.wearbridge.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay

/** Approval always takes priority over navigation; no list morphing can shrink or hide the code. */
@Composable
fun PairingApprovalScreen(code: String, onAllow: () -> Unit, onDeny: () -> Unit) {
    key(code) {
        val view = LocalView.current
        // Give the user time to compare the phone code, without changing their screen timeout.
        LaunchedEffect(view) {
            val wasKeepingScreenOn = view.keepScreenOn
            view.keepScreenOn = true
            try { delay(120_000) } finally { view.keepScreenOn = wasKeepingScreenOn }
        }
        val width = LocalConfiguration.current.screenWidthDp - 72
        // Fit all six digits, including leading zeroes, even with the accessibility font scale.
        val codeSize = (width / (4.8f * LocalDensity.current.fontScale)).coerceAtMost(32f).sp
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 30.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Bridge pairing", style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text("Match on iPhone", style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(code.chunked(3).joinToString(" "), fontSize = codeSize, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                    contentDescription = "Pairing code ${code.map { it.toString() }.joinToString(" ")}"
                })
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onDeny, modifier = Modifier.weight(1f).height(48.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    label = { Text("Deny", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 14.sp, maxLines = 1) })
                Button(onClick = onAllow, modifier = Modifier.weight(1f).height(48.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    label = { Text("Allow", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 14.sp, maxLines = 1) })
            }
        }
    }
}
