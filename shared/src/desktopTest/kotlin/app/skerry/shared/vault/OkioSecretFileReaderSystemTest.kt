package app.skerry.shared.vault

import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * The real JVM filesystem, not a fake: what #396 hit was `Paths.get` throwing InvalidPathException.
 * NUL is the one character every OS rejects, so this stands in for Windows' `"` `<` `|`.
 */
class OkioSecretFileReaderSystemTest {

    private val reader = OkioSecretFileReader(FileSystem.SYSTEM, homeDir = null)

    @Test
    fun `a ref the OS rejects as a path reads as not found`() {
        assertIs<SecretFileResult.NotFound>(reader.read("/tmp/skerry\u0000key"))
        assertFalse(reader.probe("/tmp/skerry\u0000key"))
    }
}
