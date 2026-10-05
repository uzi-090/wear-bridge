package io.wearbridge.protocol

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * App-layer security for the bridge service (docs/BLE_PROTOCOL.md "App-layer security", Risk R-22).
 *
 * The LE bond authenticates the *iPhone*, not the Bridge iPhone app: any iOS app could otherwise use the
 * bridge characteristics. So the Bridge apps pair once at app level (P-256 ECDH, approved on the watch
 * with a 6-digit code) and seal every bridge message with AES-128-GCM under per-connection keys, with
 * per-direction sequence numbers against replay. Mirrored in `ios/PairingPOC/BridgeCrypto.swift`; the
 * cross-platform test vectors in SecureChannelTest must match the Swift ones.
 */
object SecureMessageType {
    /** iOS → watch on AUTH: pubI (65) ‖ nonceI (16) ‖ helloMac (16). */
    const val HELLO = 0x20
    /** watch → iOS: nonceW (16) ‖ okMac (16). The session is live. */
    const val HELLO_OK = 0xB0
    /** watch → iOS: empty. The user must allow this iPhone app on the watch (code shown on both). */
    const val NEEDS_APPROVAL = 0xB1
    /** watch → iOS: empty. The user denied it, or the HELLO didn't verify. */
    const val DENIED = 0xB2
}

object BridgeCrypto {
    const val PUBLIC_KEY_BYTES = 65
    const val NONCE_BYTES = 16
    const val MAC_BYTES = 16
    const val KEY_BYTES = 16
    const val TAG_BYTES = 16
    const val SEQ_BYTES = 4

    /** "i2w " / "w2i ": the first 4 bytes of each direction's GCM nonce, so the two directions never collide. */
    val DIR_IPHONE_TO_WATCH = "i2w ".toByteArray(Charsets.US_ASCII)
    val DIR_WATCH_TO_IPHONE = "w2i ".toByteArray(Charsets.US_ASCII)

    private val P256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    /** X9.63 uncompressed point (0x04 ‖ X ‖ Y), the same as CryptoKit's `x963Representation`. */
    fun encodePublicKey(key: PublicKey): ByteArray {
        val w = (key as ECPublicKey).w
        return byteArrayOf(4) + fixed32(w.affineX) + fixed32(w.affineY)
    }

