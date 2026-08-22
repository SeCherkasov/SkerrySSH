package app.skerry.ui.vault

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which pointer events count as a person at the desk.
 *
 * Compose re-sends the last mouse event as a [PointerEventType.Move] at its old position after a
 * relayout, so that a control sliding under a resting cursor still gets its hover. A terminal
 * printing output relayouts on every batch: if those counted, a chatty host would answer "still
 * here" on the user's behalf and the vault would never lock (issue #291's fix, reviewed).
 */
class PointerActivityTest {

    private val activity = PointerActivity()

    @Test
    fun `a mouse that moved is the user`() {
        activity.isFromUser(mouseMove(at = Offset(4f, 4f)))

        assertTrue(activity.isFromUser(mouseMove(at = Offset(10f, 10f))))
    }

    @Test
    fun `a mouse move repeating the last position is Compose talking to itself`() {
        activity.isFromUser(mouseMove(at = Offset(10f, 10f)))

        assertFalse(
            activity.isFromUser(mouseMove(at = Offset(10f, 10f))),
            "a relayout's re-sent move counted as activity",
        )
    }

    /** The move Compose re-sends after a press repeats that press's position, not a move's. */
    @Test
    fun `the position a press left behind is remembered too`() {
        activity.isFromUser(mousePress(at = Offset(10f, 10f)))

        assertFalse(activity.isFromUser(mouseMove(at = Offset(10f, 10f))))
    }

    /** Nothing but a Move is ever fabricated: a press or a wheel notch is always a hand. */
    @Test
    fun `a press at the same position is still the user`() {
        activity.isFromUser(mouseMove(at = Offset(10f, 10f)))

        assertTrue(activity.isFromUser(mousePress(at = Offset(10f, 10f))))
    }

    /**
     * Only mouse pointers are re-sent. A finger reporting the same position twice is a finger held
     * on the glass — Compose has no reason to invent it, and it is a person.
     */
    @Test
    fun `a finger repeating its position is the user`() {
        activity.isFromUser(move(at = Offset(10f, 10f), pointer = PointerType.Touch))

        assertTrue(activity.isFromUser(move(at = Offset(10f, 10f), pointer = PointerType.Touch)))
    }

    /**
     * The remembered position has to survive everything that is not a mouse: a hybrid device
     * interleaving a finger with the mouse must not hand the next re-sent move through as a person.
     */
    @Test
    fun `a touch in between does not make the mouse forget where it was`() {
        activity.isFromUser(mouseMove(at = Offset(10f, 10f)))
        activity.isFromUser(move(at = Offset(80f, 80f), pointer = PointerType.Touch))

        assertFalse(
            activity.isFromUser(mouseMove(at = Offset(10f, 10f))),
            "a re-sent move counted as activity after a touch event",
        )
    }

    /** Guards the assumption the filter is built on: these events really do report no movement. */
    @Test
    fun `a hover move claims nothing moved, whether it did or not`() {
        val event = mouseMove(at = Offset(10f, 10f))

        assertEquals(PointerEventType.Move, event.type)
        assertEquals(event.changes.single().position, event.changes.single().previousPosition)
    }

    private fun mouseMove(at: Offset) = move(at, PointerType.Mouse)

    private fun move(at: Offset, pointer: PointerType) = PointerEvent(listOf(change(at, pointer, pressed = false)))

    private fun mousePress(at: Offset) =
        PointerEvent(listOf(change(at, PointerType.Mouse, pressed = true)))

    /**
     * A change as the framework builds it for a pointer that is not being tracked between events —
     * which is every pointer with no button down (`PointerInputChangeEventProducer.produce`).
     */
    private fun change(at: Offset, pointer: PointerType, pressed: Boolean) = PointerInputChange(
        id = PointerId(1L),
        uptimeMillis = 2L,
        position = at,
        pressed = pressed,
        previousUptimeMillis = 1L,
        previousPosition = at,
        previousPressed = false,
        isInitiallyConsumed = false,
        type = pointer,
    )
}
