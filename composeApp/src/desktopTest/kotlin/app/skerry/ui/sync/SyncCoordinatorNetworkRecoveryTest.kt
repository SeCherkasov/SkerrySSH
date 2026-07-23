package app.skerry.ui.sync

import app.skerry.shared.sync.DeviceInfo
import app.skerry.shared.sync.PairingResult
import app.skerry.shared.sync.PairingTicket
import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteDevice
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncClient
import app.skerry.shared.sync.SyncException
import app.skerry.shared.sync.SyncOutcome
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.sync.SyncSignal
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Self-healing after a network-failed sync. On mobile the first sync after a display unlock often
 * races the radio waking up from Doze and fails with NETWORK; without a retry the status parks on
 * Failed ("Sync error", red crossed cloud) until the user syncs manually or a remote change happens
 * to arrive — indefinitely long. The coordinator must bring itself back Online once the network is
 * up: via the server-reachability transition of the health ping and via its own retry backoff.
 *
 * Real vault and crypto (the connect path derives keys with Argon2id); only the network is faked.
 */
class SyncCoordinatorNetworkRecoveryTest {

    private val crypto = IonspinVaultCrypto()
    private val serverUrl = "https://sync.test"
    private val account = "maya"
    private val password = "vault-A"

    /** Client with a switchable server: [up] = false makes ping fail and refresh throw NETWORK. */
    private class SwitchableServerClient : SyncClient {
        @Volatile var up = true

        override suspend fun ping(): Boolean = up

        override suspend fun register(accountId: String, authKey: ByteArray, wrappedDataKey: ByteArray, device: DeviceInfo): SyncSession =
            SyncSession(accountId, accessToken = "access", refreshToken = "refresh")

        override suspend fun refresh(session: SyncSession): SyncSession {
            if (!up) throw SyncException(SyncException.Kind.NETWORK, "server down")
            return SyncSession(session.accountId, accessToken = "access-2", refreshToken = "refresh-2")
        }

        override fun changes(session: SyncSession): Flow<SyncSignal> = flow { awaitCancellation() }

        override suspend fun close() {}
        override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession = nope()
        override suspend fun changePassword(accountId: String, currentAuthKey: ByteArray, newAuthKey: ByteArray, newWrappedDataKey: ByteArray, device: DeviceInfo): SyncSession = nope()
        override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray = nope()
        override suspend fun pull(session: SyncSession, since: Long): RecordPage = nope()
        override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage = nope()
        override suspend fun listDevices(session: SyncSession): List<RemoteDevice> = nope()
        override suspend fun revokeDevice(session: SyncSession, deviceId: String): Boolean = nope()
        override suspend fun startPairing(session: SyncSession, encryptedDataKey: ByteArray): PairingTicket = nope()
        override suspend fun claimPairing(code: String, device: DeviceInfo): PairingResult = nope()
        private fun nope(): Nothing = throw NotImplementedError("recovery flow should not call this")
    }

    private fun localVault(): Vault {
        val file = Files.createTempFile("skerry-net-recovery", ".json").toString().toPath()
        FileSystem.SYSTEM.delete(file) // FileVault creates it
        return FileVault(file, crypto, deviceId = "dev-local", fileSystem = FileSystem.SYSTEM, now = { "2026-07-23T00:00:00Z" })
            .also { it.create(password.toCharArray()) }
    }

    /** Engine that mirrors the fake server: sync fails with NETWORK while [client.up] is false. */
    private fun coordinator(
        vault: Vault,
        client: SwitchableServerClient,
        configStore: SyncConfigStore = InMemorySyncConfigStore(),
    ): SyncCoordinator = SyncCoordinator(
        clientFactory = { client },
        crypto = crypto,
        vault = vault,
        configStore = configStore,
        engineFactory = { _ ->
            SyncRunner { _ ->
                if (!client.up) throw SyncException(SyncException.Kind.NETWORK, "server down")
                SyncOutcome(pulled = 0, pushed = 0, cursor = 0L)
            }
        },
        healthPollMs = 200,
    )

