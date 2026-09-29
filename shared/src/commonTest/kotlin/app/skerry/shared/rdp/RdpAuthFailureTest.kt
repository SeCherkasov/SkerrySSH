package app.skerry.shared.rdp

import app.skerry.shared.rdp.nla.credSspFailure
import kotlin.test.Test
import kotlin.test.assertEquals

class RdpAuthFailureTest {

    @Test
    fun `credssp status codes map to the reason the user can act on`() {
        val expected = mapOf(
            0xC000006D.toInt() to RdpAuthFailure.Credentials,
            0xC0000072.toInt() to RdpAuthFailure.AccountRestricted,
            0xC0000234.toInt() to RdpAuthFailure.AccountRestricted,
            0xC0000193.toInt() to RdpAuthFailure.AccountRestricted,
            0xC0000071.toInt() to RdpAuthFailure.PasswordExpired,
            0xC0000224.toInt() to RdpAuthFailure.PasswordExpired,
            0xC000015B.toInt() to RdpAuthFailure.LogonNotAllowed,
            0xC0000001.toInt() to RdpAuthFailure.ServerRefused,
        )
        for ((code, reason) in expected) {
            assertEquals(reason, credSspFailure(code), "0x${code.toUInt().toString(16)}")
        }
    }

    @Test
    fun `set error info codes map to the reason the user can act on`() {
        // Codes per MS-RDPBCGR 2.2.5.1.1, cross-checked against FreeRDP's ERRINFO_* table.
        val expected = mapOf(
            0x00000009 to RdpAuthFailure.LogonNotAllowed, // SERVER_INSUFFICIENT_PRIVILEGES
            0x00000100 to RdpAuthFailure.License, // LICENSE_INTERNAL
            0x00000101 to RdpAuthFailure.License, // LICENSE_NO_LICENSE_SERVER
            0x0000010A to RdpAuthFailure.License, // LICENSE_NO_REMOTE_CONNECTIONS
            0x00000007 to RdpAuthFailure.ServerRefused, // SERVER_DENIED_CONNECTION
            0x0000000C to RdpAuthFailure.ServerRefused, // LOGOFF_BY_USER
            0x00000010 to RdpAuthFailure.ServerRefused, // SERVER_DWM_CRASH
            0x00001234 to RdpAuthFailure.ServerRefused,
        )
        for ((code, reason) in expected) {
            assertEquals(reason, rdpErrorInfoFailure(code), "0x${code.toString(16)}")
        }
    }

    @Test
    fun `set error info text names the code the spec gives it`() {
        assertEquals("the account does not have permission to log on remotely", rdpErrorInfoText(0x09))
        assertEquals("the user logged off", rdpErrorInfoText(0x0C))
        assertEquals("the server denied the connection", rdpErrorInfoText(0x07))
        assertEquals("the server ended the session (0x10)", rdpErrorInfoText(0x10))
    }

    @Test
    fun `a set error info exception keeps the server's wording for the log`() {
        val failure = rdpErrorInfoException(0x09)

        assertEquals(RdpAuthFailure.LogonNotAllowed, failure.reason)
        assertEquals("the account does not have permission to log on remotely", failure.message)
    }
}
