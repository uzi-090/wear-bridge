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
 *
 * Icons are served as small circles ([circle]), cropped once: drawing a full-size icon scaled down and
 * clipped per frame cost the Watch4's GPU noticeably in lists and screen transitions.
 */
class AppIconStore(context: Context) {
    private val dir = File(context.filesDir, "app_icons").apply { mkdirs() }
    private val cache = LruCache<String, Bitmap>(128)

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    @Volatile
    private var known: Set<String> = dir.listFiles()?.map { it.name.removeSuffix(SUFFIX) }?.toSet().orEmpty()

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { check(it.delete()) }
        cache.evictAll()
        known = emptySet()
        _version.value++
    }

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

    /** The icon as a [CIRCLE_PX] circle, ready to draw without scaling or clipping. Decodes on a cache miss. */
    @Synchronized
    fun circle(bundleId: String?): Bitmap? {
        if (!has(bundleId)) return null
        val name = fileName(bundleId!!)
        cache.get(name)?.let { return it }
        val source = BitmapFactory.decodeFile(File(dir, name + SUFFIX).path) ?: return null
        return crop(source).also {
            source.recycle()
            cache.put(name, it)
        }
    }

    /** Decodes every stored icon, so lists never decode on the main thread. Call off the main thread. */
    fun prewarm() {
        known.forEach { circle(it) }
    }

    private fun crop(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(CIRCLE_PX, CIRCLE_PX, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        canvas.drawCircle(CIRCLE_PX / 2f, CIRCLE_PX / 2f, CIRCLE_PX / 2f, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, null, android.graphics.Rect(0, 0, CIRCLE_PX, CIRCLE_PX), paint)
        return out
    }

    /** Bundle IDs are reverse-DNS, but sanitise anyway so a bad value can't escape the directory. */
    private fun fileName(bundleId: String) = bundleId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(160)

    private companion object {
        const val SUFFIX = ".img"
        const val MAX_BYTES = 32 * 1024
        const val MAX_DIMENSION = 256
        const val CIRCLE_PX = 96
    }
}
