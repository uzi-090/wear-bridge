package io.wearbridge.watch.contacts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.wearbridge.protocol.Contact
import io.wearbridge.protocol.ContactsCodec
import io.wearbridge.watch.diagnostics.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.text.Collator
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The watch's offline copy of the iPhone contacts the user chose to sync (§14): names, normalized phones,
 * a few emails, favorites. Stored in one file encrypted with an AES-256-GCM key that never leaves the
 * Android Keystore, in no-backup storage. Loaded into memory (a few thousand contacts is ~100 KB).
 */
class ContactsStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "contacts.bin")
    private val prefs = context.getSharedPreferences("bridge_contacts", Context.MODE_PRIVATE)
    private val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    /** Sorted by name, in the watch's locale. */
    val contacts: StateFlow<List<Contact>> = _contacts

    private val _generation = MutableStateFlow(prefs.getLong(KEY_GENERATION, 0))
    /** The last sync the watch committed, or 0. The iPhone resyncs fully when it doesn't match. */
    val generation: StateFlow<Long> = _generation

    init {
        // Debug demo contacts never outlive the process that created them.
        if (_generation.value == DEMO_GENERATION) clear()
        if (_generation.value != 0L) {
            val loaded = runCatching { load() }.onFailure { DiagLog.w("contacts: unreadable store ${it::class.simpleName}") }.getOrNull()
            if (loaded == null) {
                setGeneration(0) // forces a full resync from the iPhone
            } else {
                _contacts.value = sorted(loaded)
            }
        }
    }

    fun snapshot(): Map<String, Contact> = _contacts.value.associateBy { it.id }

    fun commit(contacts: Map<String, Contact>, generation: Long) {
        save(contacts.values)
        _contacts.value = sorted(contacts.values)
        setGeneration(generation)
    }

    fun clear() {
        file.delete()
        _contacts.value = emptyList()
        setGeneration(0)
    }

    /** The contact with this number, if any (last 10 digits must match). */
    fun findByPhone(number: String): Contact? =
        _contacts.value.firstOrNull { c -> c.phones.any { ContactsCodec.samePhone(it, number) } }

    private fun sorted(c: Collection<Contact>) = c.sortedWith { a, b -> collator.compare(a.name, b.name) }

    private fun setGeneration(g: Long) {
        prefs.edit().putLong(KEY_GENERATION, g).apply()
        _generation.value = g
    }

    // File: iv (12) ‖ AES-GCM( u32 count ‖ { u16 length ‖ one-contact UPSERT encoding }… )
    private fun save(contacts: Collection<Contact>) {
        val plain = ByteArrayOutputStream().apply {
            writeU32(contacts.size)
            for (c in contacts) {
                val bytes = ContactsCodec.encodeUpsert(listOf(c))
                write(bytes.size ushr 8)
                write(bytes.size and 0xFF)
                write(bytes)
            }
        }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(cipher.iv + cipher.doFinal(plain))
        tmp.renameTo(file) // atomic replace: a crash never leaves a half-written store
    }

    private fun load(): List<Contact> {
        val blob = file.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        }
        val plain = cipher.doFinal(blob, 12, blob.size - 12)
        var p = 4
        val count = ((plain[0].toInt() and 0xFF) shl 24) or ((plain[1].toInt() and 0xFF) shl 16) or
            ((plain[2].toInt() and 0xFF) shl 8) or (plain[3].toInt() and 0xFF)
        return List(count) {
            val len = ((plain[p].toInt() and 0xFF) shl 8) or (plain[p + 1].toInt() and 0xFF)
            p += 2
            ContactsCodec.decodeUpsert(plain.copyOfRange(p, p + len)).single().also { p += len }
        }
    }

    private fun ByteArrayOutputStream.writeU32(v: Int) {
        write(v ushr 24); write(v ushr 16); write(v ushr 8); write(v)
    }

    private fun key(): SecretKey {
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

    companion object {
        /** Reserved for the debug demo's fake contacts (the iPhone never picks it). */
        const val DEMO_GENERATION = 0xDE70DE70L
        private const val ALIAS = "bridge.contacts"
        private const val KEY_GENERATION = "generation"
    }
}
