package app.skerry.android

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Only an explicit choice overrides a flag's default. `writeText` truncates before it writes, so a
 * process killed in between leaves an empty file — and a default-on security switch
 * (`lock_on_background`, issue #397) must not read that as the user turning it off.
 */
class FlagFileTest {
    private val dir: File = Files.createTempDirectory("flags").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun anEmptyFileReadsAsTheDefault() {
        File(dir, "on").writeText("")
        File(dir, "off").writeText("")

        assertEquals(true, FlagFile("on", default = true).read(dir))
        assertEquals(false, FlagFile("off", default = false).read(dir))
    }

    @Test
    fun aGarbledFileReadsAsTheDefault() {
        File(dir, "on").writeText("tr")

        assertEquals(true, FlagFile("on", default = true).read(dir))
    }

    @Test
    fun anExplicitChoiceOverridesTheDefault() {
        File(dir, "on").writeText("false\n")
        File(dir, "off").writeText("true")

        assertEquals(false, FlagFile("on", default = true).read(dir))
        assertEquals(true, FlagFile("off", default = false).read(dir))
    }

    @Test
    fun aMissingFileReadsAsTheDefault() {
        assertEquals(true, FlagFile("absent", default = true).read(dir))
    }
}
