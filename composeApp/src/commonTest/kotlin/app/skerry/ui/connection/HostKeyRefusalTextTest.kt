package app.skerry.ui.connection

import app.skerry.shared.ssh.HostKeyRefusal
import app.skerry.shared.ssh.SshHostKeyRejectedException
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_err_hostkey_cert
import app.skerry.ui.generated.resources.conn_err_hostkey_changed
import app.skerry.ui.generated.resources.conn_err_hostkey_locked
import app.skerry.ui.generated.resources.conn_err_hostkey_untrusted
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostKeyRefusalTextTest {

    @Test
    fun `each reason gets its own line`() = runTest {
        // The mapping is the whole feature: swapping two branches compiles, passes every verifier
        // test, and tells a user with a locked vault that their certificate was rejected.
        assertEquals(Res.string.conn_err_hostkey_changed, hostKeyRefusalText(HostKeyRefusal.KeyChanged))
        assertEquals(Res.string.conn_err_hostkey_untrusted, hostKeyRefusalText(HostKeyRefusal.NotTrustedYet))
        assertEquals(Res.string.conn_err_hostkey_locked, hostKeyRefusalText(HostKeyRefusal.TrustStoreUnreadable))
        assertEquals(Res.string.conn_err_hostkey_cert, hostKeyRefusalText(HostKeyRefusal.CertificateRejected))
    }

    @Test
    fun `no two reasons read the same`() = runTest {
        val lines = HostKeyRefusal.entries.map { getString(hostKeyRefusalText(it)) }

        assertEquals(lines.size, lines.toSet().size, "two reasons share a line: $lines")
        assertTrue(lines.none { it.isBlank() })
    }

    @Test
    fun `a hop's refusal says the jump host, not the target`() = runTest {
        val target = hostKeyRefusalLine(SshHostKeyRejectedException("x", HostKeyRefusal.KeyChanged))
        val hop = hostKeyRefusalLine(SshHostKeyRejectedException("x", HostKeyRefusal.KeyChanged, hop = true))

        assertEquals(getString(Res.string.conn_err_hostkey_changed), target)
        assertTrue(hop != null && hop != target, "a hop rejection must not read like the target's")
        assertTrue(hop.contains(getString(Res.string.conn_err_hostkey_changed)), "the reason itself is kept")
    }

    @Test
    fun `a rejection with no reason has no line of its own`() = runTest {
        assertNull(hostKeyRefusalLine(SshHostKeyRejectedException("x")))
    }
}
