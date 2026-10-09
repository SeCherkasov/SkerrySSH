package app.skerry.ui.sync

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import app.skerry.shared.ai.AiProviderKind
import app.skerry.shared.ai.AiSettings
import app.skerry.shared.sync.SyncOutcome
import app.skerry.shared.update.UpdateSettings
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.ui.AppDependencies
import app.skerry.ui.ai.AiAssistantController
import app.skerry.ui.theme.SkerryTheme
import app.skerry.ui.desktop.WithTestLifecycle
import app.skerry.ui.mobile.MobileDesignApp
import app.skerry.ui.update.UpdateNoticeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MobileSyncedCatalogTest {
    @Test
    fun `an unrelated sync cannot cancel publication of received mobile settings`() = runBlocking<Unit> {
        initializeVaultCrypto()
        val fixture = Fixture()
        with(fixture) {
            try {
                runComposeUiTest {
                    setContent {
                        SkerryTheme {
                            WithTestLifecycle {
                                MobileDesignApp(deps = AppDependencies(vault = vault, sync = sync),
                                    aiOverride = ai, updatesOverride = updates)
                            }
                        }
                    }
                    waitForIdle()
                    sync.connect("https://sync.test", "maya", "vault-A".toCharArray())
                    waitUntil(timeoutMillis = 5_000) { ai.settings.model == "baseline" }
                    sync.syncNow()
                    waitUntil(timeoutMillis = 5_000) { entered.count == 0L }
                    assertTrue(entered.count == 0L, "the settings read must be in flight")
                    sync.syncNow()
                    // The old status-driven path lets the unrelated cycle cancel the settings effect.
                    // The callback path keeps the next cycle queued until this publication completes.
                    if (nextCycle.await(2, TimeUnit.SECONDS)) {
                        waitUntil(timeoutMillis = 5_000) {
                            (sync.status.value as? SyncStatus.Online)?.changedTypes == setOf(RecordType.HOST)
                        }
                        waitForIdle()
                    }
                    release.countDown()
                    waitUntil(timeoutMillis = 5_000) {
                        cycles.get() >= 3 && sync.status.value is SyncStatus.Online
                    }
                    waitForIdle()
                    assertEquals("synced", ai.settings.model)
                }
            } finally {
                release.countDown()
                sync.close()
                updates.stop()
                scope.cancel()
                vault.lock()
            }
        }
    }
}

private class Fixture {
    val crypto = IonspinVaultCrypto()
    val vault = newAccountVault(crypto, "vault-A")
    val client = ReactivatingClient(wrapOwnKey(vault, crypto, "vault-A", "maya"), reactivated = false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val current = AtomicReference(AiSettings(provider = AiProviderKind.OFF, model = "initial"))
    val block = AtomicBoolean(false)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val nextCycle = CountDownLatch(1)
    val cycles = AtomicInteger()
    val ai = AiAssistantController(current.get(), persist = {}, providerFactory = { error("AI is disabled") },
        scope = scope, reload = {
            val prepared = current.get()
            if (block.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "test did not release preparation" }
            }
            prepared
        })
    val updates = UpdateNoticeController(UpdateSettings(false), persist = {}, check = { null }, scope = scope)
    val sync = SyncCoordinator(clientFactory = { client }, crypto = crypto, vault = vault,
        engineFactory = {
            SyncRunner {
                val types = when (cycles.incrementAndGet()) {
                    1 -> { current.set(current.get().copy(model = "baseline")); setOf(RecordType.SETTINGS) }
                    2 -> { current.set(current.get().copy(model = "synced")); block.set(true); setOf(RecordType.SETTINGS) }
                    else -> { nextCycle.countDown(); setOf(RecordType.HOST) }
                }
                SyncOutcome(cycles.get(), 0, 0, changedTypes = types)
            }
        })
}
