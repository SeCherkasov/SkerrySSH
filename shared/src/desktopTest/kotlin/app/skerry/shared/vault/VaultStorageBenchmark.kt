package app.skerry.shared.vault

import app.skerry.shared.host.Host
import app.skerry.shared.host.VaultHostStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTime

/** Opt-in encrypted storage workload; use SKERRY_BENCH=1 and -Pskerry.bench without coverage. */
class VaultStorageBenchmark {
    @Test
    fun storageWorkload() = runBlocking {
        if (System.getenv("SKERRY_BENCH") != "1") return@runBlocking
        initializeVaultCrypto()
        val crypto = IonspinVaultCrypto()
        for (size in listOf(100, 1_000, 10_000)) {
            val samples = mutableMapOf<String, MutableList<Double>>()
            repeat(4) { run ->
                val fs = FakeFileSystem()
                val path = "/vault.json".toPath()
                val key = crypto.newDataKey()
                val vault = FileVault(path, crypto, "local", fs, now = { TS })
                vault.createWithDataKey(key)
                fun records(version: Long) = List(size) { index ->
                    val id = "host-$index"
                    val host = Host(id, "Host $index", "$index.example.com", 22, "root")
                    val payload = Json.encodeToString(Host.serializer(), host).encodeToByteArray()
                    VaultRecord(id, RecordType.HOST, version, TS, "peer", false,
                        crypto.seal(key, payload, recordAad(id, RecordType.HOST, version, "peer", false, TS)))
                }
                vault.mergeRemote(records(1))
                val remote = records(2).reversed()
                val store = VaultHostStore(vault)
                fun sample(name: String, block: () -> Unit) {
                    val millis = measureTime(block).inWholeMicroseconds / 1000.0
                    if (run > 0) samples.getOrPut(name) { mutableListOf() }.add(millis)
                }
                sample("merge") { assertEquals(size, vault.mergeRemote(remote).applied.size) }
                sample("catalog") { assertEquals(size, store.all().size) }
                val unlockKey = vault.exportDataKey()!!
                vault.lock()
                sample("unlock-catalog") {
                    assertEquals(UnlockResult.Success, vault.unlockWithDataKey(unlockKey))
                    assertEquals(size, store.all().size)
                }
                sample("import-100") {
                    val imported = List(100) { Host("import-$it", "Import $it", "$it.import.test", 22, "root") }
                    importHosts(store, imported)
                }
                assertEquals(size + 100, store.all().size)
                vault.lock()
                fs.close()
            }
            for ((name, values) in samples) {
                println("BENCH storage size=$size operation=$name medianMs=${values.sorted()[1]} samples=$values")
            }
        }
    }

    private fun importHosts(store: VaultHostStore, hosts: List<Host>) {
        store.putAll(hosts)
    }

    private companion object {
        const val TS = "2026-10-08T12:00:00Z"
    }
}
