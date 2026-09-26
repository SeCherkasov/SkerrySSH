package app.skerry.ui.sync

import app.skerry.shared.sync.SyncOutcome
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A record the server hands back that the vault refuses — it does not authenticate under the account
 * key, or it would re-type a record holding its id — is a tampering signal. The engine counts it into
 * [SyncOutcome.rejected]; the coordinator has to pass that count on, or the signal ends in silence.
 */
class SyncCoordinatorRejectedRecordsTest {

    private val crypto = IonspinVaultCrypto()
    private val account = "maya"
    private val password = "vault-A"

    @Test
    fun `records the vault refused are reported with their count, and a clean cycle reports nothing`() = runBlocking<Unit> {
        initializeVaultCrypto()
        val vault = newAccountVault(crypto, password)
        val client = ReactivatingClient(wrapOwnKey(vault, crypto, password, account), reactivated = false)
        val reported = CopyOnWriteArrayList<Int>()
        // The connect's own cycle is clean; the one after it refuses three records.
        val cycles = AtomicInteger()
        val sut = SyncCoordinator(
            clientFactory = { client },
            crypto = crypto,
            vault = vault,
            onRecordsRejected = { reported += it },
            engineFactory = { _ ->
                SyncRunner { _ ->
                    val rejected = if (cycles.incrementAndGet() == 1) 0 else 3
                    SyncOutcome(pulled = 0, pushed = 0, cursor = 0L, rejected = rejected)
                }
            },
        )
        try {
            sut.connect("https://sync.test", account, password.toCharArray())
            sut.status.awaitStatus("the session to come up") { it is SyncStatus.Online }

            sut.syncNow()
            awaitSync("the refused records to be reported") { while (reported.isEmpty()) delay(20) }
            assertEquals(listOf(3), reported, "only the cycle that refused something reports, and with its count")
        } finally {
            sut.close()
        }
    }
}
