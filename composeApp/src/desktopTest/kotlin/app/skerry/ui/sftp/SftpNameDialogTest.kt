package app.skerry.ui.sftp

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.runForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The SFTP name dialog (New folder / Rename), rendered on its own: reaching it through the UI needs
 * a live connection and a remote listing.
 *
 * Its rules are about what would go wrong on the far side — a name that collides with an existing
 * entry silently overwrites, and a path separator escapes the directory the user is looking at.
 */
@OptIn(ExperimentalTestApi::class)
class SftpNameDialogTest {

    @Test
    fun `the typed name reaches the caller`() {
        var confirmed: String? = null
        runForm({ dialog(onConfirm = { confirmed = it }) }) {
            onNodeWithTag(UiTags.FORM_FIELD).performTextReplacement("deploy")
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals("deploy", confirmed)
    }

    /** A name already taken in this directory would overwrite what is there. */
    @Test
    fun `a name that already exists is refused`() {
        var confirmed: String? = null
        runForm({ dialog(onConfirm = { confirmed = it }, existing = setOf("logs")) }) {
            onNodeWithTag(UiTags.FORM_FIELD).performTextReplacement("logs")
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertNull(confirmed, "the dialog accepted a name that is already taken")
    }

    /** A separator in a file name is a path, and this dialog names one entry in one directory. */
    @Test
    fun `a name with a path separator is refused`() {
        var confirmed: String? = null
        runForm({ dialog(onConfirm = { confirmed = it }) }) {
            onNodeWithTag(UiTags.FORM_FIELD).performTextReplacement("../etc/passwd")
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertNull(confirmed, "the dialog accepted a path where a name belongs")
    }

    @Test
    fun `an empty name is refused`() {
        var confirmed: String? = null
        runForm({ dialog(onConfirm = { confirmed = it }, initial = "notes.md") }) {
            onNodeWithTag(UiTags.FORM_FIELD).performTextReplacement("")
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertNull(confirmed)
    }
}

@Composable
private fun dialog(
    onConfirm: (String) -> Unit,
    initial: String = "",
    existing: Set<String> = emptySet(),
) = NameDialog(
    title = "New folder",
    confirmLabel = "Create",
    initial = initial,
    onConfirm = onConfirm,
    onDismiss = {},
    existing = existing,
)
