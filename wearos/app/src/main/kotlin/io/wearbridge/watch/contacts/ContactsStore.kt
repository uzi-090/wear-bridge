package io.wearbridge.watch.contacts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.wearbridge.protocol.Contact
import io.wearbridge.protocol.ContactsCodec
import io.wearbridge.watch.diagnostics.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
        if (file.exists()) {
            val loaded = runCatching { load() }.onFailure { DiagLog.w("contacts: unreadable store ${it::class.simpleName}") }.getOrNull()
            if (loaded != null) {
                _contacts.value = sorted(loaded.contacts)
                _generation.value = loaded.generation
            } else { _generation.value = 0 }
        } else { _generation.value = 0 }
        if (_generation.value == DEMO_GENERATION) clear()
    }

    fun snapshot(): Map<String, Contact> = _contacts.value.associateBy { it.id }

    fun commit(contacts: Map<String, Contact>, generation: Long) {
        val ordered = sorted(contacts.values)
        save(contacts.values, generation)
        _contacts.value = ordered
        _generation.value = generation
    }

    fun clear() = commit(emptyMap(), 0)

    /** Unpair removes the encrypted generation and any interrupted write, without needing encryption. */
    fun forgetDevice() {
        _contacts.value = emptyList()
        _generation.value = 0
        for (entry in listOf(file, File(file.parentFile, file.name + ".tmp"))) {
            check(!entry.exists() || entry.delete()) { "contact deletion failed" }
        }
        check(prefs.edit().clear().commit())
    }

    /** The contact with this number, if any (last 10 digits must match). */
    fun findByPhone(number: String): Contact? =
        _contacts.value.firstOrNull { c -> c.phones.any { ContactsCodec.samePhone(it, number) } }

    private fun sorted(c: Collection<Contact>) = c.sortedWith { a, b -> collator.compare(a.name, b.name) }

    private fun save(contacts: Collection<Contact>, generation: Long) {
        val plain = ContactBookCodec.encode(contacts, generation)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = cipher.iv + cipher.doFinal(plain)
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { stream -> stream.write(blob); stream.fd.sync() }
            // A failed replacement throws; memory and generation are published only after success.
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { tmp.delete() }
    }

    private fun load(): ContactBookCodec.Book {
        val blob = file.readBytes()
        require(blob.size in 28..2_000_000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        }
        return ContactBookCodec.decode(cipher.doFinal(blob, 12, blob.size - 12), prefs.getLong(KEY_GENERATION, 0))
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
