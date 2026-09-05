package app.skerry.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shtail_group_unnamed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The folder sections a list draws once anything in it is filed. What matters is the fold: a header
 * that hides its own rows, leaves the rest of the list on screen, and files its state under a key
 * that belongs to this list alone.
 */
@OptIn(ExperimentalTestApi::class)
class FolderSectionsTest {

    private data class Row(val id: String, val group: String?)

    /** A row the way the libraries hold one: a holder whose folder can change under the same list. */
    private class Holder(val id: String, group: String?) {
        var group: String? by mutableStateOf(group)
    }

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

    /** Where a header sits on screen — the only way to read the order the sections came out in. */
    private fun ComposeUiTest.topOf(header: String): Float =
        onNodeWithText(header).fetchSemanticsNode().positionInRoot.y

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
    fun `a list with a manual order draws its folders in the order it holds them`() {
        // Passing a folder move is what says the list carries an order of its own: the sections then
        // follow the list, because that order is exactly what a folder drag writes. Sorting them by
        // name instead would redraw the list unchanged after every drag.
        val mixed = listOf(Row("a", "zebra"), Row("b", "Alpha"))

        runForm({
            Column {
                FolderSections(
                    mixed, scope = "test", collapse = Collapse(), group = { it.group }, itemKey = { it.id },
                    onMoveGroup = { _, _, _ -> },
                ) { Txt(it.id) }
            }
        }) {
            assertTrue(topOf("zebra") < topOf("Alpha"), "a dragged order must survive the redraw")
        }

        runForm({ sections(mixed, "test", Collapse()) }) {
            assertTrue(topOf("Alpha") < topOf("zebra"), "a list with no order of its own sorts by name")
        }
    }

    @Test
    fun `refiling a row from the editor moves it without the list itself changing`() {
        // The libraries hold each record in a mutable holder, so saving a new folder from the editor
        // rewrites the row in place and leaves the list the same object. Sections computed off the
        // list alone would keep drawing the row under the folder it left.
        val moved = Holder("web-01", "Production")
        val rows = listOf(moved, Holder("db-01", "Staging"))

        runForm({
            Column {
                FolderSections(
                    rows, scope = "test", collapse = Collapse(),
                    group = { it.group }, itemKey = { it.id },
                ) { Txt(it.id) }
            }
        }) {
            onNodeWithText("Production").assertIsDisplayed()

            moved.group = "Staging"
            waitForIdle()

            onNodeWithText("Production").assertDoesNotExist()
            assertTrue(topOf("Staging") < topOf("web-01"), "the row must be drawn under its new folder")
        }
    }
    /** The sections as a library draws them: rows and headers are drag surfaces. */
    @Composable
    private fun draggableSections(
        items: List<Holder>,
        collapse: Collapse,
        onMoveItem: (String, String?, Int, Set<String>) -> Unit = { _, _, _, _ -> },
        onMoveGroup: (String?, Int, Set<String>) -> Unit = { _, _, _ -> },
    ) {
        Column {
            FolderSections(
                items, scope = "drag", collapse = collapse, group = { it.group }, itemKey = { it.id },
                onMoveItem = onMoveItem, onMoveGroup = onMoveGroup,
            ) { Txt(it.id) }
        }
    }

    /**
     * Drags the node drawing [from] onto the one drawing [to], in steps a hand would make — the
     * gesture only claims the pointer once it is past the mouse dead zone.
     */
    private fun ComposeUiTest.drag(from: String, to: String) {
        val start = onNodeWithText(from).centerY()
        val target = onNodeWithText(to).centerY()
        onNodeWithText(from).performMouseInput {
            moveTo(center)
            press()
            repeat(DRAG_STEPS) { moveBy(Offset(0f, (target - start) / DRAG_STEPS)) }
            release()
        }
        waitForIdle()
    }

    private fun SemanticsNodeInteraction.centerY(): Float = fetchSemanticsNode().boundsInRoot.center.y

    @Test
    fun `a row dragged onto another folder is reported as filed under it`() {
        val items = listOf(Holder("web-01", "Production"), Holder("laptop", null))
        var move: Triple<String, String?, Int>? = null

        runForm({ draggableSections(items, Collapse(), onMoveItem = { id, group, index, _ -> move = Triple(id, group, index) }) }) {
            drag(from = "web-01", to = "laptop")

            assertEquals(Triple("web-01", null, 0), move, "a row dropped among the unfiled rows leaves its folder")
        }
    }

    /**
     * The gesture keeps the coroutine it launched on the row's first pointer event, so a folder list
     * captured there would be the one every later drag of that row answers with. A folder that
     * appeared since — a sync apply, or the row next to it being refiled — has to be a target too.
     */
    @Test
    fun `a folder that appeared after the row was first touched is still a drop target`() {
        val refiled = Holder("db-01", "Production")
        val items = listOf(Holder("web-01", "Production"), refiled, Holder("api-01", "Production"))
        var move: Triple<String, String?, Int>? = null

        runForm({ draggableSections(items, Collapse(), onMoveItem = { id, group, index, _ -> move = Triple(id, group, index) }) }) {
            // Starts the row's gesture coroutine without moving anything.
            onNodeWithText("api-01").performClick()
            waitForIdle()

            refiled.group = "Staging"
            waitForIdle()
            onNodeWithText("Staging").assertIsDisplayed()

            drag(from = "api-01", to = "db-01")

            assertEquals(Triple("api-01", "Staging", 0), move, "the drop belongs to the folder under the pointer")
        }
    }

    /**
     * The rows a drop index was counted over travel with it, because the caller may be showing a
     * filtered slice of a list whose order lives in the whole of it. They are read through the same
     * provider the target is resolved through, so a row that left the list mid-gesture is not still
     * reported as on screen — the frozen gesture coroutine would otherwise answer with the list the
     * row was first touched under.
     */
    @Test
    fun `the rows reported with a drop are the ones on screen when it happened`() {
        var items by mutableStateOf(
            listOf(Holder("web-01", "Production"), Holder("db-01", "Production"), Holder("laptop", null)),
        )
        var onScreen: Set<String>? = null

        runForm({ draggableSections(items, Collapse(), onMoveItem = { _, _, _, ids -> onScreen = ids }) }) {
            onNodeWithText("web-01").performClick()
            waitForIdle()

            // A search narrowing the list, or a record deleted on another device and synced here.
            items = items.filterNot { it.id == "db-01" }
            waitForIdle()

            drag(from = "web-01", to = "laptop")

            assertEquals(setOf("web-01", "laptop"), onScreen, "the row the list dropped must not be reported as drawn")
        }
    }

    @Test
    fun `a folder header dragged past its neighbour reports the index it landed on`() {
        val items = listOf(Holder("web-01", "Production"), Holder("db-01", "Staging"))
        var move: Pair<String?, Int>? = null

        runForm({ draggableSections(items, Collapse(), onMoveGroup = { group, index, _ -> move = group to index }) }) {
            drag(from = "Production", to = "Staging")

            assertEquals("Production" to 1, move, "a folder dropped below its neighbour comes second")
        }
    }

}

/** Enough steps that each one stays small, as a real drag's moves are. */
private const val DRAG_STEPS = 6
