package io.wearbridge.watch.notifications

import io.wearbridge.ancs.Category

/** What kind of app a notification came from. It drives default filtering and masking. */
enum class AppKind { MESSAGING, EMAIL, SOCIAL, WORK, CALLS, FINANCE, PRODUCTIVITY, MEDIA, NAVIGATION, SHOPPING, SYSTEM, OTHER }

/**
 * Display metadata for an iOS app. [color] is an ARGB accent for the monogram badge. Bridge ships
 * no third-party logos (trademark risk R-13), so an app is shown as its initial on its accent colour.
 */
data class AppInfo(
    val bundleId: String?,
    val name: String,
    val color: Long,
    val kind: AppKind,
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
    private fun e(id: String, name: String, color: Long, kind: AppKind) = id to AppInfo(id, name, color, kind)

    private val KNOWN: Map<String, AppInfo> = mapOf(
        // Apple
        e("com.apple.MobileSMS", "Messages", 0xFF34C759, AppKind.MESSAGING),
        e("com.apple.mobilephone", "Phone", 0xFF34C759, AppKind.CALLS),
        e("com.apple.facetime", "FaceTime", 0xFF34C759, AppKind.CALLS),
        e("com.apple.mobilemail", "Mail", 0xFF1E88E5, AppKind.EMAIL),
        e("com.apple.mobilecal", "Calendar", 0xFFFF3B30, AppKind.PRODUCTIVITY),
        e("com.apple.reminders", "Reminders", 0xFFFF9500, AppKind.PRODUCTIVITY),
        e("com.apple.Preferences", "Settings", 0xFF8E8E93, AppKind.SYSTEM),
        // Messaging
        e("net.whatsapp.WhatsApp", "WhatsApp", 0xFF25D366, AppKind.MESSAGING),
        e("net.whatsapp.WhatsAppSMB", "WhatsApp Business", 0xFF25D366, AppKind.MESSAGING),
        e("ph.telegra.Telegraph", "Telegram", 0xFF2AABEE, AppKind.MESSAGING),
        e("org.whispersystems.signal", "Signal", 0xFF3A76F0, AppKind.MESSAGING),
        e("com.facebook.Messenger", "Messenger", 0xFF0084FF, AppKind.MESSAGING),
        // Social
        e("com.burbn.instagram", "Instagram", 0xFFE1306C, AppKind.SOCIAL),
        e("com.atebits.Tweetie2", "X", 0xFF2F3336, AppKind.SOCIAL),
        e("com.hammerandchisel.discord", "Discord", 0xFF5865F2, AppKind.SOCIAL),
        e("com.linkedin.LinkedIn", "LinkedIn", 0xFF0A66C2, AppKind.SOCIAL),
        // Email and work
        e("com.google.Gmail", "Gmail", 0xFFEA4335, AppKind.EMAIL),
        e("com.microsoft.Office.Outlook", "Outlook", 0xFF0078D4, AppKind.EMAIL),
        e("com.tinyspeck.chatlyio", "Slack", 0xFF611F69, AppKind.WORK),
        e("com.microsoft.skype.teams", "Teams", 0xFF6264A7, AppKind.WORK),
        e("com.google.Dynamite", "Google Chat", 0xFF00AC47, AppKind.WORK),
        // Media and navigation
        e("com.spotify.client", "Spotify", 0xFF1DB954, AppKind.MEDIA),
        e("com.google.ios.youtube", "YouTube", 0xFFFF0000, AppKind.MEDIA),
        e("com.google.Maps", "Google Maps", 0xFF34A853, AppKind.NAVIGATION),
        // Seen on the test iPhone (India)
        e("com.kotak811mobilebankingapp.instantsavingsupiscanandpayrecharge", "Kotak 811", 0xFFED1C24, AppKind.FINANCE),
        e("com.BhartiMobile.myairtel", "Airtel Thanks", 0xFFE40000, AppKind.OTHER),
        e("bundl.swiggy", "Swiggy", 0xFFFC8019, AppKind.SHOPPING),
        e("com.appflipkart.flipkart", "Flipkart", 0xFF2874F0, AppKind.SHOPPING),
        e("olacabs.OlaCabs", "Ola", 0xFF35B34A, AppKind.NAVIGATION),
        e("bike.rapido.customer", "Rapido", 0xFFF9C935, AppKind.NAVIGATION),
        e("com.eazydiner.edapp", "EazyDiner", 0xFFE53935, AppKind.SHOPPING),
        e("com.zeeTV.DIDS4", "ZEE5", 0xFF8230C6, AppKind.MEDIA),
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