    @Test
    fun `network-failed sync heals itself when the server becomes reachable again`() = runBlocking<Unit> {
        initializeVaultCrypto()
        val vault = localVault()
        val client = SwitchableServerClient()
        val sut = coordinator(vault, client)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            withTimeout(30_000) { sut.status.first { it is SyncStatus.Online } }
            withTimeout(5_000) { sut.serverReachable.first { it == ServerReachable.REACHABLE } }

            // The network drops: a sync cycle fails with NETWORK and the status parks on Failed.
            client.up = false
            sut.syncNow()
            withTimeout(5_000) {
                sut.status.first { (it as? SyncStatus.Failed)?.reason == SyncFailureReason.Network }
            }
            withTimeout(5_000) { sut.serverReachable.first { it == ServerReachable.UNREACHABLE } }

            // The network returns. No manual sync, no remote change — the status must recover on its
            // own once the health ping sees the server again. The 3s bound is below the retry-backoff
            // minimum, so only the reachability-transition path can pass it.
            client.up = true
            withTimeout(3_000) { sut.status.first { it is SyncStatus.Online } }
        } finally {
            sut.close()
        }
    }

    @Test
    fun `network-failed sync retries with backoff even if the server never looked down`() = runBlocking<Unit> {
        initializeVaultCrypto()
        val vault = localVault()
        val client = SwitchableServerClient()
        val engineBroken = java.util.concurrent.atomic.AtomicBoolean(false)
        val sut = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = vault,
            engineFactory = { _ ->
                SyncRunner { _ ->
                    if (engineBroken.get()) throw SyncException(SyncException.Kind.NETWORK, "connection reset")
                    SyncOutcome(pulled = 0, pushed = 0, cursor = 0L)
                }
            },
            healthPollMs = 200,
        )
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            withTimeout(30_000) { sut.status.first { it is SyncStatus.Online } }

            // A transient blip: the sync path fails with NETWORK while the health ping stays green, so
            // no reachability transition will ever fire — only the coordinator's own retry can heal it.
            engineBroken.set(true)
            sut.syncNow()
            withTimeout(5_000) {
                sut.status.first { (it as? SyncStatus.Failed)?.reason == SyncFailureReason.Network }
            }
            engineBroken.set(false)
            withTimeout(15_000) { sut.status.first { it is SyncStatus.Online } }
        } finally {
            sut.close()
        }
    }

    @Test
    fun `keep-connected restore retries when the server becomes reachable`() = runBlocking<Unit> {
        initializeVaultCrypto()
        val vault = localVault()
        val client = SwitchableServerClient()
        val configStore = InMemorySyncConfigStore()

        // First launch: connect with keep-connected so the sealed refresh token lands in the config.
        val first = coordinator(vault, client, configStore)
        try {
            first.connect(serverUrl, account, password.toCharArray(), keepConnected = true)
            withTimeout(30_000) { first.status.first { it is SyncStatus.Online } }
            assertTrue(configStore.load()?.sealedRefreshToken != null, "keep-connected must seal the refresh token")
        } finally {
            first.close()
        }

        // Cold start with the network down: the silent restore fails and falls back to Configured.
        client.up = false
        val second = coordinator(vault, client, configStore)
        try {
            withTimeout(5_000) { second.status.first { it is SyncStatus.Configured } }
            second.resumeAfterUnlock() // the vault is already unlocked; restore hits the dead network
            delay(500)
            assertTrue(second.status.value is SyncStatus.Configured, "restore over a dead network must fall back to Configured")

            // The network returns: the restore must be retried without user action.
            client.up = true
            withTimeout(10_000) { second.status.first { it is SyncStatus.Online } }
        } finally {
            second.close()
        }
    }
}
