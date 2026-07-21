package app.skerry.ui.sync

import app.skerry.shared.sync.DeviceInfo
import app.skerry.shared.sync.PairingResult
import app.skerry.shared.sync.PairingTicket
import app.skerry.shared.sync.RecordPage
import app.skerry.shared.sync.RemoteDevice
import app.skerry.shared.sync.RemoteRecord
import app.skerry.shared.sync.SyncClient
import app.skerry.shared.sync.SyncOutcome
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.sync.SyncSignal
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.CompletableDeferred
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #30 — sync must stop while the vault is locked and come back once it is unlocked.
 *
 * Locking leaves the WS live-pull and the local-push subscription running against a vault that can no
 * longer decrypt anything: every signal retries a sync that is guaranteed to throw, the status parks on
 * Failed, and nothing repairs it after unlocking (the session survived, so there is nothing to
 * "restore"). [SyncCoordinator.pauseForLock] / [SyncCoordinator.resumeAfterUnlock] are the two halves.
 *
 * Real vault and crypto (the connect path derives keys with Argon2id); only the network is faked.
 */
class SyncCoordinatorLockPauseTest {

    private val crypto = IonspinVaultCrypto()
    private val serverUrl = "https://sync.test"
    private val account = "maya"
    private val password = "vault-A"

    /**
     * Registers any account and reports how many live-pull subscriptions are collecting right now.
     * [registerGate], when given, holds `register` open so a connect can be caught mid-flight.
     */
    private class WatchCountingClient(private val registerGate: CompletableDeferred<Unit>? = null) : SyncClient {
        val watchers = AtomicInteger(0)
        val peakWatchers = AtomicInteger(0)
        val registering = CompletableDeferred<Unit>()

        override fun changes(session: SyncSession): Flow<SyncSignal> = flow {
            watchers.incrementAndGet()
            peakWatchers.updateAndGet { maxOf(it, watchers.get()) }
            try {
                awaitCancellation()
            } finally {
                watchers.decrementAndGet()
            }
        }

        override suspend fun register(accountId: String, authKey: ByteArray, wrappedDataKey: ByteArray, device: DeviceInfo): SyncSession {
            registering.complete(Unit)
            registerGate?.await()
            return SyncSession(accountId, accessToken = "access", refreshToken = "refresh")
        }

        override suspend fun ping(): Boolean = true
        override suspend fun close() {}
        override suspend fun login(accountId: String, authKey: ByteArray, device: DeviceInfo): SyncSession = nope()
        override suspend fun fetchWrappedDataKey(session: SyncSession): ByteArray = nope()
        override suspend fun pull(session: SyncSession, since: Long): RecordPage = nope()
        override suspend fun push(session: SyncSession, records: List<RemoteRecord>): RecordPage = nope()
        override suspend fun listDevices(session: SyncSession): List<RemoteDevice> = nope()
        override suspend fun revokeDevice(session: SyncSession, deviceId: String): Boolean = nope()
        override suspend fun refresh(session: SyncSession): SyncSession = nope()
        override suspend fun startPairing(session: SyncSession, encryptedDataKey: ByteArray): PairingTicket = nope()
        override suspend fun claimPairing(code: String, device: DeviceInfo): PairingResult = nope()
        private fun nope(): Nothing = throw NotImplementedError("the lock/unlock flow should not call this")
    }

    private fun localVault(): Vault {
        val file = Files.createTempFile("skerry-issue30", ".json").toString().toPath()
        FileSystem.SYSTEM.delete(file) // FileVault creates it
        return FileVault(file, crypto, deviceId = "dev-local", fileSystem = FileSystem.SYSTEM, now = { "2026-07-21T00:00:00Z" })
            .also { it.create(password.toCharArray()) }
    }

    private fun coordinator(vault: Vault, client: SyncClient): SyncCoordinator = SyncCoordinator(
        clientFactory = { client },
        crypto = crypto,
        vault = vault,
        engineFactory = { _ -> SyncRunner { _ -> SyncOutcome(pulled = 0, pushed = 0, cursor = 0L) } },
    )

    @Test
    fun `locking stops live sync and unlocking brings it back`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val client = WatchCountingClient()
        val sut = coordinator(vault, client)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            withTimeout(30_000) { sut.status.first { it is SyncStatus.Online } }
            withTimeout(5_000) { while (client.watchers.get() == 0) delay(10) }

            // Lock: no live subscription may survive it — otherwise the WS keeps retrying against a
            // vault that can't decrypt, and the status parks on Failed.
            vault.lock()
            sut.pauseForLock()
            withTimeout(5_000) { sut.status.first { it is SyncStatus.Configured } }
            withTimeout(5_000) { while (client.watchers.get() != 0) delay(10) }

            // Unlock: sync resumes on its own, no password retyped and no manual "Sync".
            assertTrue(vault.unlock(password.toCharArray()) is UnlockResult.Success)
            sut.resumeAfterUnlock()
            withTimeout(30_000) { sut.status.first { it is SyncStatus.Online } }
            withTimeout(5_000) { while (client.watchers.get() == 0) delay(10) }
            assertEquals(1, client.peakWatchers.get(), "resume must not stack a second live-pull subscription")
        } finally {
            sut.close()
        }
    }

    @Test
    fun `locking during a connect stops the subscriptions that connect publishes`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val gate = CompletableDeferred<Unit>()
        val client = WatchCountingClient(registerGate = gate)
        val sut = coordinator(vault, client)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            withTimeout(30_000) { client.registering.await() } // the connect now holds opMutex

            // The vault locks mid-connect. The pause queues behind the connect on opMutex and tears down
            // what it published; without that, live sync survives the lock unnoticed.
            vault.lock()
            sut.pauseForLock()
            gate.complete(Unit)

            withTimeout(30_000) { sut.status.first { it is SyncStatus.Configured } }
            withTimeout(5_000) { while (client.watchers.get() != 0) delay(10) }
            delay(200) // and it stays down: nothing restarts them behind the lock screen
            assertEquals(0, client.watchers.get())
            assertTrue(sut.status.value is SyncStatus.Configured)
        } finally {
            sut.close()
        }
    }

    @Test
    fun `a connect that starts after the pause does not publish a live session`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val client = WatchCountingClient()
        val sut = coordinator(vault, client)
        try {
            // The other ordering of the same race: the pause wins opMutex first and finds no session to
            // stop, so the connect behind it has to undo its own live half — nothing else is left to.
            sut.pauseForLock()
            delay(100) // let the pause run to completion, so it cannot tear the connect down for us
            sut.connect(serverUrl, account, password.toCharArray())

            withTimeout(30_000) { sut.status.first { it is SyncStatus.Configured } }
            delay(200)
            assertEquals(0, client.watchers.get(), "no live-pull may survive behind a locked vault")
        } finally {
            sut.close()
        }
    }

    @Test
    fun `unlocking without a preceding pause leaves the live session alone`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val client = WatchCountingClient()
        val sut = coordinator(vault, client)
        try {
            sut.connect(serverUrl, account, password.toCharArray())
            withTimeout(30_000) { sut.status.first { it is SyncStatus.Online } }
            withTimeout(5_000) { while (client.watchers.get() == 0) delay(10) }

            // A stray unlock callback (biometric re-prompt, gate recomposition) must not tear down or
            // duplicate a healthy session.
            sut.resumeAfterUnlock()
            delay(300)
            assertTrue(sut.status.value is SyncStatus.Online)
            assertEquals(1, client.peakWatchers.get(), "no second subscription")
        } finally {
            sut.close()
        }
    }
}
