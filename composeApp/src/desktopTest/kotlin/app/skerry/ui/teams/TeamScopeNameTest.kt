package app.skerry.ui.teams

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import app.skerry.ui.desktop.runForm
import kotlin.test.Test

/**
 * The name of a share space, drawn where the team screen lists them.
 *
 * A scope is named by whoever created it and the name reaches every member through the server,
 * which never inspects it: the chip that says which space the records below belong to is the one
 * line telling a shared production space from a shared staging one, and a bidi override in it
 * reverses exactly that.
 *
 * Written as escapes, never as the characters themselves.
 */
@OptIn(ExperimentalTestApi::class)
class TeamScopeNameTest {

    @Test
    fun `a scope chip draws its peer-authored name flattened`() = runForm({
        ScopeSection(
            scopes = listOf(TeamScopeUi(id = "s1", name = SPOOFED, memberCount = 2, hasKey = true)),
            selected = "s1",
            canManage = false,
            onSelect = {},
            onNew = {},
            onAccess = {},
            onDelete = {},
        )
    }) {
        onNodeWithText(FLATTENED).assertIsDisplayed()
        onNodeWithText(SPOOFED).assertDoesNotExist()
    }
}

/** U+202E before the tail: the chip draws as `prod-gnigats`'s mirror unless it is filtered. */
private const val SPOOFED = "prod-\u202Egnigats"

private const val FLATTENED = "prod-gnigats"
