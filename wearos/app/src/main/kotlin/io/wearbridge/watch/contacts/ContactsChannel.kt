package io.wearbridge.watch.contacts

import android.bluetooth.BluetoothDevice
import io.wearbridge.protocol.Contact
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
class ContactsChannel(private val secure: SecureLink, private val store: ContactsStore) {
    private val reassemblers = HashMap<String, Reassembler>()
    private var staging: HashMap<String, Contact>? = null

    fun onChunk(device: BluetoothDevice, chunk: ByteArray) {
        val r = reassemblers.getOrPut(device.address) { Reassembler(maxMessageBytes = 32 * 1024) }
        when (val result = r.accept(chunk)) {
            is Reassembler.Result.Complete -> {
                val plain = secure.open(device, result.message.type, result.message.payload) ?: return
                handle(result.message.type, plain)
            }
            is Reassembler.Result.Error -> DiagLog.w("contacts: ${result.reason}")
            Reassembler.Result.Incomplete -> Unit
        }
    }

    fun onDisconnected(device: BluetoothDevice) {
        reassemblers.remove(device.address)
        if (staging != null) DiagLog.i("contacts: sync interrupted, keeping the previous contacts")
        staging = null
    }

    private fun handle(type: Int, payload: ByteArray) {
        try {
            when (type) {
                ContactsMessageType.BEGIN -> {
                    val (full, count) = ContactsCodec.decodeBegin(payload)
                    staging = if (full) HashMap() else HashMap(store.snapshot())
                    DiagLog.i("contacts: sync begin full=$full expected=$count")
                }
                ContactsMessageType.UPSERT -> staging?.let { s -> ContactsCodec.decodeUpsert(payload).forEach { s[it.id] = it } }
                ContactsMessageType.DELETE -> staging?.let { s -> ContactsCodec.decodeDelete(payload).forEach(s::remove) }
                ContactsMessageType.END -> {
                    val s = staging ?: return DiagLog.w("contacts: END without BEGIN")
                    val generation = ContactsCodec.decodeEnd(payload)
                    store.commit(s, generation)
                    staging = null
                    DiagLog.i("contacts: committed ${s.size} contacts")
                }
                ContactsMessageType.CLEAR -> {
                    staging = null
                    store.clear()
                    DiagLog.i("contacts: cleared")
                }
                else -> DiagLog.w("contacts: unknown message type $type")
            }
        } catch (e: IllegalArgumentException) {
            DiagLog.w("contacts: malformed message type=$type")
        }
    }
}

/** "Call from iPhone" feedback on the watch. */
enum class CallRequestState { IDLE, SENT, NO_APP }
