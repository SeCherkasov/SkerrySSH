package app.skerry.ui.sync

import app.skerry.shared.sync.SyncOutcome
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #278 — who owns the status. Two things write it without the user asking: the silent
 * keep-connected restore ([SyncCoordinator.restoreSession]) and the vault's idle lock. Neither may run
 * over what the user is doing, and neither may make a tap of the user's into a no-op:
 *
 *  - a restore in flight must not swallow a connect or a pairing (the flake this class is named after:
 *    the double-submit guard read [SyncStatus.Busy], which a restore raises just the same);
 *  - a restore must not write over a connect paused on [SyncStatus.NeedsPasswordReplaceConfirm] — that
 *    would take the dialog off the screen while the password it stashed stays in memory;
 *  - a real double submit must still be refused, including the confirmed re-run of a paused connect;
 *  - the lock declines a pending confirmation rather than leaving the ACCOUNT password in the heap.
 *
 * The vault and the crypto are real (Argon2id), as in [SyncCoordinatorPasswordReplaceTest]; only the
 * network is the shared [FakeAccountClient], whose gates park an operation mid-flight so the racing one
 * has a deterministic window to run in.
 */
class SyncCoordinatorLinkingGuardTest {

    private val crypto = IonspinVaultCrypto()
    private val serverUrl = "https://sync.test"
    private val account = "maya"
    private val vaultPassword = "vault-A"
    private val accountPassword = "account-B"

    /** A local vault created under [vaultPassword] (unlocked, with its own random dataKey). */
    private fun localVault(): Vault {
        val file = Files.createTempFile("skerry-issue278", ".json").toString().toPath()
        FileSystem.SYSTEM.delete(file) // FileVault creates it
        return FileVault(file, crypto, deviceId = "dev-local", fileSystem = FileSystem.SYSTEM, now = { "2026-07-18T00:00:00Z" })
            .also { it.create(vaultPassword.toCharArray()) }
    }

    private fun coordinator(
        vault: Vault,
        client: FakeAccountClient,
        configStore: SyncConfigStore = InMemorySyncConfigStore(),
    ): SyncCoordinator = SyncCoordinator(
        clientFactory = { client },
        crypto = crypto,
        vault = vault,
        configStore = configStore,
        debtStore = InMemoryReconcileDebtStore(),
        deviceIdProvider = { "dev-local" },
        engineFactory = { _ -> SyncRunner { _ -> SyncOutcome(pulled = 0, pushed = 0, cursor = 0L) } },
    )

    /** Sync already configured for [account] on [serverUrl], without the keep-connected token. */
    private fun configuredStore(): SyncConfigStore = InMemorySyncConfigStore().apply {
        save(SyncConfig(serverUrl, account, deviceId = "dev-local"))
    }

    /**
     * The bug under the flake in issue #278. The silent keep-connected restore raises [SyncStatus.Busy]
     * exactly like a connect does, and [SyncCoordinator.connect]'s double-submit guard read that status —
     * so a restore that happened to start first turned the user's own connect into a no-op, with nothing
     * on screen to say the tap did anything. A double submit is what that guard is for; a background
     * restore is not one, and the connect must queue behind it instead of vanishing.
     */
    @Test
    fun `a silent restore in flight does not swallow a user connect`() = runBlocking<Unit> {
        initializeVaultCrypto()
        val vault = localVault()
        val gate = CompletableDeferred<Unit>()
        val client = FakeAccountClient(crypto, account, existingAccountPassword = accountPassword, refreshGate = gate)
        val store = InMemorySyncConfigStore().apply { save(keepConnectedLink(vault, crypto, serverUrl, account, "dev-local")) }
        val sut = coordinator(vault, client, store)
        try {
            sut.restoreSession()
            awaitSync("the silent restore to reach the token exchange") { client.refreshing.await() }
            // The restore holds opMutex and the status is Busy because of it — the connect must still land.
            sut.connect(serverUrl, account, accountPassword.toCharArray())
            gate.complete(Unit) // the restore fails and parks on Configured; the connect is next in the queue
            sut.status.awaitStatus("the password-replace confirmation to be asked") { it is SyncStatus.NeedsPasswordReplaceConfirm }
        } finally {
            sut.close()
        }
    }

