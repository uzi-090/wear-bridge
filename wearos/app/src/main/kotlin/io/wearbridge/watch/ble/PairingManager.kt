package io.wearbridge.watch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import io.wearbridge.watch.diagnostics.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * LE bonding with the iPhone. Pairing is usually started by iOS (when the companion app reads an
 * encrypted characteristic). Otherwise this starts it. The user confirms the numeric comparison
 * on both devices, which the system UI handles. Confined to the owner's serialized scope.
 */
@SuppressLint("MissingPermission")
class PairingManager(
    private val scope: CoroutineScope,
    private val onBonded: () -> Unit,
    private val onFailed: () -> Unit,
) {
    private var device: BluetoothDevice? = null
    private var timeout: Job? = null

    fun request(target: BluetoothDevice) {
        device = target
        when (target.bondState) {
            BluetoothDevice.BOND_BONDED -> return onBonded()
            BluetoothDevice.BOND_NONE -> DiagLog.i("pairing requested started=${target.createBond()}")
            else -> DiagLog.i("pairing already in progress")
        }
        timeout?.cancel()
        timeout = scope.launch {
            delay(PAIRING_TIMEOUT_MS)
            DiagLog.w("pairing timed out")
            onFailed()
        }
    }

    /** Feed ACTION_BOND_STATE_CHANGED here (already on the owner's scope). */
    fun onBondStateChanged(changed: BluetoothDevice, state: Int, previous: Int) {
        if (changed.address != device?.address) return
        DiagLog.i("bond state $previous → $state")
        when (state) {
            BluetoothDevice.BOND_BONDED -> {
                cancel()
                onBonded()
            }
            BluetoothDevice.BOND_NONE -> if (previous == BluetoothDevice.BOND_BONDING) {
                cancel()
                onFailed()
            }
        }
    }

    fun cancel() {
        timeout?.cancel()
        timeout = null
    }

    private companion object {
        /** Long enough for the user to compare codes on both screens. */
        const val PAIRING_TIMEOUT_MS = 60_000L
    }
}