    fun decodePublicKey(bytes: ByteArray): PublicKey {
        require(bytes.size == PUBLIC_KEY_BYTES && bytes[0] == 4.toByte()) { "bad public key" }
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), P256))
    }

    /** A private key from its 32-byte scalar (tests, and CryptoKit's `rawRepresentation`). */
    fun privateKeyFromScalar(d: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, d), P256))

    fun newKeyPair(): java.security.KeyPair =
        java.security.KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** ECDH x-coordinate (32 bytes), as CryptoKit's `sharedSecretFromKeyAgreement` yields. */
    fun ecdh(privateKey: PrivateKey, peer: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(peer, true)
            generateSecret()
        }

    /** The long-term pairing key: HKDF-SHA256(ECDH, no salt, "bridge-v2 static"). */
    fun staticKey(sharedSecret: ByteArray): ByteArray = hkdf(sharedSecret, ByteArray(0), "bridge-v2 static", 32)

    /** The 6-digit code both screens show, bound to both public keys. */
    fun pairingCode(staticKey: ByteArray, pubI: ByteArray, pubW: ByteArray): String {
        val h = hmac(staticKey, "code".toByteArray(), pubI, pubW)
        val n = ((h[0].toLong() and 0x7F) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
            ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        return (n % 1_000_000).toString().padStart(6, '0')
    }

    /** Proves the sender holds the iPhone app's private key (an eavesdropper can't start or reset a session). */
    fun helloMac(staticKey: ByteArray, nonceI: ByteArray): ByteArray =
        hmac(staticKey, "hello".toByteArray(), nonceI).copyOf(MAC_BYTES)

    /** Proves the watch holds its key and binds both nonces. */
    fun okMac(staticKey: ByteArray, nonceI: ByteArray, nonceW: ByteArray): ByteArray =
        hmac(staticKey, "ok".toByteArray(), nonceI, nonceW).copyOf(MAC_BYTES)

    /** Per-connection keys: (iPhone → watch, watch → iPhone). */
    fun sessionKeys(staticKey: ByteArray, nonceI: ByteArray, nonceW: ByteArray): Pair<ByteArray, ByteArray> {
        val salt = nonceI + nonceW
        return hkdf(staticKey, salt, "bridge-v2 i2w", KEY_BYTES) to hkdf(staticKey, salt, "bridge-v2 w2i", KEY_BYTES)
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            parts.forEach(::update)
            doFinal()
        }

    /** RFC 5869 HKDF-SHA256. An empty salt means a zero-filled one, as the RFC (and CryptoKit) specify. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            t = hmac(prk, t, info.toByteArray(), byteArrayOf(counter.toByte()))
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = java.security.MessageDigest.isEqual(a, b)

    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size) // drop the sign byte
            else -> ByteArray(32 - b.size) + b
        }
    }
}

/**
 * One direction pair of an established session. A sealed message payload is
 * `seq u32 BE ‖ AES-GCM ciphertext ‖ tag`, with nonce = direction (4) ‖ seq (u64 BE) and
 * AAD = framing version ‖ message type, so a message can't be replayed, reordered, or moved to another type.
 * Not thread-safe: use from one serialized context.
 */
class SecureSession(
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
    private val sendDirection: ByteArray,
    private val receiveDirection: ByteArray,
) {
    private var sendSeq = 0L
    private var lastReceived = 0L

    fun seal(type: Int, plaintext: ByteArray): ByteArray {
        val seq = ++sendSeq
        check(seq <= 0xFFFFFFFFL) { "sequence exhausted: reconnect for new keys" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(BridgeCrypto.TAG_BYTES * 8, nonce(sendDirection, seq)))
            updateAAD(aad(type))
        }
        return u32(seq) + cipher.doFinal(plaintext)
    }

    /** The plaintext, or null if it's forged, corrupted, replayed or out of order. */
    fun open(type: Int, payload: ByteArray): ByteArray? {
        if (payload.size < BridgeCrypto.SEQ_BYTES + BridgeCrypto.TAG_BYTES) return null
        val seq = ((payload[0].toLong() and 0xFF) shl 24) or ((payload[1].toLong() and 0xFF) shl 16) or
            ((payload[2].toLong() and 0xFF) shl 8) or (payload[3].toLong() and 0xFF)
        if (seq <= lastReceived) return null // replay or reorder
        val plaintext = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(receiveKey, "AES"), GCMParameterSpec(BridgeCrypto.TAG_BYTES * 8, nonce(receiveDirection, seq)))
                updateAAD(aad(type))
                doFinal(payload, BridgeCrypto.SEQ_BYTES, payload.size - BridgeCrypto.SEQ_BYTES)
            }
        } catch (_: javax.crypto.AEADBadTagException) {
            return null
        }
        lastReceived = seq
        return plaintext
    }

    private fun nonce(direction: ByteArray, seq: Long): ByteArray = direction + ByteArray(8) { i -> (seq ushr (56 - 8 * i)).toByte() }

    private fun aad(type: Int) = byteArrayOf(Framing.VERSION.toByte(), type.toByte())

    private fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    companion object {
        fun forWatch(keys: Pair<ByteArray, ByteArray>) =
            SecureSession(keys.second, keys.first, BridgeCrypto.DIR_WATCH_TO_IPHONE, BridgeCrypto.DIR_IPHONE_TO_WATCH)

        fun forIphone(keys: Pair<ByteArray, ByteArray>) =
            SecureSession(keys.first, keys.second, BridgeCrypto.DIR_IPHONE_TO_WATCH, BridgeCrypto.DIR_WATCH_TO_IPHONE)
    }
}
