package io.wearbridge.watch.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Text
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.R
import io.wearbridge.watch.ble.PairingPhase
import io.wearbridge.watch.ble.LinkState
import io.wearbridge.watch.ui.theme.BridgeList
import io.wearbridge.watch.ui.theme.BridgePalette
import io.wearbridge.watch.ui.theme.NavButton
import io.wearbridge.watch.ui.theme.Note
import io.wearbridge.watch.ui.theme.PrimaryButton
import io.wearbridge.watch.ui.theme.SectionHeader

@Composable
fun DeviceScreen(hasPermissions: Boolean, bluetoothEnabled: Boolean, onRequestPermissions: () -> Unit, onDiagnostics: () -> Unit) {
    val pairing by BridgeGraph.pairing.state.collectAsStateWithLifecycle()
    val link by BridgeGraph.link.collectAsStateWithLifecycle()
    val notice by BridgeGraph.pairing.notice.collectAsStateWithLifecycle()
    val cleanupPending by BridgeGraph.pairing.cleanupPending.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var removal by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val removing = pairing.phase == PairingPhase.REMOVING
    val listState = key(pairing.phase) { rememberTransformingLazyColumnState() }
    LaunchedEffect(removing) { if (removing) removal = null }
    val bluetoothOff = !bluetoothEnabled
    val openBluetooth = {
        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Unit
    }
    BridgeList(state = listState) { spec ->
        item { SectionHeader("Device", spec) }
        item { Note(spec, Copy.pairingPhase(pairing, BridgeGraph.pairing.pendingRemovalForget == true)) }
        if (!(pairing.phase == PairingPhase.PAIRING && notice != null))
            Copy.pairingConnection(pairing, link)?.let { item { Note(spec, it) } }
        notice?.let { item { Note(spec, it) } }
        if (!removing && !hasPermissions) {
            item { PrimaryButton(spec, "Allow Bluetooth", R.drawable.ic_bluetooth, onRequestPermissions) }
        } else if (!removing && bluetoothOff) {
            item { PrimaryButton(spec, "Turn on Bluetooth", R.drawable.ic_bluetooth, openBluetooth) }
        }
        if (pairing.phase == PairingPhase.UNPAIRED && !cleanupPending) {
            if (pairing.address != null && notice == null) item { Note(spec, "Your iPhone is still saved.") }
            if (hasPermissions && !bluetoothOff) item { PrimaryButton(spec, Copy.pairLabel(pairing), R.drawable.ic_bluetooth) { BridgeGraph.pairIphone(context) } }
            item { Note(spec, Copy.pairInstructions(pairing)) }
        } else if (pairing.phase == PairingPhase.PAIRING && notice == null && !bluetoothOff && hasPermissions) {
            item { Note(spec, Copy.pairInstructions(pairing)) }
        }
        if (pairing.enabled && hasPermissions && !bluetoothOff && link.state == LinkState.ERROR) {
            item { PrimaryButton(spec, "Reconnect", R.drawable.ic_rotate, BridgeGraph::reconnect) }
        }
        if (cleanupPending && !removing) {
            item { Note(spec, "Finish removing saved data before pairing again.") }
        }
        if (pairing.enabled) {
            item { NavButton(spec, R.drawable.ic_bluetooth,
                if (pairing.phase == PairingPhase.PAIRING) "Cancel pairing" else "Unpair iPhone",
                if (pairing.phase == PairingPhase.PAIRING) "Stop setup" else "Keeps the saved iPhone",
                iconTint = BridgePalette.Destructive, labelMaxLines = 2, secondaryMaxLines = 2) { removal = false } }
        }
        if ((pairing.address != null || pairing.enabled || cleanupPending) && pairing.phase != PairingPhase.REMOVING) {
            item { NavButton(spec, R.drawable.ic_delete, "Forget Device", "Removes the saved iPhone", iconTint = BridgePalette.Destructive,
                labelMaxLines = 2, secondaryMaxLines = 2) { removal = true } }
        }
        if (pairing.enabled && notice == null) Copy.guidance(link)?.let { item { Note(spec, it) } }
        if (!removing) {
            item { NavButton(spec, R.drawable.ic_settings, "Bluetooth Settings", labelMaxLines = 2, onClick = openBluetooth) }
            item { Note(spec, "To remove Bluetooth pairing too, forget this device in Bluetooth Settings on both devices.") }
        }
        item { NavButton(spec, R.drawable.ic_info, "Diagnostics", onClick = onDiagnostics) }
    }
    AlertDialog(
        visible = removal != null,
        onDismissRequest = { removal = null },
        title = { Text(if (removal == true) "Forget Device?" else if (pairing.phase == PairingPhase.PAIRING) "Cancel pairing?" else "Unpair iPhone?",
            fontSize = 14.sp, textAlign = TextAlign.Center) },
        text = { Text(if (removal == false && pairing.phase == PairingPhase.PAIRING)
            "Stops setup. You can pair again later." else Copy.removalExplanation(removal == true)) },
        confirmButton = { AlertDialogDefaults.ConfirmButton(modifier = Modifier.semantics {
            contentDescription = if (removal == true) "Forget Device" else if (pairing.phase == PairingPhase.PAIRING) "Cancel pairing" else "Unpair iPhone"
        }, onClick = {
            if (removal == true) BridgeGraph.forgetIphone() else BridgeGraph.unpairIphone()
            removal = null
        }) },
        dismissButton = { AlertDialogDefaults.DismissButton(modifier = Modifier.semantics { contentDescription = "Cancel" }, onClick = { removal = null }) },
    )
}
