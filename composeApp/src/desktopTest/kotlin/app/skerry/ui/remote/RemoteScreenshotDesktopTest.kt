package app.skerry.ui.remote

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The desktop half of the screenshot button: a real PNG under the user's pictures folder. */
class RemoteScreenshotDesktopTest {

    @Test
    fun the_frame_lands_in_the_pictures_folder_as_a_png() = runTest {
        withHome { home ->
            home.resolve("Pictures").createDirectories()

            val path = saveRemoteScreenshot(ImageBitmap(4, 3), "win-01.lan")

            assertTrue(path != null && path.endsWith(".png"), "no file was written: $path")
            val file = Path.of(path!!)
            assertTrue(file.startsWith(home.resolve("Pictures").resolve("Skerry")), path)
            // Written, not just named: the first eight bytes are the PNG signature.
            assertEquals(PNG_MAGIC.toList(), file.readBytes().take(PNG_MAGIC.size).toList())
        }
    }

    @Test
    fun a_home_without_a_pictures_folder_still_gets_the_file() = runTest {
        withHome { home ->
            val path = saveRemoteScreenshot(ImageBitmap(2, 2), "desk")

            assertTrue(path != null && Path.of(path).startsWith(home.resolve("Skerry")), "$path")
        }
    }

    @Test
    fun an_unwritable_target_answers_null_instead_of_throwing() = runTest {
        withHome { home ->
            // A regular file where the Skerry directory should go: createDirectories fails, and a
            // screenshot is not worth an exception escaping into the UI.
            Files.createFile(home.resolve("Skerry"))

            assertNull(saveRemoteScreenshot(ImageBitmap(2, 2), "desk"))
            // And nothing half-written is left behind under it.
            assertTrue(home.listDirectoryEntries().none { it.fileName.toString().startsWith("skerry-") })
        }
    }

    private inline fun withHome(body: (Path) -> Unit) {
        val previous = System.getProperty("user.home")
        val home = Files.createTempDirectory("skerry-shot")
        System.setProperty("user.home", home.toString())
        try {
            body(home)
        } finally {
            if (previous != null) System.setProperty("user.home", previous) else System.clearProperty("user.home")
            home.toFile().deleteRecursively()
        }
    }

    private companion object {
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
