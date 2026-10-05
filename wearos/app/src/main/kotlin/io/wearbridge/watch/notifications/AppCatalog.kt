package io.wearbridge.watch.notifications

import io.wearbridge.ancs.Category

/** What kind of app a notification came from. It drives default filtering and masking. */
enum class AppKind { MESSAGING, EMAIL, SOCIAL, WORK, CALLS, FINANCE, PRODUCTIVITY, MEDIA, NAVIGATION, SHOPPING, SYSTEM, OTHER }

/**
 * Display metadata for an iOS app. [color] is the ARGB brand colour for the badge background.
 * [logo] is a bundled Simple Icons slug (see [io.wearbridge.watch.ui.AppLogos]) when one exists.
 * Otherwise the badge shows the app's initial.
 */
data class AppInfo(
    val bundleId: String?,
    val name: String,
    val color: Long,
    val kind: AppKind,
    val logo: String? = null,
) {
    /** One uppercase letter or digit for the badge, or a dot if the name has neither. */
    val initial: String
        get() = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
}

/**
 * Local bundle-ID → metadata catalog (§10). It ships with the app, so it's updated with each app
 * release. Nothing is fetched at runtime. Keyed by bundle ID only, never by matching display names.
 */
object AppCatalog {
    private fun e(id: String, name: String, color: Long, kind: AppKind, logo: String? = null) =
        id to AppInfo(id, name, color, kind, logo)

    private val KNOWN: Map<String, AppInfo> = mapOf(
        // Apple
        e("com.apple.MobileSMS", "Messages", 0xFF34C759, AppKind.MESSAGING, "imessage"),
        e("com.apple.mobilephone", "Phone", 0xFF34C759, AppKind.CALLS, "glyph_phone"),
        e("com.apple.facetime", "FaceTime", 0xFF34C759, AppKind.CALLS),
        e("com.apple.mobilemail", "Mail", 0xFF1E88E5, AppKind.EMAIL),
        e("com.apple.mobilecal", "Calendar", 0xFFFF3B30, AppKind.PRODUCTIVITY),
        e("com.apple.reminders", "Reminders", 0xFFFF9500, AppKind.PRODUCTIVITY),
        e("com.apple.Preferences", "Settings", 0xFF8E8E93, AppKind.SYSTEM),
        e("com.apple.ScreenTimeNotifications", "Screen Time", 0xFF5E5CE6, AppKind.SYSTEM, "glyph_hourglass"),
        // Messaging
        e("net.whatsapp.WhatsApp", "WhatsApp", 0xFF25D366, AppKind.MESSAGING, "whatsapp"),
        e("net.whatsapp.WhatsAppSMB", "WhatsApp Business", 0xFF25D366, AppKind.MESSAGING, "whatsapp"),
        e("ph.telegra.Telegraph", "Telegram", 0xFF2AABEE, AppKind.MESSAGING, "telegram"),
        e("org.whispersystems.signal", "Signal", 0xFF3A76F0, AppKind.MESSAGING, "signal"),
        e("com.facebook.Messenger", "Messenger", 0xFF0084FF, AppKind.MESSAGING, "messenger"),
        // Social
        e("com.burbn.instagram", "Instagram", 0xFFE1306C, AppKind.SOCIAL, "instagram"),
        e("com.atebits.Tweetie2", "X", 0xFF2F3336, AppKind.SOCIAL, "x"),
        e("com.hammerandchisel.discord", "Discord", 0xFF5865F2, AppKind.SOCIAL, "discord"),
        e("com.linkedin.LinkedIn", "LinkedIn", 0xFF0A66C2, AppKind.SOCIAL),
        // Email and work
        e("com.google.Gmail", "Gmail", 0xFFEA4335, AppKind.EMAIL, "gmail"),
        e("com.microsoft.Office.Outlook", "Outlook", 0xFF0078D4, AppKind.EMAIL),
        e("com.tinyspeck.chatlyio", "Slack", 0xFF611F69, AppKind.WORK),
        e("com.microsoft.skype.teams", "Teams", 0xFF6264A7, AppKind.WORK),
        e("com.google.Dynamite", "Google Chat", 0xFF00AC47, AppKind.WORK, "googlechat"),
        // Media and navigation
        e("com.apple.Music", "Music", 0xFFFA243C, AppKind.MEDIA, "applemusic"),
        e("com.spotify.client", "Spotify", 0xFF1DB954, AppKind.MEDIA, "spotify"),
        e("com.google.ios.youtube", "YouTube", 0xFFFF0000, AppKind.MEDIA, "youtube"),
        e("com.google.ios.youtubemusic", "YouTube Music", 0xFFFF0000, AppKind.MEDIA, "youtube"),
        e("com.apple.podcasts", "Podcasts", 0xFF9B4DDB, AppKind.MEDIA),
        e("com.google.Maps", "Google Maps", 0xFF34A853, AppKind.NAVIGATION, "googlemaps"),
        // Seen on the test iPhone (India)
        e("com.kotak811mobilebankingapp.instantsavingsupiscanandpayrecharge", "Kotak 811", 0xFFED1C24, AppKind.FINANCE),
        e("com.BhartiMobile.myairtel", "Airtel Thanks", 0xFFE40000, AppKind.OTHER, "airtel"),
        e("bundl.swiggy", "Swiggy", 0xFFFC8019, AppKind.SHOPPING, "swiggy"),
        e("com.appflipkart.flipkart", "Flipkart", 0xFF2874F0, AppKind.SHOPPING),
        e("olacabs.OlaCabs", "Ola", 0xFF35B34A, AppKind.NAVIGATION),
        e("bike.rapido.customer", "Rapido", 0xFFF9C935, AppKind.NAVIGATION),
        e("com.eazydiner.edapp", "EazyDiner", 0xFFE53935, AppKind.SHOPPING),
        e("com.zeeTV.DIDS4", "ZEE5", 0xFF8230C6, AppKind.MEDIA),
        e("com.truesoftware.TrueCallerOther", "Truecaller", 0xFF0087FF, AppKind.CALLS),
        e("com.valyoo.lenskart", "Lenskart", 0xFF00B5B8, AppKind.SHOPPING),
        e("com.GeoGuessr.iOS.GeoGuessr", "GeoGuessr", 0xFF5A3DC8, AppKind.OTHER),
    )

