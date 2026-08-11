package app.skerry.ui.sync

import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #172: what licenses a sync cycle over a vault that owes a reactivation rebuild is the rebuild THIS
 * coordinator ran *for this link, and for the debt that stands now* — not the bare fact that it ran one.
 * The debt is keyed on a whole [ServerLink] because one account id names two accounts on a home and a work
 * instance, and the permission has to be keyed the same way or the two disagree.
 *
 * Five ways the disagreement is reachable, one test each: another link's session, an activation that does
 * not reconcile, either store write of a reconcile refused, and a second revocation on the same link. The
 * doubles live in `ReactivationFixtures.kt`; the debt itself is exercised in [SyncCoordinatorReconcileDebtTest].
 */
class SyncCoordinatorArmedLinkTest {

    private val crypto = IonspinVaultCrypto()
    private val workUrl = "https://work.test"
    private val homeUrl = "https://home.test"
    private val account = "maya"
    private val password = "vault-A"

    private fun freshVault(): Vault = newAccountVault(crypto, password)
    private fun ownWrap(vault: Vault): ByteArray = wrapOwnKey(vault, crypto, password, account)

    /**
     * The state both of the first two tests need, and the one the issue describes: the work rebuild ran, its
     * own first cycle failed so work's debt still stands, and the connect to home — which reactivated this
     * device too — then died on the clear (an auto-lock landing inside it). Home's session is live over a
     * vault nothing was dropped from and home's rebuild is owed, while the only rebuild this coordinator
     * ever ran was work's. "r1" is what home purged while this device was revoked: no cycle on home may push
     * it back. Runs [body] with that state and closes the coordinator whatever the body does.
     */
    private suspend fun onHomeSessionAfterWorkRebuild(body: suspend (Armed) -> Unit) {
        initializeVaultCrypto()
        val vault = freshVault()
        val work = ReactivatingClient(ownWrap(vault), reactivated = true, failFirstPull = true)
        val home = ReactivatingClient(ownWrap(vault), reactivated = true)
        val debts = InMemoryReconcileDebtStore()
        val sut = SyncCoordinator(
            clientFactory = { url -> if (url == homeUrl) home else work },
            crypto = crypto,
            // The work rebuild's clear goes through; the vault is locked by the time home's runs.
            vault = ClearFailingVault(vault, failures = 1, intact = 1),
            configStore = InMemorySyncConfigStore(),
            debtStore = debts,
        )
        try {
            sut.connect(workUrl, account, password.toCharArray())
            sut.status.awaitStatus("the work reconcile's first cycle to fail") { it is SyncStatus.Failed }
            assertTrue(debts.owes(workUrl, account), "the work rebuild is still owed")

            sut.connect(homeUrl, account, password.toCharArray())
            sut.status.awaitStatus("the home connect to fail on the clear") { it is SyncStatus.Failed }
            assertTrue(debts.owes(homeUrl, account), "home's rebuild is owed and nothing has run it")
            vault.put("r1", RecordType.HOST, "purged-by-home".encodeToByteArray())
            body(Armed(sut, home, debts))
        } finally {
            sut.close()
        }
    }

    private data class Armed(
        val sut: SyncCoordinator,
        val home: ReactivatingClient,
        val debts: InMemoryReconcileDebtStore,
    )

    /**
     * Read as a flag, the work rebuild would answer for home: the cycle runs, pushes the records home purged,
     * and retires a rebuild home never got.
     */
    @Test
    fun `a rebuild run for one link is no permission to sync another`() = runBlocking {
        onHomeSessionAfterWorkRebuild { (sut, home, debts) ->
            sut.syncNow()
            sut.status.awaitStatus("the cycle to settle") {
                it is SyncStatus.Online || it == SyncStatus.Failed(SyncFailureReason.ReconcileRequired)
            }
            assertEquals(
                SyncStatus.Failed(SyncFailureReason.ReconcileRequired),
                sut.status.value,
                "a rebuild run for the work link is no permission to sync home",
            )
            assertFalse(home.pushed.any { it.id == "r1" }, "the record home purged must not be pushed back")
            assertTrue(debts.owes(homeUrl, account), "and a rebuild home never got must not be retired")
        }
    }

