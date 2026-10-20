package io.wearbridge.watch.contacts

import android.bluetooth.BluetoothDevice
import io.wearbridge.protocol.ContactsCodec
import io.wearbridge.protocol.ContactsMessageType
import io.wearbridge.protocol.Reassembler
import io.wearbridge.watch.diagnostics.DiagLog
import io.wearbridge.watch.security.SecureLink

/**
 * The CONTACTS characteristic: BEGIN, UPSERT/DELETE batches, END. Batches go to a staging copy that's
 * committed only at END, so an interrupted sync never leaves a half-updated address book (the iPhone
 * then sees an old generation and resyncs). Every message is sealed. Confined to the BLE scope.
 */
class ContactsChannel(private val secure: SecureLink, private val store: ContactsStore,
    private val onCleared: (ByteArray) -> Unit = {},
) {
    private val reassemblers = HashMap<String, Reassembler>()
    private val transaction = ContactTransaction(store::snapshot)
    private var owner: String? = null

    fun onChunk(device: BluetoothDevice, chunk: ByteArray) {
        val r = reassemblers.getOrPut(device.address) { Reassembler(maxMessageBytes = 32 * 1024) }
        when (val result = r.accept(chunk)) {
            is Reassembler.Result.Complete -> {
                val plain = secure.open(device, result.message.type, result.message.payload)
                if (plain == null) { if (owner == device.address) abort(); return }
                if (owner != null && owner != device.address) return
                if (result.message.type == ContactsMessageType.BEGIN) owner = device.address
                handle(result.message.type, plain)
            }
            is Reassembler.Result.Error -> { if (owner == device.address) abort(); DiagLog.w("contacts: framing rejected") }
            Reassembler.Result.Incomplete -> Unit
        }
    }

    fun onDisconnected(device: BluetoothDevice) {
        reassemblers.remove(device.address)
        if (owner == device.address) abort()
    }

    private fun abort() { transaction.abort(); owner = null }

    private fun handle(type: Int, payload: ByteArray) {
        // Debug demo mode shows fake contacts for screenshots: real syncs must not replace them meanwhile
        // (found: a resync put real names on screen). The iPhone resyncs after the app restarts.
        if (io.wearbridge.watch.BridgeGraph.demoMode) return
        try {
            if (type == ContactsMessageType.CLEAR) {
                require(payload.isEmpty() || payload.size == 16)
                abort(); store.clear()
                if (payload.size == 16) onCleared(payload.copyOf())
                DiagLog.i("contacts: cleared")
            } else {
                transaction.handle(type, payload)?.let { commit ->
                    store.commit(commit.contacts, commit.generation)
                    owner = null
                    DiagLog.i("contacts: committed ${commit.contacts.size} contacts")
                }
            }
        } catch (e: Exception) {
            abort()
            DiagLog.w("contacts: transaction rejected ${e::class.simpleName}")
        }
    }
}

/** "Call from iPhone" feedback on the watch. */
enum class CallRequestState { IDLE, SENT, NO_APP }
