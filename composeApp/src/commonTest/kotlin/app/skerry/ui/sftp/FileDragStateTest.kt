package app.skerry.ui.sftp

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val LOCAL_BOUNDS = Rect(0f, 0f, 400f, 600f)
private val REMOTE_BOUNDS = Rect(400f, 0f, 800f, 600f)
private val OVER_LOCAL = Offset(100f, 300f)
private val OVER_REMOTE = Offset(600f, 300f)
private val OUTSIDE = Offset(900f, 700f)

private val ITEM = FileItem("app.log", "/var/log/app.log", FileItemType.File, 10, 0)

private fun laidOut(): FileDragState = FileDragState().apply {
    setBounds(ActivePane.Local, LOCAL_BOUNDS)
    setBounds(ActivePane.Remote, REMOTE_BOUNDS)
}

class FileDragStateTest {

    @Test
    fun `the other pane under the pointer is the target and the source pane never is`() {
        val drag = laidOut()
        drag.start(ActivePane.Remote, listOf(ITEM), OVER_REMOTE)
        assertNull(drag.target)

        drag.moveTo(OVER_LOCAL)
        assertEquals(ActivePane.Local, drag.target)

        drag.moveTo(OUTSIDE)
        assertNull(drag.target)
    }

    @Test
    fun `a drop on the other pane copies, and with shift held it moves`() {
        val drag = laidOut()
        drag.start(ActivePane.Remote, listOf(ITEM), OVER_REMOTE)
        drag.moveTo(OVER_LOCAL)
        assertEquals(FileDrop(ActivePane.Remote, listOf(ITEM), move = false), drag.drop())

        drag.start(ActivePane.Local, listOf(ITEM), OVER_LOCAL)
        drag.setShift(true)
        drag.moveTo(OVER_REMOTE)
        assertEquals(FileDrop(ActivePane.Local, listOf(ITEM), move = true), drag.drop())
    }

    @Test
    fun `releasing shift before the drop turns the move back into a copy`() {
        val drag = laidOut()
        drag.setShift(true)
        drag.start(ActivePane.Local, listOf(ITEM), OVER_LOCAL)
        assertTrue(drag.move)
        drag.moveTo(OVER_REMOTE)
        drag.setShift(false)
        assertEquals(FileDrop(ActivePane.Local, listOf(ITEM), move = false), drag.drop())
    }

    @Test
    fun `a drop back on the source pane or outside both does nothing`() {
        val drag = laidOut()
        drag.start(ActivePane.Local, listOf(ITEM), OVER_LOCAL)
        drag.moveTo(Offset(200f, 100f))
        assertNull(drag.drop())

        drag.start(ActivePane.Local, listOf(ITEM), OVER_LOCAL)
        drag.moveTo(OUTSIDE)
        assertNull(drag.drop())
    }

    @Test
    fun `a drop ends the drag, so a second release reports nothing`() {
        val drag = laidOut()
        drag.start(ActivePane.Remote, listOf(ITEM), OVER_REMOTE)
        drag.moveTo(OVER_LOCAL)
        drag.drop()

        assertFalse(drag.active)
        assertNull(drag.target)
        assertNull(drag.drop())
    }

    @Test
    fun `a cancelled drag drops nowhere even when released over the other pane`() {
        val drag = laidOut()
        drag.start(ActivePane.Remote, listOf(ITEM), OVER_REMOTE)
        drag.moveTo(OVER_LOCAL)
        drag.cancel()

        assertFalse(drag.active)
        drag.moveTo(OVER_LOCAL)
        assertNull(drag.target)
        assertNull(drag.drop())
    }

    @Test
    fun `a drag with nothing to carry never starts`() {
        val drag = laidOut()
        drag.start(ActivePane.Remote, emptyList(), OVER_REMOTE)
        drag.moveTo(OVER_LOCAL)

        assertFalse(drag.active)
        assertNull(drag.drop())
    }
}
