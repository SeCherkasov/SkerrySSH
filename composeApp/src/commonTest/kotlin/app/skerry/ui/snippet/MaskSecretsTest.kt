package app.skerry.ui.snippet

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a confirmation may draw of text carrying resolved vault secrets (issue #246). The tail rule
 * exists for strings the classifier CUT mid-secret: an exact replace cannot see a truncated span,
 * and the alternative printed the secret's head in the guard's aside in clear.
 */
class MaskSecretsTest {

    @Test
    fun `a full secret span is replaced with the mask`() {
        assertEquals(
            "echo $SECRET_MASK | sudo -S true",
            maskSecrets("echo hunter2 | sudo -S true", listOf("hunter2")),
        )
    }

    @Test
    fun `a string cut mid-secret masks the trailing prefix`() {
        assertEquals("sudo -S $SECRET_MASK", maskSecrets("sudo -S hunt", listOf("hunter2")))
    }

    @Test
    fun `text without the secret is left alone`() {
        assertEquals("uptime", maskSecrets("uptime", listOf("hunter2")))
    }

    @Test
    fun `overlapping secrets are masked longest first`() {
        // Shorter-first would mask only the embedded span and print the longer secret's flanks.
        assertEquals(
            "echo $SECRET_MASK and $SECRET_MASK",
            maskSecrets("echo tok_abc123 and tok_abc", listOf("tok_abc", "tok_abc123")),
        )
    }

    @Test
    fun `blank secrets mask nothing`() {
        assertEquals("echo x", maskSecrets("echo x", listOf("", "  ")))
    }
}