    /** Muted accents for unknown apps, chosen deterministically from the bundle ID. */
    private val PALETTE = longArrayOf(
        0xFF5B8DEF, 0xFF4CAF7D, 0xFFE0884B, 0xFFB46FD8, 0xFF4FB3BF, 0xFFD9667A, 0xFF8D9AAE, 0xFFC9A646,
    )

    fun resolve(bundleId: String?, displayName: String?, category: Category): AppInfo {
        KNOWN[bundleId]?.let { known ->
            // Prefer the iPhone's own (localized) display name when it's available.
            return if (displayName.isNullOrBlank()) known else known.copy(name = displayName)
        }
        val name = displayName?.takeIf { it.isNotBlank() } ?: AppLabels.fallback(bundleId)
        val color = PALETTE[Math.floorMod((bundleId ?: name).hashCode(), PALETTE.size)]
        return AppInfo(bundleId, name, color, kindFor(category))
    }

    /**
     * The app behind an AMS player name (iOS reports only the display name, e.g. "Spotify" or "Music").
     * Tries the catalog, then apps the watch has seen in notifications ([seenApps]: bundle ID → name),
     * so their App Store icons can be used too. Falls back to a monogram badge.
     */
    fun forPlayerName(name: String, seenApps: Map<String, String>): AppInfo {
        val clean = name.trim { it.isWhitespace() || it == '\u200E' || it == '\u200F' }
        KNOWN.values.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it }
        seenApps.entries.firstOrNull { it.value.trim { c -> c.isWhitespace() || c == '\u200E' || c == '\u200F' }.equals(clean, ignoreCase = true) }
            ?.let { return resolve(it.key, it.value, Category.ENTERTAINMENT) }
        return resolve(null, clean.ifBlank { "iPhone" }, Category.ENTERTAINMENT)
    }

    fun kindFor(category: Category): AppKind = when (category) {
        Category.SOCIAL -> AppKind.MESSAGING
        Category.EMAIL -> AppKind.EMAIL
        Category.BUSINESS_AND_FINANCE -> AppKind.FINANCE
        Category.INCOMING_CALL, Category.MISSED_CALL, Category.VOICEMAIL, Category.ACTIVE_CALL -> AppKind.CALLS
        Category.SCHEDULE -> AppKind.PRODUCTIVITY
        Category.ENTERTAINMENT -> AppKind.MEDIA
        Category.LOCATION -> AppKind.NAVIGATION
        else -> AppKind.OTHER
    }
}
