package app.skerry.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shtail_group_unnamed
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The folder sections a list draws once anything in it is filed. What matters is the fold: a header
 * that hides its own rows, leaves the rest of the list on screen, and files its state under a key
 * that belongs to this list alone.
 */
@OptIn(ExperimentalTestApi::class)
class FolderSectionsTest {

    private data class Row(val id: String, val group: String?)

    /**
     * [FolderCollapse] over a set held as Compose state — the design states hold theirs the same
     * way, and a plain set would never make the list recompose.
     */
    private class Collapse(initial: Set<String> = emptySet()) : FolderCollapse {
        var collapsed: Set<String> by mutableStateOf(initial)
            private set

        override fun isGroupCollapsed(name: String): Boolean = name in collapsed
        override fun toggleGroupCollapsed(name: String) {
            collapsed = if (name in collapsed) collapsed - name else collapsed + name
        }
    }

    private val rows = listOf(Row("web-01", "Production"), Row("db-01", "Production"), Row("laptop", null))

    /** A Column, like every call site: the sections are emitted into the caller's layout. */
    @Composable
    private fun sections(items: List<Row>, scope: String, collapse: Collapse) {
        Column {
            FolderSections(items, scope = scope, collapse = collapse, group = { it.group }, itemKey = { it.id }) {
                Txt(it.id)
            }
        }
    }

    @Test
    fun `a header folds its own section and leaves the rest of the list alone`() {
        val collapse = Collapse()
        runForm({ sections(rows, "test", collapse) }) {
            onNodeWithText("Production").assertIsDisplayed()
            // The header counts what the folder holds.
            onNodeWithText("2").assertIsDisplayed()
            onNodeWithText("web-01").assertIsDisplayed()

            onNodeWithText("Production").performClick()
            waitForIdle()

            onNodeWithText("web-01").assertDoesNotExist()
            onNodeWithText("db-01").assertDoesNotExist()
            // The folded folder keeps its header, and the bucket below it is untouched.
            onNodeWithText("Production").assertIsDisplayed()
            onNodeWithText("laptop").assertIsDisplayed()

            onNodeWithText("Production").performClick()
            waitForIdle()
            onNodeWithText("web-01").assertIsDisplayed()
        }
        assertEquals(emptySet(), collapse.collapsed)
    }

    @Test
    fun `the fold is filed under this list's key, not the folder's name`() {
        val collapse = Collapse()
        runForm({ sections(rows, "snippet", collapse) }) {
            onNodeWithText("Production").performClick()
            waitForIdle()
        }
        // Not "Production": a folder of hosts by that name must not fold with it.
        assertEquals(setOf(folderCollapseKey("snippet", "Production")), collapse.collapsed)
    }

    @Test
    fun `a list with nothing filed stays flat`() {
        runForm({ sections(listOf(Row("web-01", null), Row("laptop", "")), "test", Collapse()) }) {
            // No "Ungrouped" header over a library that has never used a folder.
            onNodeWithText("Ungrouped").assertDoesNotExist()
            onNodeWithText("web-01").assertIsDisplayed()
            onNodeWithText("laptop").assertIsDisplayed()
        }
    }

    /**
     * The other place a folder name is drawn. A name written by a client that never normalized it
     * reaches the header verbatim, and the header is a row the user reads to tell folders apart.
     */
    @Test
    fun `a header draws a hostile name filtered and a nameless one as such`() {
        val hostile = listOf(Row("a", "\u202Eacme"), Row("b", "\u200B\u200B"))
        runForm({ sections(hostile, "test", Collapse()) }) {
            onNodeWithText("acme").assertIsDisplayed()
            onNodeWithText("\u202Eacme").assertDoesNotExist()
            // A name that filters away to nothing would otherwise draw a blank header with a count.
            onNodeWithText(string(Res.string.shtail_group_unnamed)).assertIsDisplayed()
        }
    }

    @Test
    fun `a collapsed folder opens collapsed`() {
        runForm({ sections(rows, "test", Collapse(setOf(folderCollapseKey("test", "Production")))) }) {
            onNodeWithText("web-01").assertDoesNotExist()
            onNodeWithText("Production").assertIsDisplayed()
        }
    }

    @Test
    fun `collapseGroup collapses an expanded group and is idempotent`() {
        val collapse = Collapse()
        collapse.collapseGroup("Production")
        assertEquals(setOf("Production"), collapse.collapsed)
        // Idempotent: collapsing again does not toggle it back to expanded
        collapse.collapseGroup("Production")
        assertEquals(setOf("Production"), collapse.collapsed)

        collapse.expandGroup("Production")
        assertEquals(emptySet(), collapse.collapsed)
        collapse.expandGroup("Production")
        assertEquals(emptySet(), collapse.collapsed)
    }
}
