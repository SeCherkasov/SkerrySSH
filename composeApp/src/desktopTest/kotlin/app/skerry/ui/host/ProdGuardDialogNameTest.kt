package app.skerry.ui.host

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import app.skerry.shared.ai.CommandRiskReason
import app.skerry.shared.host.Host
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.guard_prod_connect_message
import app.skerry.ui.generated.resources.guard_prod_snippet_message
import app.skerry.ui.generated.resources.risk_recursive_force_delete
import kotlin.test.Test

/**
 * The name a confirmation dialog puts in front of the user.
 *
 * A dialog is where a host name stops being decoration: "this host is tagged #prod" is the last
 * line read before a session opens on production, and the snippet variant is read before a command
 * runs there by itself. Both name the host from a profile a team member may have written, so both
 * draw it the way the catalog row does.
 *
 * Written as escapes, never as the characters themselves.
 */
@OptIn(ExperimentalTestApi::class)
class ProdGuardDialogNameTest {

    @Test
    fun `the connect confirmation names the host flattened`() = runForm({
        ProdConnectDialog(ProdConnectRequest(host(), snippetLine = null) {}, onDismiss = {})
    }) {
        onNodeWithText(string(Res.string.guard_prod_connect_message, FLATTENED)).assertIsDisplayed()
        onNodeWithText(string(Res.string.guard_prod_connect_message, SPOOFED)).assertDoesNotExist()
    }

    /** The branch that matters more: the command runs the moment the session opens. */
    @Test
    fun `the snippet confirmation names the host flattened too`() = runForm({
        ProdConnectDialog(ProdConnectRequest(host(), snippetLine = "systemctl restart api") {}, onDismiss = {})
    }) {
        onNodeWithText(string(Res.string.guard_prod_snippet_message, FLATTENED)).assertIsDisplayed()
        onNodeWithText(string(Res.string.guard_prod_snippet_message, SPOOFED)).assertDoesNotExist()
    }

    /**
     * The risk caption comes from the caller, classified over the REAL line: the drawn line may
     * carry masked vault secrets, and classifying the masked text would lose a reason that lives
     * inside the resolved span (issue #246).
     */
    @Test
    fun `the snippet confirmation draws the risk the caller classified`() = runForm({
        ProdConnectDialog(
            ProdConnectRequest(
                host(),
                // The whole command resolved from the vault and masked: classifying THIS string
                // finds nothing, so a regression back to classifying the drawn line loses the
                // caption and fails the assertion below.
                snippetLine = "\u2022\u2022\u2022\u2022\u2022\u2022",
                snippetRisk = CommandRiskReason.RecursiveForceDelete,
            ) {},
            onDismiss = {},
        )
    }) {
        onNodeWithText(string(Res.string.risk_recursive_force_delete)).assertIsDisplayed()
    }
}

private fun host(): Host =
    Host(id = "h-prod", label = SPOOFED, address = "10.0.0.7", port = 22, username = "root", tags = listOf("prod"))

/** U+202E before the tail: drawn as `db-prod-staging` unless the dialog filters it. */
private const val SPOOFED = "db-prod\u202Egnigats-"

private const val FLATTENED = "db-prodgnigats-"
