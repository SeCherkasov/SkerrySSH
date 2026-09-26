package app.skerry.ui.connection

import app.skerry.shared.jumpshell.JumpShellRefusedException.Reason
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_error_jump_shell_address
import app.skerry.ui.generated.resources.conn_error_jump_shell_disabled
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

class JumpShellRefusalTextTest {

    @Test
    fun `each reason points at its own fix`() = runTest {
        // Swapped, a malformed address would send the user to a setting that is already on.
        assertEquals(Res.string.conn_error_jump_shell_disabled, jumpShellRefusalText(Reason.DISABLED))
        assertEquals(Res.string.conn_error_jump_shell_address, jumpShellRefusalText(Reason.NOT_AN_ADDRESS))
        val lines = Reason.entries.map { getString(jumpShellRefusalText(it)) }
        assertEquals(lines.size, lines.toSet().size, "two reasons share a line: $lines")
    }
}
