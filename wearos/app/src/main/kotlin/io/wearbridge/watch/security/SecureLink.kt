package io.wearbridge.watch.security

import android.bluetooth.BluetoothDevice
import io.wearbridge.protocol.BridgeCrypto
import io.wearbridge.protocol.Framing
import io.wearbridge.protocol.Reassembler
import io.wearbridge.protocol.SecureMessageType
import io.wearbridge.protocol.SecureSession
import io.wearbridge.watch.ble.BridgePeripheral
import io.wearbridge.watch.ble.BridgeUuids
import io.wearbridge.watch.diagnostics.DiagLog
import io.wearbridge.watch.diagnostics.Redact
import java.security.SecureRandom

/**
 * App-level authentication for the bridge service (Risk R-22). The iPhone app sends HELLO on the AUTH
 * characteristic. A known app gets a session at once. A new one needs the user to allow it on the watch,
 * with the same 6-digit code shown on both screens. Until a session exists, every bridge message in
 * either direction is dropped. Confined to the BLE scope.
 */
class SecureLink(
    private val peripheral: BridgePeripheral,
    private val identity: IdentityStore,
    private val prompt: ApprovalPrompt,
    /** A new iPhone app key asks for approval; [request] identifies it for [expire]. */
    private val onApprovalRequired: (request: Int) -> Unit,
    private val onEstablished: (BluetoothDevice) -> Unit,
) {
    interface ApprovalPrompt {
        fun show(code: String)
        fun dismiss()
    }

    private class Pending(val device: BluetoothDevice, val iphoneKey: ByteArray, val staticKey: ByteArray, val nonceI: ByteArray)

    private val sessions = HashMap<String, SecureSession>()
    private val reassemblers = HashMap<String, Reassembler>()
    private var pending: Pending? = null
    private var request = 0
    private var messageId = 0
    private val random = SecureRandom()

    fun reset() {
        sessions.clear()
        reassemblers.clear()
        pending = null
        prompt.dismiss()
    }

    fun hasSession(device: BluetoothDevice) = device.address in sessions

    /** Sealed payload for [device], or null if it hasn't authenticated (then nothing may be sent). */
    fun seal(device: BluetoothDevice, type: Int, plaintext: ByteArray): ByteArray? = sessions[device.address]?.seal(type, plaintext)

    /** Plaintext, or null if unauthenticated, forged, corrupted or replayed. */
    fun open(device: BluetoothDevice, type: Int, payload: ByteArray): ByteArray? {
        val session = sessions[device.address] ?: return null.also { DiagLog.w("secure: message before authentication type=$type") }
        return session.open(type, payload) ?: null.also { DiagLog.w("secure: rejected message type=$type (forged, corrupted or replayed)") }
    }

    fun onAuthChunk(device: BluetoothDevice, chunk: ByteArray) {
        val r = reassemblers.getOrPut(device.address) { Reassembler(maxMessageBytes = 256) }
        when (val result = r.accept(chunk)) {
            is Reassembler.Result.Complete -> if (result.message.type == SecureMessageType.HELLO) onHello(device, result.message.payload)
            is Reassembler.Result.Error -> DiagLog.w("secure: ${result.reason}")
            Reassembler.Result.Incomplete -> Unit
        }
    }

    fun onDisconnected(device: BluetoothDevice) {
        sessions.remove(device.address)
        reassemblers.remove(device.address)
        if (pending?.device?.address == device.address) {
            pending = null
            prompt.dismiss()
        }
    }

    /** An unanswered approval [request] ends as a denial, so a late tap can't pair a phone that gave up. */
    fun expire(request: Int): Boolean {
        if (pending == null || request != this.request) return false
        DiagLog.i("secure: approval request expired")
        approve(false)
        return true
    }

    /** The iPhone app stopped listening for the answer (Cancel Pairing): drop its request. */
    fun withdraw(device: BluetoothDevice): Boolean {
        if (pending?.device?.address != device.address) return false
        DiagLog.i("secure: approval request withdrawn by the iPhone")
        pending = null
        prompt.dismiss()
        return true
    }

    /** The user answered the "Allow Bridge on iPhone?" prompt. */
    fun approve(allow: Boolean) {
        val p = pending ?: return
        pending = null
        prompt.dismiss()
        if (allow) {
            identity.trust(p.iphoneKey)
            DiagLog.i("secure: iPhone app allowed")
            establish(p.device, p.staticKey, p.nonceI)
        } else {
            DiagLog.i("secure: iPhone app denied")
            send(p.device, SecureMessageType.DENIED, ByteArray(0))
        }
    }

    private fun onHello(device: BluetoothDevice, payload: ByteArray) {
        val expected = BridgeCrypto.PUBLIC_KEY_BYTES + BridgeCrypto.NONCE_BYTES + BridgeCrypto.MAC_BYTES
        if (payload.size != expected) return DiagLog.w("secure: malformed hello")
        val iphoneKey = payload.copyOfRange(0, BridgeCrypto.PUBLIC_KEY_BYTES)
        val nonceI = payload.copyOfRange(BridgeCrypto.PUBLIC_KEY_BYTES, BridgeCrypto.PUBLIC_KEY_BYTES + BridgeCrypto.NONCE_BYTES)
        val mac = payload.copyOfRange(expected - BridgeCrypto.MAC_BYTES, expected)
        val staticKey = try {
            BridgeCrypto.staticKey(BridgeCrypto.ecdh(identity.privateKey, BridgeCrypto.decodePublicKey(iphoneKey)))
        } catch (e: Exception) {
            return DiagLog.w("secure: invalid iPhone key ${e::class.simpleName}")
        }
        // Only the holder of the iPhone app's private key can produce this, so nobody else can start or reset a session.
        if (!BridgeCrypto.constantTimeEquals(mac, BridgeCrypto.helloMac(staticKey, nonceI))) {
            DiagLog.w("secure: hello failed verification ${Redact.device(device)}")
            return send(device, SecureMessageType.DENIED, ByteArray(0))
        }
        if (identity.isTrusted(iphoneKey)) {
            establish(device, staticKey, nonceI)
            return
        }
        // A new app identity cannot inherit the previous app's ANCS access while awaiting approval.
        sessions.remove(device.address)
        onApprovalRequired(++request)
        pending = Pending(device, iphoneKey, staticKey, nonceI)
        val code = BridgeCrypto.pairingCode(staticKey, iphoneKey, identity.publicKey)
        DiagLog.i("secure: new iPhone app, waiting for approval on the watch")
        prompt.show(code)
        send(device, SecureMessageType.NEEDS_APPROVAL, ByteArray(0))
    }

    private fun establish(device: BluetoothDevice, staticKey: ByteArray, nonceI: ByteArray) {
        val nonceW = ByteArray(BridgeCrypto.NONCE_BYTES).also(random::nextBytes)
        sessions[device.address] = SecureSession.forWatch(BridgeCrypto.sessionKeys(staticKey, nonceI, nonceW))
        send(device, SecureMessageType.HELLO_OK, nonceW + BridgeCrypto.okMac(staticKey, nonceI, nonceW))
        DiagLog.i("secure: session established ${Redact.device(device)}")
        onEstablished(device)
    }

    private fun send(device: BluetoothDevice, type: Int, payload: ByteArray) {
        messageId = (messageId + 1) and 0xFF
        peripheral.notify(device, BridgeUuids.AUTH, Framing.split(type, messageId, payload, peripheral.maxPayload(device)))
    }
}
