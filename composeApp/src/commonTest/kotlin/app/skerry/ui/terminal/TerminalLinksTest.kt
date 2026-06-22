package app.skerry.ui.terminal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalLinksTest {

    @Test
    fun `accepts web schemes with authority`() {
        assertTrue(isSafeLinkUri("https://skerry.app/docs"))
        assertTrue(isSafeLinkUri("http://example.com"))
        assertTrue(isSafeLinkUri("ftp://files.example.com/x"))
        assertTrue(isSafeLinkUri("mailto:dev@skerry.app"))
    }

    @Test
    fun `scheme match is case-insensitive`() {
        assertTrue(isSafeLinkUri("HTTPS://Skerry.App"))
        assertTrue(isSafeLinkUri("MailTo:dev@skerry.app"))
    }

    @Test
    fun `rejects dangerous and non-web schemes`() {
        assertFalse(isSafeLinkUri("file:///etc/passwd"))
        assertFalse(isSafeLinkUri("javascript:alert(1)"))
        assertFalse(isSafeLinkUri("data:text/html,<script>"))
        assertFalse(isSafeLinkUri("ssh://root@host"))
    }

    @Test
    fun `rejects degenerate http without authority`() {
        assertFalse(isSafeLinkUri("http:"))
        assertFalse(isSafeLinkUri("https:evil"))
    }

    @Test
    fun `rejects uris carrying control characters`() {
        // Сервер мог бы вшить \n/\r для порчи диспетча URI на платформе.
        val nl = 10.toChar()
        val cr = 13.toChar()
        assertFalse(isSafeLinkUri("https://ok.test${nl}https://evil.test"))
        assertFalse(isSafeLinkUri("https://ok.test${cr}x"))
    }
}
