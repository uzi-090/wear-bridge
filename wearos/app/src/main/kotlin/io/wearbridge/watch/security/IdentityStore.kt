package io.wearbridge.watch.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.wearbridge.protocol.BridgeCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The watch's app-level identity (a P-256 key pair) and the iPhone app keys the user has allowed.
 *
 * The private key is stored encrypted with an AES-256-GCM key that lives in the Android Keystore and
 * never leaves it (Wear OS 3 can't do ECDH inside the Keystore, so the EC key is wrapped instead).
 * Trusted iPhone keys are public keys, so they're stored as plain hex. App data isn't backed up
 * (`allowBackup=false`, data extraction rules), so keys never leave the watch.
 */
class IdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences("bridge_identity", Context.MODE_PRIVATE)

    val publicKey: ByteArray
    val privateKey: PrivateKey

    private val _trusted = MutableStateFlow(prefs.getStringSet(KEY_TRUSTED, emptySet()).orEmpty())
    /** Hex X9.63 public key of the iPhone app the user allowed (at most one). */
    val trusted: StateFlow<Set<String>> = _trusted

    init {
        val storedPublic = prefs.getString(KEY_PUBLIC, null)
        val storedPrivate = prefs.getString(KEY_PRIVATE, null)
        val restored = if (storedPublic != null && storedPrivate != null) {
            runCatching { storedPublic.unhex() to unwrap(storedPrivate.unhex()) }.getOrNull()
        } else null
        if (restored != null) {
            publicKey = restored.first
            privateKey = restored.second
        } else {
            // First run, or the Keystore key was lost (e.g. a factory reset): new identity, nobody trusted.
            val kp = BridgeCrypto.newKeyPair()
            publicKey = BridgeCrypto.encodePublicKey(kp.public)
            privateKey = kp.private
            prefs.edit()
                .putString(KEY_PUBLIC, publicKey.hex())
                .putString(KEY_PRIVATE, wrap(kp.private.encoded).hex())
                .remove(KEY_TRUSTED)
                .apply()
            _trusted.value = emptySet()
        }
    }

    fun isTrusted(iphoneKey: ByteArray): Boolean = iphoneKey.hex() in _trusted.value

    /**
     * Allows [iphoneKey] as the one paired iPhone app. A previous app key is dropped: a new key
     * means Bridge on the iPhone was reinstalled or removed this watch, so the old one is dead.
     * Committed synchronously, so a restart right after approval can't restore pairing without trust.
     */
    fun trust(iphoneKey: ByteArray) {
        val next = setOf(iphoneKey.hex())
        check(prefs.edit().putStringSet(KEY_TRUSTED, next).commit())
        _trusted.value = next
    }

    /** Forgets every allowed iPhone app. They'll need to be allowed again. */
    fun forgetAll() {
        _trusted.value = emptySet()
        check(prefs.edit().remove(KEY_TRUSTED).commit())
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    /** iv (12) ‖ ciphertext+tag. */
    private fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        return c.iv + c.doFinal(plain)
    }

    private fun unwrap(blob: ByteArray): PrivateKey {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        }
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(c.doFinal(blob, 12, blob.size - 12)))
    }

    private companion object {
        const val ALIAS = "bridge.identity.wrap"
        const val KEY_PUBLIC = "public"
        const val KEY_PRIVATE = "private_wrapped"
        const val KEY_TRUSTED = "trusted_iphone_apps"

        fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        fun String.unhex() = ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
