package app.skerry.ui.vault

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one decision [ExportOutcome] encodes: which endings the user has to be told about. It exists
 * because a single `Boolean` cannot tell "you cancelled the dialog" apart from "the write failed",
 * and silence after a failed write reads as success — for a private key export, that is the user
 * believing they have a backup they do not have.
 */
class ExportOutcomeTest {

    @Test
    fun a_cancelled_save_as_is_the_users_own_choice() {
        assertFalse(ExportOutcome.Cancelled.worthReporting)
    }

    @Test
    fun a_failed_write_is_reported() {
        assertTrue(ExportOutcome.Failed.worthReporting)
    }

    @Test
    fun a_written_file_speaks_for_itself() {
        // The user chose the path in the dialog and can see the file there; a "saved!" box on top of
        // that is the reassuring second sentence this UI does not write.
        assertFalse(ExportOutcome.Saved.worthReporting)
    }
}
