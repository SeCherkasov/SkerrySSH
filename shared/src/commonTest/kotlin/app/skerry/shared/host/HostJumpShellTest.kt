package app.skerry.shared.host

import app.skerry.shared.ssh.ConnectionType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a profile typed through its jump host's shell gives up: its own credential, a file session
 * and commands sent as the session opens — each would land on the jump host, not on this one.
 */
class HostJumpShellTest {

    private val direct = Host(id = "h", label = "db", address = "db", username = "me")
    private val viaShell = direct.copy(jumpHostId = "bastion", jumpViaShell = true)

    @Test
    fun `only an ssh profile with a jump host is reached through its shell`() {
        assertEquals(true, viaShell.reachedThroughJumpShell)
        assertEquals(false, viaShell.copy(jumpHostId = null).reachedThroughJumpShell)
        assertEquals(false, viaShell.copy(connectionType = ConnectionType.MOSH).reachedThroughJumpShell)
        assertEquals(false, direct.copy(jumpHostId = "bastion").reachedThroughJumpShell)
    }

    @Test
    fun `a profile typed through the jump host's shell needs no credential, files or typed commands`() {
        assertEquals(Triple(true, true, true), Triple(direct.needsOwnCredential, direct.opensFileSessions, direct.takesCommandsOnOpen))
        assertEquals(Triple(false, false, false), Triple(viaShell.needsOwnCredential, viaShell.opensFileSessions, viaShell.takesCommandsOnOpen))
        val telnet = direct.copy(connectionType = ConnectionType.TELNET)
        assertEquals(Triple(false, false, true), Triple(telnet.needsOwnCredential, telnet.opensFileSessions, telnet.takesCommandsOnOpen))
    }

    @Test
    fun `a team profile goes through its jump host end to end`() {
        // A peer who edits the shared profile would otherwise move every member's keystrokes and
        // the destination's host key check onto the jump host.
        assertEquals(false, viaShell.asTeamShared().reachedThroughJumpShell)
        assertEquals(viaShell.copy(jumpViaShell = false), viaShell.asTeamShared())
        assertEquals(direct, direct.asTeamShared())
    }
}