    /**
     * The same disagreement through the door an activation that does NOT reconcile opens: a password rotation
     * re-publishes the session over the vault as it is. It may keep an arming — a rebuild whose retiring
     * write was refused still answers for its own link — but only for the link that rebuild was run for. Kept
     * as a flag, work's would pass for home's here, and the rotation's first cycle pushes what home purged
     * and retires the rebuild home is still owed.
     */
    @Test
    fun `an activation that does not reconcile keeps only its own link's arming`() = runBlocking {
        onHomeSessionAfterWorkRebuild { (sut, home, debts) ->
            // The rotation re-publishes the home session without reconciling anything.
            assertEquals(
                AccountPasswordChange.Success,
                sut.changeAccountPassword(password.toCharArray(), "vault-B".toCharArray()),
            )
            assertEquals(
                SyncStatus.Failed(SyncFailureReason.ReconcileRequired),
                sut.status.value,
                "a rebuild run for the work link must not survive as home's",
            )
            assertFalse(home.pushed.any { it.id == "r1" }, "the record home purged must not be pushed back")
            assertTrue(debts.owes(homeUrl, account), "and a rebuild home never got must not be retired")
        }
    }

    /**
     * Both guards read the SAVED link as the identity of the server this session talks to, so the reconcile
     * saves it before it records the debt: the debt write can be refused (a full disk — and a store writes
     * even when the set is unchanged), and the session is already published by then. Recorded first, that
     * refusal leaves the guards reading the link this device came FROM while the live session is on the one
     * it reactivated on — and the cycle over the un-rebuilt vault is licensed by a link nobody asked about.
     */
    @Test
    fun `a refused debt write leaves the guards reading the link the session is on`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()
        vault.put("r1", RecordType.HOST, "purged-by-work".encodeToByteArray())

