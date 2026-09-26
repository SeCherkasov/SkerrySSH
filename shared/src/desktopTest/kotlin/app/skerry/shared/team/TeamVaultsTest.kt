package app.skerry.shared.team

import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TeamVaultsTest {

    private val crypto = IonspinVaultCrypto()

    /**
     * The coordinator opens spaces from a sync on one dispatcher thread while the UI reads them from
     * another. Two [app.skerry.shared.vault.FileVault]s over one file each hold their own copy of the
     * records, and whichever writes last overwrites what the other wrote.
     */
    @Test
    fun `two threads opening one space get the same vault`() = runBlocking<Unit> {
        initializeVaultCrypto()
        val dir = Files.createTempDirectory("skerry-teamvaults-race").toString().toPath()
        val vaults = TeamVaults(dir, crypto, deviceId = "dev-a", fileSystem = FileSystem.SYSTEM, now = { NOW })
        val key = crypto.newDataKey()
        var split = 0
        repeat(ROUNDS) { round ->
            val ref = TeamScopeRef("team-$round")
            val barrier = CyclicBarrier(2)
            val opened = arrayOfNulls<Any>(2)
            val threads = (0..1).map { i -> thread { barrier.await(); opened[i] = vaults.open(ref, key) } }
            threads.forEach { it.join() }
            if (opened[0] !== opened[1]) split++
        }
        assertEquals(0, split, "rounds in which one space was opened as two vaults")
    }

    /**
     * Forgetting a team has to take its scope files with it. A directory that cannot be listed is not
     * an empty one: answering it as such leaves the records of a team the account has no right to on
     * disk, with nothing said.
     */
    @Test
    fun `resetting a team whose directory cannot be listed is a failure, not a no-op`() {
        val fake = FakeFileSystem()
        val dir = "/teams".toPath()
        fake.createDirectories(dir)
        fake.write(dir / "team-a__prod.vault") { writeUtf8("{}") }
        val unlistable = object : ForwardingFileSystem(fake) {
            override fun list(dir: Path): List<Path> = throw IOException("EACCES")
        }
        val vaults = TeamVaults(dir, crypto, deviceId = "dev-a", fileSystem = unlistable, now = { NOW })

        assertFailsWith<IOException> { vaults.resetTeam("team-a") }
    }

    @Test
    fun `resetting a team with no directory yet has nothing to do`() {
        val fake = FakeFileSystem()
        val vaults = TeamVaults("/teams".toPath(), crypto, deviceId = "dev-a", fileSystem = fake, now = { NOW })

        vaults.resetTeam("team-a")

        assertTrue(!fake.exists("/teams".toPath()))
    }

    @Test
    fun `resetting a team drops every scope file under it and nothing else`() {
        val fake = FakeFileSystem()
        val dir = "/teams".toPath()
        fake.createDirectories(dir)
        listOf("team-a.vault", "team-a__prod.vault", "team-a__dev.vault", "team-b__prod.vault")
            .forEach { name -> fake.write(dir / name) { writeUtf8("{}") } }
        val vaults = TeamVaults(dir, crypto, deviceId = "dev-a", fileSystem = fake, now = { NOW })

        vaults.resetTeam("team-a")

        assertEquals(listOf("team-b__prod.vault"), fake.list(dir).map { it.name })
    }

    private companion object {
        const val NOW = "2026-09-26T00:00:00Z"
        const val ROUNDS = 40
    }
}