    /**
     * The other half of the same race. A connect paused on the confirmation owns the status: a silent
     * restore running over [SyncStatus.NeedsPasswordReplaceConfirm] takes the dialog off the screen while
     * the stashed password stays in memory, and nothing but that dialog can confirm or decline it. The
     * restore is deferred, not dropped — declining hands it back.
     */
    @Test
    fun `a silent restore does not run over a connect paused on the confirmation`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val client = FakeAccountClient(crypto, account, existingAccountPassword = accountPassword)
        val linked = keepConnectedLink(vault, crypto, serverUrl, account, "dev-local")
        val store = InMemorySyncConfigStore().apply { save(linked) }
        val sut = coordinator(vault, client, store)
        try {
            sut.connect(serverUrl, account, accountPassword.toCharArray())
            sut.status.awaitStatus("the password-replace confirmation to be asked") { it is SyncStatus.NeedsPasswordReplaceConfirm }
            sut.restoreSession()
            delay(700) // give the restore its chance to (wrongly) take the confirmation off the screen
            assertTrue(sut.status.value is SyncStatus.NeedsPasswordReplaceConfirm, "was ${sut.status.value}")
            assertEquals(0, client.refreshes, "the paused connect owns the status — nothing restores under it")
            assertEquals(linked, store.load(), "and the saved link is untouched")
            // Deferred, not dropped: the answer releases the stand-down, and the next ordinary trigger (an
            // unlock, a reachability edge) is what restores. The decline itself must not — a restore that
            // owes a reactivation rebuild clears the vault, and Cancel is not the button for that.
            sut.cancelPasswordReplace()
            sut.status.awaitStatus("the declined connect to fall back to the saved link") { it is SyncStatus.Configured }
            assertEquals(0, client.refreshes, "declining is not itself a restore")
            sut.restoreSession()
            awaitSync("the released restore to reach the token exchange") { client.refreshing.await() }
        } finally {
            sut.close()
        }
    }

    /**
     * The deeper half of the same guard. A restore that passed its pre-check and then queued behind the
     * connect on `opMutex` learns about the pause only when it gets the lock — by which time the dialog is
     * already up. The re-check under the lock is what stands it down; without it the pre-check alone would
     * let exactly the interleaving that flaked through.
     */
    @Test
    fun `a restore queued behind the connect stands down once the connect pauses`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val gate = CompletableDeferred<Unit>()
        val client = FakeAccountClient(crypto, account, existingAccountPassword = accountPassword, loginGate = gate)
        val linked = keepConnectedLink(vault, crypto, serverUrl, account, "dev-local")
        val store = InMemorySyncConfigStore().apply { save(linked) }
        val sut = coordinator(vault, client, store)
        try {
            sut.connect(serverUrl, account, accountPassword.toCharArray())
            awaitSync("the connect to reach the login") { client.loggingIn.await() }
            // Nothing is stashed yet, so the restore passes its pre-check and queues on the held opMutex.
            sut.restoreSession()
            delay(200) // keep the queue order deterministic: the restore enters the mutex queue behind the connect
            gate.complete(Unit)
            sut.status.awaitStatus("the password-replace confirmation to be asked") { it is SyncStatus.NeedsPasswordReplaceConfirm }
            delay(700) // the queued restore now has the lock — it must find the pause and stand down
            assertTrue(sut.status.value is SyncStatus.NeedsPasswordReplaceConfirm, "was ${sut.status.value}")
            assertEquals(0, client.refreshes, "the queued restore must stand down on the pause it found")
            assertEquals(linked, store.load(), "and leave the saved link alone")
        } finally {
            sut.close()
        }
    }

    /**
     * The confirmed re-run is a linking operation like the connect it resumes, and the paused connect's own
     * unwind must not hand the guard back while it runs — the user can confirm in the window between the
     * status going up and that unwind finishing.
     */
    @Test
    fun `a connect during the confirmed re-run is refused`() = runBlocking {
        initializeVaultCrypto()
        val vault = localVault()
        val gate = CompletableDeferred<Unit>()
        val client = FakeAccountClient(crypto, account, existingAccountPassword = accountPassword, fetchGate = gate)
        val sut = coordinator(vault, client)
        try {
            sut.connect(serverUrl, account, accountPassword.toCharArray())
            sut.status.awaitStatus("the password-replace confirmation to be asked") { it is SyncStatus.NeedsPasswordReplaceConfirm }
            sut.confirmPasswordReplace()
            awaitSync("the re-run to reach the account-key fetch") { client.fetching.await() }
            sut.connect(serverUrl, account, accountPassword.toCharArray()) // must not start a connect of its own
            // Not a sleep: opMutex is fair, so a connect the guard let through is already ahead of this
            // disconnect in the queue and would have logged in by the time the disconnect settles. A fixed
            // window would instead have to outlast two Argon2id derivations on a loaded machine. What the
            // proof rests on is launch order, not lock order — a leaked connect dispatched onto a slower
            // worker could in principle reach opMutex after this disconnect — so it is a head start, not
            // an interlock. Still strictly better than the wall clock it replaced.
            sut.disconnect()
            gate.complete(Unit)
            sut.status.awaitStatus("the disconnect to settle") { it is SyncStatus.Disabled }
            assertEquals(2, client.logins, "the verify and the confirmed re-run — nothing else")
        } finally {
            sut.close()
        }
    }

    /**
     * What the guard in [SyncCoordinator.connect] is actually for, now that it no longer reads the status:
     * a double submit must not start a second connect — it would open a second Ktor client and race the
     * first one's status. Characterisation, not proof of the swap: the status is Busy here either way, so
     * the old guard refused this too. What proves the swap is the pair of silent-restore tests above.
     */
    @Test
    fun `a second connect while the first is in flight is refused`() = runBlocking<Unit> {
        initializeVaultCrypto()
        val vault = localVault()
        val gate = CompletableDeferred<Unit>()
        val client = FakeAccountClient(crypto, account, existingAccountPassword = accountPassword, loginGate = gate)
        val sut = coordinator(vault, client)
        try {
            sut.connect(serverUrl, account, accountPassword.toCharArray())
            awaitSync("the first connect to reach the login") { client.loggingIn.await() }
            sut.connect(serverUrl, account, accountPassword.toCharArray()) // the double submit
            gate.complete(Unit)
            sut.status.awaitStatus("the connect to pause on the confirmation") { it is SyncStatus.NeedsPasswordReplaceConfirm }
            // Same reason as above, including the same residual: the head start is launch order, so this
            // is a strong probabilistic proof rather than an interlock.
            sut.disconnect()
            sut.status.awaitStatus("the disconnect to settle") { it is SyncStatus.Disabled }
            assertEquals(1, client.logins, "the double submit must not have started a connect of its own")
        } finally {
            sut.close()
        }
    }

    /** A well-formed pairing code for [serverUrl] whose envelope opens under no key — the claim fails after the call. */
    private fun pairingPayload() = PairingPayload(serverUrl, "code-1", ByteArray(32) { 7 }).encode()

    /**
     * The pairing entry point carries the same guard as [SyncCoordinator.connect], for the same reason: a
     * silent restore is Busy too, so a guard reading the status would turn "Link a device" into a no-op
     * with nothing on screen to say the tap did anything (issue #278).
     */
    @Test
    fun `a silent restore in flight does not swallow a device pairing`() = runBlocking<Unit> {
        initializeVaultCrypto()
        val vault = localVault()
        val gate = CompletableDeferred<Unit>()
        val client = FakeAccountClient(crypto, account, existingAccountPassword = accountPassword, refreshGate = gate)
        val store = InMemorySyncConfigStore().apply { save(keepConnectedLink(vault, crypto, serverUrl, account, "dev-local")) }
        val sut = coordinator(vault, client, store)
        try {
            sut.restoreSession()
            awaitSync("the silent restore to reach the token exchange") { client.refreshing.await() }
            sut.claimPairing(pairingPayload(), vaultPassword.toCharArray())
            gate.complete(Unit) // the restore fails and parks; the claim is next in the queue
            sut.status.awaitStatus("the claim to settle") { it is SyncStatus.Failed }
            assertEquals(1, client.claims, "the claim must reach the server, not be swallowed by the restore")
        } finally {
            sut.close()
        }
    }

    /**
     * A pairing supersedes a connect paused on the confirmation, exactly as a fresh connect does. The kept
     * ACCOUNT password is wiped, and the stand-down it put on the silent restore goes with it — otherwise
     * the password stays in memory for an answer the dialog can no longer give, and a keep-connected device
     * never restores again for the rest of the process.
     */
    @Test
    fun `a pairing supersedes a connect paused on the confirmation`() = runBlocking<Unit> {
        initializeVaultCrypto()
        val vault = localVault()
        val client = FakeAccountClient(crypto, account, existingAccountPassword = accountPassword)
        val store = InMemorySyncConfigStore().apply { save(keepConnectedLink(vault, crypto, serverUrl, account, "dev-local")) }
        val sut = coordinator(vault, client, store)
        try {
            sut.connect(serverUrl, account, accountPassword.toCharArray())
            sut.status.awaitStatus("the password-replace confirmation to be asked") { it is SyncStatus.NeedsPasswordReplaceConfirm }
            sut.claimPairing(pairingPayload(), vaultPassword.toCharArray())
            sut.status.awaitStatus("the claim to settle") { it is SyncStatus.Failed }
            assertEquals(1, client.claims, "the pairing was refused instead of superseding the pause")
            // Nothing is stashed any more, so the restore the pause stood down runs when next triggered.
            sut.restoreSession()
            awaitSync("the released restore to reach the token exchange") { client.refreshing.await() }
        } finally {
            sut.close()
        }
    }

    /**
     * The client a failed restore opened is closed on the way out. A dead token, a 5xx or a server that
     * flaps its health ping would otherwise strand a Ktor engine and its pool once per attempt.
     */
    @Test
    fun `a failed silent restore closes the client it opened`() = runBlocking<Unit> {
        initializeVaultCrypto()
        val vault = localVault()
        val client = FakeAccountClient(crypto, account, existingAccountPassword = accountPassword)
        val store = InMemorySyncConfigStore().apply { save(keepConnectedLink(vault, crypto, serverUrl, account, "dev-local")) }
        val sut = coordinator(vault, client, store)
        try {
            sut.restoreSession()
            awaitSync("the restore to reach the token exchange") { client.refreshing.await() }
            sut.status.awaitStatus("the failed restore to fall back to the saved link") { it is SyncStatus.Configured }
            assertEquals(1, client.closeCalls, "the client must be closed on the way out")
        } finally {
            sut.close()
        }
    }

    /**
     * The same decline, but the lock fires while the connect is still on its way to the pause — after the
     * login, before the stash. The synchronous decline finds nothing to drop and the password lands in
     * memory a moment later, behind an already-locked vault. What catches it is the second decline, the
     * one ordered after the connect by `opMutex`.
     */
    @Test
    fun `a lock that fires before the connect pauses still drops the password`() = runBlocking<Unit> {
        initializeVaultCrypto()
        val vault = localVault()
        val gate = CompletableDeferred<Unit>()
        val client = FakeAccountClient(crypto, account, existingAccountPassword = accountPassword, loginGate = gate)
        val sut = coordinator(vault, client, configuredStore())
        try {
            sut.connect(serverUrl, account, accountPassword.toCharArray())
            awaitSync("the connect to reach the login") { client.loggingIn.await() }
            sut.pauseForLock() // nothing is stashed yet — this one finds nothing
            gate.complete(Unit)
            sut.status.awaitStatus("the lock to take the question down") { it is SyncStatus.Configured }
        } finally {
            sut.close()
        }
    }

    /**
     * The idle lock drops every other decrypted secret; the password kept for the confirmation is the
     * ACCOUNT password, and behind the lock screen nobody can answer the question it belongs to. So the
     * lock declines on the user's behalf: the stash is wiped and the status stops claiming a dialog is up.
     */
    @Test
    fun `an auto-lock drops the password kept for the confirmation`() = runBlocking<Unit> {
        initializeVaultCrypto()
        val vault = localVault()
        val client = FakeAccountClient(crypto, account, existingAccountPassword = accountPassword)
        val sut = coordinator(vault, client, configuredStore())
        try {
            sut.connect(serverUrl, account, accountPassword.toCharArray())
            sut.status.awaitStatus("the password-replace confirmation to be asked") { it is SyncStatus.NeedsPasswordReplaceConfirm }
            sut.pauseForLock()
            sut.status.awaitStatus("the lock to take the question down") { it is SyncStatus.Configured }
            sut.confirmPasswordReplace() // nothing is stashed — this must not re-run the connect
            sut.disconnect() // queues on opMutex behind any re-run the confirmation would have started
            sut.status.awaitStatus("the disconnect to settle") { it is SyncStatus.Disabled }
            assertEquals(1, client.logins, "the stash was dropped — there is nothing left to confirm")
        } finally {
            sut.close()
        }
    }
}
