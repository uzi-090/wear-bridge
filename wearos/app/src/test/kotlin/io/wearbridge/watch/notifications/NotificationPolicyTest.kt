package io.wearbridge.watch.notifications

import io.wearbridge.ancs.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyTest {

    private fun decide(
        category: Category,
        kind: AppKind = AppCatalog.kindFor(category),
        rule: AppRule = AppRule(),
        mode: FilterMode = FilterMode.ALL,
        important: Boolean = false,
    ) = NotificationPolicy.decide(category, important, kind, rule, mode)

    @Test
    fun `all mode surfaces everything`() {
        val d = decide(Category.OTHER)
        assertTrue(d.keep && d.surface && d.vibrate)
        assertFalse(d.mask)
    }

    @Test
    fun `disabled app is dropped entirely`() {
        val d = decide(Category.SOCIAL, rule = AppRule(enabled = false))
        assertFalse(d.keep || d.surface)
    }

    @Test
    fun `calls only keeps others in the inbox quietly`() {
        assertTrue(decide(Category.INCOMING_CALL, mode = FilterMode.CALLS_ONLY).surface)
        assertTrue(decide(Category.ACTIVE_CALL, mode = FilterMode.CALLS_ONLY).surface)
        val other = decide(Category.SOCIAL, mode = FilterMode.CALLS_ONLY)
        assertTrue(other.keep)
        assertFalse(other.surface)
    }

    @Test
    fun `messages and calls mode`() {
        assertTrue(decide(Category.SOCIAL, mode = FilterMode.MESSAGES_AND_CALLS).surface)
        assertTrue(decide(Category.EMAIL, mode = FilterMode.MESSAGES_AND_CALLS).surface)
        assertTrue(decide(Category.OTHER, kind = AppKind.MESSAGING, mode = FilterMode.MESSAGES_AND_CALLS).surface)
        assertFalse(decide(Category.ENTERTAINMENT, mode = FilterMode.MESSAGES_AND_CALLS).surface)
    }

    @Test
    fun `important only uses the iOS important flag`() {
        assertFalse(decide(Category.NEWS, mode = FilterMode.IMPORTANT).surface)
        assertTrue(decide(Category.NEWS, mode = FilterMode.IMPORTANT, important = true).surface)
        assertTrue(decide(Category.INCOMING_CALL, mode = FilterMode.IMPORTANT).surface)
    }

    @Test
    fun `finance is masked by default and the rule can override`() {
        assertTrue(decide(Category.BUSINESS_AND_FINANCE).mask)
        assertTrue(decide(Category.OTHER, kind = AppKind.FINANCE).mask)
        assertFalse(decide(Category.BUSINESS_AND_FINANCE, rule = AppRule(hideContent = false)).mask)
        assertTrue(decide(Category.SOCIAL, rule = AppRule(hideContent = true)).mask)
    }

    @Test
    fun `calls are never masked`() {
        assertFalse(decide(Category.INCOMING_CALL, rule = AppRule(hideContent = true)).mask)
    }

    @Test
    fun `mute keeps the notification but without vibration`() {
        val d = decide(Category.SOCIAL, rule = AppRule(vibrate = false))
        assertTrue(d.surface)
        assertFalse(d.vibrate)
    }

    @Test
    fun `catalog prefers iOS display name and falls back deterministically`() {
        assertEquals("WhatsApp", AppCatalog.resolve("net.whatsapp.WhatsApp", null, Category.SOCIAL).name)
        assertEquals("WhatsApp Messenger", AppCatalog.resolve("net.whatsapp.WhatsApp", "WhatsApp Messenger", Category.SOCIAL).name)
        val a = AppCatalog.resolve("com.example.unknown", "Unknown App", Category.OTHER)
        val b = AppCatalog.resolve("com.example.unknown", "Unknown App", Category.OTHER)
        assertEquals(a, b)
        assertEquals("U", a.initial)
        assertEquals(AppKind.FINANCE, AppCatalog.resolve("com.bank.x", "Bank", Category.BUSINESS_AND_FINANCE).kind)
        assertNotEquals(0L, a.color)
    }

    @Test
    fun `badge initial handles emoji and digits`() {
        assertEquals("•", AppInfo(null, "🙂", 0, AppKind.OTHER).initial)
        assertEquals("8", AppInfo(null, "811", 0, AppKind.OTHER).initial)
    }
}
