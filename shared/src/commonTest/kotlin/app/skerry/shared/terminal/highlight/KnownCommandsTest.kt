package app.skerry.shared.terminal.highlight

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KnownCommandsTest {

    @Test
    fun `built-in catalogue is known without any history`() {
        val vocab = SessionVocabulary()
        assertTrue(vocab.isCommand("git"))
        assertTrue(vocab.isCommand("systemctl"))
        assertTrue(vocab.isCommand("ls"))
        assertTrue(vocab.isCommand("sudo"))
    }

    @Test
    fun `shell keywords are commands`() {
        val vocab = SessionVocabulary()
        assertTrue(vocab.isCommand("if"))
        assertTrue(vocab.isCommand("done"))
        assertTrue(vocab.isCommand("echo"))
    }

    @Test
    fun `history teaches the host's own tooling`() {
        val vocab = SessionVocabulary(listOf("pveversion -v", "zfs list"))
        assertTrue(vocab.isCommand("pveversion"))
        assertTrue(vocab.isCommand("zfs"))
    }

    @Test
    fun `history entries that are not command names are not learned`() {
        val vocab = SessionVocabulary(listOf("./deploy.sh prod", "FOO=1 make", "-n 5"))
        assertFalse(vocab.isCommand("./deploy.sh"))
        assertFalse(vocab.isCommand("FOO=1"))
        assertFalse(vocab.isCommand("-n"))
        assertFalse(vocab.isCommand("deploy.sh"))
    }

    @Test
    fun `unknown word stays unknown`() {
        assertFalse(SessionVocabulary().isCommand("frobnicate"))
    }

    @Test
    fun `subcommands are scoped to their command`() {
        val vocab = SessionVocabulary()
        assertTrue(vocab.isSubcommand("git", "status"))
        assertTrue(vocab.isSubcommand("systemctl", "restart"))
        assertFalse(vocab.isSubcommand("git", "restart"))
        assertFalse(vocab.isSubcommand("frobnicate", "status"))
    }

    @Test
    fun `empty vocabulary knows nothing`() {
        assertFalse(CommandVocabulary.Empty.isCommand("git"))
        assertFalse(CommandVocabulary.Empty.isSubcommand("git", "status"))
    }
}
