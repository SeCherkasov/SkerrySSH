package app.skerry.shared.ai

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiPolicyTest {

    @Test
    fun `off disables ai entirely`() {
        val d = AiPolicyDecision.of(AiPolicy.Off)
        assertFalse(d.aiEnabled)
        assertFalse(d.cloudAllowed)
    }

    @Test
    fun `strict enables ai but blocks cloud and sanitizes`() {
        val d = AiPolicyDecision.of(AiPolicy.Strict)
        assertTrue(d.aiEnabled)
        assertFalse(d.cloudAllowed, "Strict is local-only; no local provider yet → cloud blocked")
        assertTrue(d.sanitizeSecrets)
    }

    @Test
    fun `balanced allows cloud with sanitization`() {
        val d = AiPolicyDecision.of(AiPolicy.Balanced)
        assertTrue(d.aiEnabled)
        assertTrue(d.cloudAllowed)
        assertTrue(d.sanitizeSecrets)
    }

    @Test
    fun `permissive allows cloud without sanitization`() {
        val d = AiPolicyDecision.of(AiPolicy.Permissive)
        assertTrue(d.aiEnabled)
        assertTrue(d.cloudAllowed)
        assertFalse(d.sanitizeSecrets)
    }

    @Test
    fun `serializes by name so ordinal reordering keeps compatibility`() {
        val json = Json
        assertEquals("\"Balanced\"", json.encodeToString(AiPolicy.serializer(), AiPolicy.Balanced))
        assertEquals(AiPolicy.Off, json.decodeFromString(AiPolicy.serializer(), "\"Off\""))
    }
}
