package io.wearbridge.watch.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Official App Store icons sent by the iPhone app (which fetches them from Apple's lookup service).
 * Stored in app-private storage, one small image per bundle ID. [version] changes whenever an icon is
 * added, so UI and the settings snapshot can refresh. Thread-safe.
 */
class AppIconStore(context: Context) {
    private val dir = File(context.filesDir, "app_icons").apply { mkdirs() }
    private val cache = LruCache<String, Bitmap>(64)

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    @Volatile
    private var known: Set<String> = dir.listFiles()?.map { it.name.removeSuffix(SUFFIX) }?.toSet().orEmpty()

    fun has(bundleId: String?): Boolean = bundleId != null && fileName(bundleId) in known

    /** Validates and stores an icon. Returns false if it isn't a sensible image. */
    @Synchronized
    fun save(bundleId: String, image: ByteArray): Boolean {
        if (image.isEmpty() || image.size > MAX_BYTES) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        if (bounds.outWidth !in 16..MAX_DIMENSION || bounds.outHeight !in 16..MAX_DIMENSION) return false
        val name = fileName(bundleId)
        File(dir, name + SUFFIX).writeBytes(image)
        cache.remove(name)
        known = known + name
        _version.value++
        return true
    }

    fun bitmap(bundleId: String?): Bitmap? {
        if (!has(bundleId)) return null
        val name = fileName(bundleId!!)
        cache.get(name)?.let { return it }
        return BitmapFactory.decodeFile(File(dir, name + SUFFIX).path)?.also { cache.put(name, it) }
    }

    /** Bundle IDs are reverse-DNS, but sanitise anyway so a bad value can't escape the directory. */
    private fun fileName(bundleId: String) = bundleId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(160)

    private companion object {
        const val SUFFIX = ".img"
        const val MAX_BYTES = 32 * 1024
        const val MAX_DIMENSION = 256
    }
}
