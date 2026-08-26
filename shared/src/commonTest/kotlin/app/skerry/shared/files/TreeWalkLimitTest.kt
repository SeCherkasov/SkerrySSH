package app.skerry.shared.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The bounds every client-side walk over a server's listing shares. */
class TreeWalkLimitTest {

    @Test
    fun `a listing at the cap is allowed and the entry past it is refused`() {
        refuseOversizedListing("/d", MAX_LISTING_ENTRIES)

        val e = assertFailsWith<FileBrowserException> { refuseOversizedListing("/d", MAX_LISTING_ENTRIES + 1) }
        assertEquals(FileBrowserFailure.TreeTooLarge, e.failure)
    }

    @Test
    fun `a listing is measured against the room left, not against the whole cap`() {
        // A walk already holding the listings of the levels above it passes down what is left of the
        // bound, so the same directory is fine at the top of a walk and too wide halfway into one.
        refuseOversizedListing("/d", entries = 10, cap = 10)

        val e = assertFailsWith<FileBrowserException> { refuseOversizedListing("/d", entries = 10, cap = 9) }
        assertEquals(FileBrowserFailure.TreeTooLarge, e.failure)
    }

    @Test
    fun `a descent at the depth limit is allowed and the level past it is refused`() {
        refuseTooDeep(MAX_TREE_DEPTH)

        val e = assertFailsWith<FileBrowserException> { refuseTooDeep(MAX_TREE_DEPTH + 1) }
        assertEquals(FileBrowserFailure.TreeTooLarge, e.failure)
    }
}
