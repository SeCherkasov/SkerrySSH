package app.skerry.ui.design

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldDraftTest {

    /** Records what a call site's `onValueChange` received, in order. */
    private class Sink {
        val texts = mutableListOf<String>()
        val take: (String) -> Unit = { texts += it }
    }

    @Test
    fun untouched_field_puts_the_caret_after_the_prefilled_text() {
        val draft = FieldDraft()
        draft.focused = true
        // The String overload of BasicTextField seeds TextRange.Zero, so typing into an autofocused
        // rename dialog used to prepend: "report.log" + "x" -> "xreport.log".
        assertEquals(TextRange(10), draft.textFieldValue("report.log").selection)
    }

    @Test
    fun focus_selects_the_whole_value_when_armed() {
        val draft = FieldDraft()
        draft.focusGained("report.log", selectAll = true)
        // The extension is part of the selection: renaming report.log to notes.txt replaces both.
        assertEquals(TextRange(0, 10), draft.textFieldValue("report.log").selection)
    }

    @Test
    fun focus_leaves_the_caret_alone_when_not_armed() {
        val draft = FieldDraft()
        draft.accept(TextFieldValue("2222", TextRange(2)), "2222") {}
        draft.focusGained("2222", selectAll = false)
        assertEquals(TextRange(2), draft.textFieldValue("2222").selection)
    }

    @Test
    fun an_empty_value_is_not_selected() {
        val draft = FieldDraft()
        draft.focusGained("", selectAll = true)
        assertTrue(draft.textFieldValue("").selection.collapsed)
    }

    @Test
    fun a_click_after_the_selection_collapses_it() {
        val draft = FieldDraft()
        draft.focusGained("22", selectAll = true)
        draft.accept(TextFieldValue("22", TextRange(1)), "22") {}
        assertEquals(TextRange(1), draft.textFieldValue("22").selection)
    }

    @Test
    fun a_caret_move_is_not_reported_as_an_edit() {
        val draft = FieldDraft()
        val sink = Sink()
        // A click moves the caret and nothing else. The String overload of BasicTextField filters
        // this out; forwarding it would re-run the caller's setter — clearing an error banner or
        // committing a setting — on a bare click.
        draft.accept(TextFieldValue("22", TextRange(1)), "22", sink.take)
        assertEquals(emptyList(), sink.texts)
        draft.accept(TextFieldValue("223", TextRange(3)), "22", sink.take)
        assertEquals(listOf("223"), sink.texts)
    }

    @Test
    fun the_ime_composition_survives_a_round_trip() {
        val draft = FieldDraft()
        // Dropping the composition makes EditProcessor commit it and restart the input session on
        // every keystroke, which breaks composing input (pinyin, predictive text).
        draft.accept(TextFieldValue("ni", TextRange(2), composition = TextRange(0, 2)), "") {}
        assertEquals(TextRange(0, 2), draft.textFieldValue("ni").composition)
    }

    @Test
    fun selecting_all_drops_a_stale_composition() {
        val draft = FieldDraft()
        draft.accept(TextFieldValue("ni", TextRange(2), composition = TextRange(0, 2)), "") {}
        draft.focusGained("ni", selectAll = true)
        assertNull(draft.textFieldValue("ni").composition)
    }

    @Test
    fun blur_forgets_the_caret() {
        val draft = FieldDraft()
        draft.focused = true
        draft.focusGained("3389", selectAll = true)
        assertEquals(TextRange(0, 4), draft.textFieldValue("3389").selection)
        draft.focusLost()
        draft.focused = false
        // Same text, so only the reset can explain the collapsed caret.
        assertTrue(draft.textFieldValue("3389").selection.collapsed)
    }

    @Test
    fun an_unfocused_field_keeps_the_caret_at_the_start() {
        val draft = FieldDraft()
        // The field scrolls to follow the caret even unfocused, so a value wider than its box would
        // open showing its tail. Only a focused field gets the caret moved to the end.
        assertEquals(TextRange(0), draft.textFieldValue("https://sync.example.com:8443").selection)
        draft.focused = true
        assertEquals(TextRange(29), draft.textFieldValue("https://sync.example.com:8443").selection)
    }

    @Test
    fun a_second_edit_before_the_caller_recomposes_is_still_reported() {
        val draft = FieldDraft()
        val sink = Sink()
        // Two emissions inside one frame: the caller's `current` is still the pre-edit value for
        // both, so comparing against it would drop the second one and lose the retyped character.
        draft.accept(TextFieldValue("lo", TextRange(2)), "log", sink.take)
        draft.accept(TextFieldValue("log", TextRange(3)), "log", sink.take)
        assertEquals(listOf("lo", "log"), sink.texts)
    }

    @Test
    fun an_edit_reproducing_the_last_emitted_text_survives_an_outside_replacement() {
        val draft = FieldDraft()
        val sink = Sink()
        // NumberStepper: the field holds "13", the user types "1", then the +/- button replaces the
        // value with "14" without taking focus. Backspace re-emits "1" — the text this field last
        // produced — and comparing against it would drop the keystroke, leaving the key dead.
        draft.accept(TextFieldValue("1", TextRange(1)), "13", sink.take)
        draft.accept(TextFieldValue("1", TextRange(1)), "14", sink.take)
        assertEquals(listOf("1", "1"), sink.texts)
    }

    @Test
    fun a_value_the_caller_rewrote_keeps_the_caret_where_it_was() {
        val draft = FieldDraft()
        draft.focused = true
        // Notes are capped at their limit on the way in (`capNotes`), snippet parameters are
        // sanitized. Typing in the middle of such a value emits one string and the caller stores
        // another, so the caret belongs to no text at all — sending it to the end would push every
        // following keystroke there too.
        draft.accept(TextFieldValue("abcXdef", TextRange(4)), "abcdef") {}
        assertEquals(TextRange(4), draft.textFieldValue("abcXde").selection)
    }

    @Test
    fun a_multi_line_field_opens_at_its_beginning() {
        val draft = FieldDraft(singleLine = false)
        draft.focused = true
        // A snippet command or a note is read before it is edited, so it opens the way a file does
        // in the editor — at the top, not scrolled to its last line.
        assertEquals(TextRange(0), draft.textFieldValue("cd /var/log\ntail -f syslog").selection)
    }

    @Test
    fun a_shorter_value_from_outside_keeps_the_caret_inside_it() {
        val draft = FieldDraft()
        draft.focused = true
        draft.focusGained("3389", selectAll = true)
        // Switching the protocol refills the port while the field keeps focus. Constraining the old
        // range would highlight "33" out of "22"; what survives is the offset, and only inside the
        // new text.
        assertEquals(TextRange(2), draft.textFieldValue("22").selection)
    }

    @Test
    fun a_longer_value_from_outside_starts_at_its_end() {
        val draft = FieldDraft()
        draft.focused = true
        draft.focusGained("22", selectAll = true)
        // SSH to RDP with the port field still focused. A caller only ever takes away from what it
        // was handed, so a value that grew came from somewhere else and is not the text this caret
        // was measured against: keeping offset 2 would leave "33|89" and turn a keystroke into 33589.
        assertEquals(TextRange(4), draft.textFieldValue("3389").selection)
    }

    @Test
    fun a_value_of_the_same_length_from_outside_starts_at_its_end() {
        val draft = FieldDraft()
        draft.focused = true
        // A tap places the caret and changes nothing else; whatever arrives after that came from
        // elsewhere. RDP to VNC refills 3389 with 5900 — the same width, so length alone would read
        // it as the field's own text coming back and leave the caret at "59|00".
        draft.accept(TextFieldValue("3389", TextRange(2)), "3389") {}
        assertEquals(TextRange(4), draft.textFieldValue("5900").selection)
    }

    @Test
    fun a_value_replaced_while_an_edit_was_in_flight_starts_at_its_end() {
        val draft = FieldDraft()
        draft.focused = true
        // The stepper: the field holds "13", the user types "1", and the + button replaces the value
        // with "14" without taking focus. The field did emit, but a value longer than what it emitted
        // cannot be that emission coming back.
        draft.accept(TextFieldValue("1", TextRange(1)), "13") {}
        assertEquals(TextRange(2), draft.textFieldValue("14").selection)
    }

    @Test
    fun an_edit_the_caller_refused_is_reported_again() {
        val draft = FieldDraft()
        val sink = Sink()
        // A caller may drop what it is handed instead of storing it — the web-access password field
        // ignores input while a save is in flight. Its value never moves, so measuring against the
        // text this field last emitted would make the retyped character equal to "no change" and
        // the key would stay dead for as long as the field lives.
        draft.accept(TextFieldValue("x", TextRange(1)), "", sink.take)
        draft.accept(TextFieldValue("x", TextRange(1)), "", sink.take)
        assertEquals(listOf("x", "x"), sink.texts)
    }

    @Test
    fun a_masked_field_never_selects_on_focus() {
        val draft = FieldDraft(masked = true)
        draft.focused = true
        // A selected password reveals its length to anyone looking at the screen.
        draft.focusGained("hunter2", selectAll = true)
        assertTrue(draft.textFieldValue("hunter2").selection.collapsed)
    }

    @Test
    fun a_multi_line_field_never_selects_on_focus() {
        val draft = FieldDraft(singleLine = false)
        draft.focused = true
        // A pasted key or a note is edited, not replaced wholesale.
        draft.focusGained("-----BEGIN OPENSSH PRIVATE KEY-----", selectAll = true)
        assertTrue(draft.textFieldValue("-----BEGIN OPENSSH PRIVATE KEY-----").selection.collapsed)
    }
}