        val work = ReactivatingClient(ownWrap(vault), reactivated = false)
        val home = ReactivatingClient(ownWrap(vault), reactivated = false)
        // Linked to home, and owing work a rebuild from an earlier launch: the connect below reconciles off
        // the standing debt, not off a fresh `reactivated`.
        val config = InMemorySyncConfigStore()
            .also { it.save(SyncConfig(homeUrl, account, deviceId = "devA")) }
        val debts = DebtWriteFailingStore(
            InMemoryReconcileDebtStore().also { it.save(setOf(ServerLink(workUrl, account))) },
        )
        val sut = SyncCoordinator(
            clientFactory = { url -> if (url == homeUrl) home else work },
            crypto = crypto,
            vault = vault,
            configStore = config,
            debtStore = debts,
        )
        try {
            sut.connect(workUrl, account, password.toCharArray())
            sut.status.awaitStatus("the connect to fail on the refused debt write") { it is SyncStatus.Failed }

            sut.syncNow()
            sut.status.awaitStatus("the cycle to settle") {
                it is SyncStatus.Online || it == SyncStatus.Failed(SyncFailureReason.ReconcileRequired)
            }
            assertEquals(
                SyncStatus.Failed(SyncFailureReason.ReconcileRequired),
                sut.status.value,
                "the session is on work, and work's rebuild never ran",
            )
            assertFalse(work.pushed.any { it.id == "r1" }, "the record work purged must not be pushed back")
        } finally {
            sut.close()
        }
    }

    /**
     * The same refusal on the other write: the reconcile cannot save the link either, and the session for it
     * is already published. The saved link then still names the one this device came from — the very link
     * this coordinator is armed for — so a permission that outlives the switch licenses the cycle against a
     * server that never got its rebuild, and retires the debt of the one that did.
     */
    @Test
    fun `a refused link write leaves no permission behind for the link it named`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()

        val work = ReactivatingClient(ownWrap(vault), reactivated = true, failFirstPull = true)
        val home = ReactivatingClient(ownWrap(vault), reactivated = true)
        val config = LinkWriteFailingStore(InMemorySyncConfigStore())
        val debts = InMemoryReconcileDebtStore()
        val sut = SyncCoordinator(
            clientFactory = { url -> if (url == homeUrl) home else work },
            crypto = crypto,
            vault = vault,
            configStore = config,
            debtStore = debts,
        )
        try {
            // Work rebuilds and arms this coordinator; its own first cycle fails, so the debt stands.
            sut.connect(workUrl, account, password.toCharArray())
            sut.status.awaitStatus("the work reconcile's first cycle to fail") { it is SyncStatus.Failed }
            assertTrue(debts.owes(workUrl, account), "the work rebuild is still owed")

            config.refuse = true
            sut.connect(homeUrl, account, password.toCharArray())
            sut.status.awaitStatus("the home connect to fail on the link write") { it is SyncStatus.Failed }
            assertEquals(workUrl, config.load()?.serverUrl, "the refused write left the work link saved")
            // Only now: the work session's push job is still live, and a record added while THAT one was the
            // session the vault answered to would arm a cycle this test is not about.
            vault.put("r1", RecordType.HOST, "purged-by-home".encodeToByteArray())

            sut.syncNow()
            sut.status.awaitStatus("the cycle to settle") {
                it is SyncStatus.Online || it == SyncStatus.Failed(SyncFailureReason.ReconcileRequired)
            }
            assertEquals(
                SyncStatus.Failed(SyncFailureReason.ReconcileRequired),
                sut.status.value,
                "the session is home's, and no rebuild of this vault was run for it",
            )
            assertFalse(home.pushed.any { it.id == "r1" }, "the record home purged must not be pushed back")
            assertTrue(debts.owes(workUrl, account), "and work's rebuild must not be retired by home's cycle")
        } finally {
            sut.close()
        }
    }

    /**
     * A second revocation on the same link is a NEW obligation, and the permission an earlier rebuild left
     * says nothing about it: the records this debt is about went into the vault after that rebuild. The
     * state is reachable because a retiring write can be refused (a full disk) — the rebuild ran, its cycle
     * succeeded, and the debt stayed standing with the arming up. If the reconcile the next connect owes
     * then dies on the clear, an arming carried over would let the cycle push exactly what was purged.
     */
    @Test
    fun `a fresh revocation revokes the permission the last rebuild left`() = runBlocking {
        initializeVaultCrypto()
        val vault = freshVault()

        val first = ReactivatingClient(ownWrap(vault), reactivated = true)
        val second = ReactivatingClient(ownWrap(vault), reactivated = true)
        var connects = 0
        val debts = DebtClearFailingStore(InMemoryReconcileDebtStore())
        val sut = SyncCoordinator(
            clientFactory = { if (connects++ == 0) first else second },
            crypto = crypto,
            // The first rebuild runs; the second one finds the vault locked.
            vault = ClearFailingVault(vault, failures = 1, intact = 1),
            configStore = InMemorySyncConfigStore(),
            debtStore = debts,
        )
        try {
            sut.connect(workUrl, account, password.toCharArray())
            sut.status.awaitStatus("the first connect to settle") { it is SyncStatus.Online || it is SyncStatus.Failed }
            assertTrue(sut.status.value is SyncStatus.Online, "was ${sut.status.value}")
            assertTrue(debts.owes(workUrl, account), "the refused retiring write leaves the debt standing")

            // What the account purges while this device is revoked the second time.
            vault.put("r1", RecordType.HOST, "purged-while-revoked".encodeToByteArray())

            sut.connect(workUrl, account, password.toCharArray())
            sut.status.awaitStatus("the second connect to fail on the clear") { it is SyncStatus.Failed }

            sut.syncNow()
            sut.status.awaitStatus("the cycle to settle") {
                it is SyncStatus.Online || it == SyncStatus.Failed(SyncFailureReason.ReconcileRequired)
            }
            assertEquals(
                SyncStatus.Failed(SyncFailureReason.ReconcileRequired),
                sut.status.value,
                "the rebuild this revocation owes has not run — the previous one does not answer for it",
            )
            assertFalse(second.pushed.any { it.id == "r1" }, "the purged record must not reach the server")
        } finally {
            sut.close()
        }
    }
}
