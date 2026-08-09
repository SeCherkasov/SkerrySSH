package app.skerry.ui.host

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.runDesktopShell
import kotlin.test.Test

/**
 * The sidebar's folder dialog. A group with no name is the failure worth guarding: the folder would
 * appear in the sidebar as a blank row nothing can be dragged out of again.
 */
@OptIn(ExperimentalTestApi::class)
class HostGroupDialogTest {

    @Test
    fun `a folder needs a name before it can be created`() = runDesktopShell {
        onNodeWithTag(UiTags.NEW_GROUP).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

        onNodeWithTag(UiTags.FORM_FIELD).performTextInput(GROUP)
        onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled()
    }

    /** Whitespace is not a name: the dialog must not take a folder called "   ". */
    @Test
    fun `a blank name is refused`() = runDesktopShell {
        onNodeWithTag(UiTags.NEW_GROUP).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.FORM_FIELD).performTextInput("   ")
        onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
    }

    @Test
    fun `cancelling closes the dialog`() = runDesktopShell {
        onNodeWithTag(UiTags.NEW_GROUP).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.FORM_FIELD).performTextInput(GROUP)
        onNodeWithTag(UiTags.FORM_CANCEL).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.FORM_FIELD).assertDoesNotExist()
    }
}

private const val GROUP = "Staging"
