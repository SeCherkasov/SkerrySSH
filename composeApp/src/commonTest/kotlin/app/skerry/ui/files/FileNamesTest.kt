package app.skerry.ui.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileNamesTest {

    @Test
    fun `ordinary names are safe`() {
        listOf("a.txt", "sub", ".hidden", "file with spaces", "весёлое-имя", "..twodots").forEach {
            assertFalse(isUnsafeListingName(it), "expected a safe name: $it")
        }
    }

    @Test
    fun `empty separators and dot names are unsafe`() {
        listOf("", "a/b", "/abs", "a\\b", "..\\evil.txt", ".", "..").forEach {
            assertTrue(isUnsafeListingName(it), "expected an unsafe name: $it")
        }
    }

    @Test
    fun `childPath joins directory and name`() {
        assertEquals("/home/user/a.txt", childPath("/home/user", "a.txt"))
    }

    @Test
    fun `childPath does not double the slash at the root`() {
        assertEquals("/a.txt", childPath("/", "a.txt"))
    }
}
