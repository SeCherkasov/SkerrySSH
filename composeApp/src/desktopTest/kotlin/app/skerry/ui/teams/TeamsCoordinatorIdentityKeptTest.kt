package app.skerry.ui.teams

import app.skerry.shared.sync.InMemorySyncStateStore
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.team.TeamIdentityStore
import app.skerry.shared.team.TeamVaults
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.ui.sync.TeamLink
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * A refresh publishes the account's Teams identity, creating it on first use. One that is on this
 * device but does not read is not a first use: the refresh stops and says so, and the record is left
 * for the re-pull that can still make it readable.
 */
class TeamsCoordinatorIdentityKeptTest {

    private val crypto = IonspinVaultCrypto()

    @Test
    fun `an unreadable identity stops the refresh instead of being replaced`() = runBlocking<Unit> {
        initializeVaultCrypto()
        val vaultFile = Files.createTempFile("skerry-identity-kept", ".json").toString().toPath()
        FileSystem.SYSTEM.delete(vaultFile)
        val vault = FileVault(vaultFile, crypto, deviceId = "dev-alice", fileSystem = FileSystem.SYSTEM, now = { NOW })
        vault.create("master".toCharArray())
        TeamIdentityStore(vault, crypto).ensure()
        vault.adoptDataKey(crypto.newDataKey(), "master".toCharArray())
        val before = vault.records().single { it.type == RecordType.TEAM_IDENTITY }
        val teamDir = Files.createTempDirectory("skerry-identity-kept-vaults").toString().toPath()
        val coord = TeamsCoordinator(
            live = {
                TeamLink(
                    SyncSession("alice@example.com", "access", "refresh"),
                    TeamsCoordinatorSpaceResetCursorTest.RecordingClient({ emptyList() }, { emptyList() }, bobKeys = null),
                    "test-link",
                )
            },
            vault = vault,
            crypto = crypto,
            teamVaults = TeamVaults(teamDir, crypto, deviceId = "dev-alice", fileSystem = FileSystem.SYSTEM, now = { NOW }),
            teamState = InMemorySyncStateStore(),
            newId = { error("unused") },
        )

        coord.refresh()

        assertEquals(TeamsFailure.IdentityKept, coord.lastError.value)
        assertContentEquals(before.blob, vault.records().single { it.type == RecordType.TEAM_IDENTITY }.blob)
    }

    private companion object {
        const val NOW = "2026-09-26T00:00:00Z"
    }
}
