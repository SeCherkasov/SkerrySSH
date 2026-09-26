package app.skerry.shared.team

import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The Teams identity is the key every envelope is sealed to, and the record syncs to every device of
 * the account. A record that is there but cannot be read here is not a missing one: minting a fresh
 * pair over it would push a new identity to all devices and orphan every envelope sealed to the old one.
 */
class TeamIdentityStoreTest {

    private val crypto = IonspinVaultCrypto()
    private val password = "master"

    private fun newVault() = FileVault(
        path = Files.createTempDirectory("skerry-identity").resolve("vault.json").toString().toPath(),
        crypto = crypto,
        deviceId = "dev-a",
        fileSystem = FileSystem.SYSTEM,
        now = { "2026-09-26T00:00:00Z" },
    )

    @Test
    fun `an identity record that does not open is refused, not replaced`() = runBlocking<Unit> {
        initializeVaultCrypto()
        val vault = newVault()
        vault.create(password.toCharArray())
        val store = TeamIdentityStore(vault, crypto)
        store.ensure()
        // What an adopted account key leaves behind until the re-pull lands.
        vault.adoptDataKey(crypto.newDataKey(), password.toCharArray())
        val before = vault.records().single { it.type == RecordType.TEAM_IDENTITY }

        assertNull(store.load())
        assertFailsWith<TeamIdentityUnreadableException> { store.ensure() }

        val after = vault.records().single { it.type == RecordType.TEAM_IDENTITY }
        assertContentEquals(before.blob, after.blob, "the unreadable identity was overwritten")
        assertFalse(after.version != before.version, "the unreadable identity was re-versioned")
    }

    @Test
    fun `a missing identity is still created on first use`() = runBlocking<Unit> {
        initializeVaultCrypto()
        val vault = newVault()
        vault.create(password.toCharArray())
        val store = TeamIdentityStore(vault, crypto)

        val created = store.ensure()

        assertContentEquals(created.sharing.publicKey, store.load()!!.sharing.publicKey)
    }
}
