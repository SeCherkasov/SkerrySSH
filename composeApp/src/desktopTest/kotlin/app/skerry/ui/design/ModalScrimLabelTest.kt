package app.skerry.ui.design

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import app.skerry.ui.desktop.runForm
import kotlin.test.Test

/**
 * A dialog with no field to autofocus leaves focus on [ModalScrim]'s own box, which is otherwise a
 * full-screen node with no name: opened that way it says nothing to anyone reading the screen
 * aloud. The three shared wrappers name it from their own title, so every dialog built on them
 * inherits that — which is exactly the thing a later edit could drop without failing anything else.
 */
@OptIn(ExperimentalTestApi::class)
class ModalScrimLabelTest {

    @Test
    fun `a confirmation names itself`() = runForm({
        ConfirmActionDialog(
            title = TITLE,
            message = "This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {},
            onDismiss = {},
        )
    }) {
        onNodeWithContentDescription(TITLE).assertIsDisplayed()
    }

    @Test
    fun `a notice names itself`() = runForm({
        NoticeDialog(title = TITLE, message = "The jump host refused the connection.", buttonLabel = "OK", onDismiss = {})
    }) {
        onNodeWithContentDescription(TITLE).assertIsDisplayed()
    }

    @Test
    fun `a help dialog names itself`() = runForm({
        HelpDialog(title = TITLE, closeLabel = "Close", onDismiss = {}) {}
    }) {
        onNodeWithContentDescription(TITLE).assertIsDisplayed()
    }

    private companion object {
        const val TITLE = "Delete this host?"
    }
}
