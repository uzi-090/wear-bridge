package io.wearbridge.watch.ui

import androidx.annotation.DrawableRes
import io.wearbridge.watch.R

/**
 * Bundled brand logos (Simple Icons, CC0) plus generic system glyphs (Material Icons, Apache-2.0). They're vector glyphs drawn white on the app's brand-colour
 * circle. They ship with the app and are never downloaded at runtime. These are trademarks of their
 * owners, so review before any store release (risk R-13, docs/THIRD_PARTY.md).
 */
object AppLogos {
    private val BY_SLUG = mapOf(
        "airtel" to R.drawable.logo_airtel,
        "applemusic" to R.drawable.logo_applemusic,
        "discord" to R.drawable.logo_discord,
        "glyph_hourglass" to R.drawable.glyph_hourglass,
        "glyph_phone" to R.drawable.glyph_phone,
        "gmail" to R.drawable.logo_gmail,
        "googlechat" to R.drawable.logo_googlechat,
        "googlemaps" to R.drawable.logo_googlemaps,
        "imessage" to R.drawable.logo_imessage,
        "instagram" to R.drawable.logo_instagram,
        "messenger" to R.drawable.logo_messenger,
        "signal" to R.drawable.logo_signal,
        "spotify" to R.drawable.logo_spotify,
        "swiggy" to R.drawable.logo_swiggy,
        "telegram" to R.drawable.logo_telegram,
        "whatsapp" to R.drawable.logo_whatsapp,
        "x" to R.drawable.logo_x,
        "youtube" to R.drawable.logo_youtube,
    )

    @DrawableRes
    fun drawable(slug: String?): Int? = slug?.let(BY_SLUG::get)
}
