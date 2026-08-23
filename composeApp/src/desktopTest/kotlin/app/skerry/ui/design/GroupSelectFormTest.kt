package app.skerry.ui.design

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.skerry.shared.text.MAX_GROUP_LENGTH
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_create
import app.skerry.ui.generated.resources.conn_group_new
import app.skerry.ui.generated.resources.conn_group_none
import app.skerry.ui.generated.resources.shell_group_name_placeholder
import app.skerry.ui.mobile.MobileGroupCreateDialog
import app.skerry.ui.mobile.MobileGroupSelectField
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The "Group" select and the dialog behind its "New group…" — the two places a folder name is
 * chosen. What is checked here is what the picker owes the rest of the feature: a name it draws is
 * a name that cannot lie, and a name it creates is one the record can actually keep.
 */
@OptIn(ExperimentalTestApi::class)
class GroupSelectFormTest {

    /**
     * The header sanitizes a folder name because it can arrive over sync; the picker lists the same
     * names and had better sanitize them too, or the one place a folder is *chosen* is the one
     * place a right-to-left override can make it read as another.
     */
    @Test
    fun `a folder name written by another client is drawn stripped in the menu`() {
        runForm({ GroupSelectField(value = "", groups = listOf("\u202Eacme"), onChange = {}) }) {
            onNodeWithText(string(Res.string.conn_group_none)).performClick()
            waitForIdle()

            onNodeWithText("acme").assertExists()
            onNodeWithText("\u202Eacme").assertDoesNotExist()
        }
    }

    /** The phone draws the same names through the same filter, and had better keep doing so. */
    @Test
    fun `the phone's menu strips the same name`() {
        runForm({
            MobileGroupSelectField(value = "", groups = listOf("\u202Eacme"), onChange = {}, onCreateGroup = {})
        }) {
            onNodeWithText(string(Res.string.conn_group_none)).performClick()
            waitForIdle()

            onNodeWithText("acme").assertExists()
            onNodeWithText("\u202Eacme").assertDoesNotExist()
        }
    }

    /** A name longer than the record keeps would come back cut on the next open, unexplained. */
    @Test
    fun `the new group name is capped where the record caps it`() {
        var picked: String? = null
        runForm({ GroupSelectField(value = "", groups = emptyList(), onChange = { picked = it }) }) {
            onNodeWithText(string(Res.string.conn_group_none)).performClick()
            onNodeWithText(string(Res.string.conn_group_new)).performClick()
            waitForIdle()

            onNodeWithContentDescription(string(Res.string.shell_group_name_placeholder))
                .performTextInput("a".repeat(MAX_GROUP_LENGTH * 2))
            onNodeWithText(string(Res.string.conn_create)).performClick()
            waitForIdle()
        }
        assertEquals(MAX_GROUP_LENGTH, picked?.length)
    }

    /** The phone's dialog is a different composable and used to cap nothing at all. */
    @Test
    fun `the phone caps the new group name too`() {
        var created: String? = null
        runForm({ MobileGroupCreateDialog(onDismiss = {}, onCreate = { created = it }) }) {
            onNodeWithContentDescription(string(Res.string.shell_group_name_placeholder))
                .performTextInput("a".repeat(MAX_GROUP_LENGTH * 2))
            onNodeWithText(string(Res.string.conn_create)).performClick()
            waitForIdle()
        }
        assertEquals(MAX_GROUP_LENGTH, created?.length)
    }
}
