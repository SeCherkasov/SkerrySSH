package app.skerry.shared.sync

import app.skerry.server.config.ServerConfig
import app.skerry.server.module
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.initializeVaultCrypto
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Сквозной zero-knowledge round-trip: реальный self-hosted сервер (embedded) + настоящий
 * [KtorSyncClient] по HTTP. Доказывает ключевой инвариант Phase 2 — новое устройство B,
 * имея лишь мастер-пароль и accountId, восстанавливает dataKey из серверной обёртки и
 * расшифровывает запись, созданную устройством A, при этом сервер видит только шифротекст
 * (`docs/skerry-sync-design.md` §1, §4).
 */
class SyncE2eTest {

    private val accountId = "alice@example.com"
    private val masterPassword = "correct horse battery staple"

    /** AAD слота записи как в FileVault: `id` + U+001F + `type.name`. */
    private fun aad(id: String, type: RecordType) = "$id${type.name}".encodeToByteArray()

    @Test
    fun `device B reconstructs data key from password and decrypts device A record`() = runBlocking {
        initializeVaultCrypto()
        val crypto = IonspinVaultCrypto()
        val port = ServerSocket(0).use { it.localPort }
        val dbFile = Files.createTempFile("skerry-e2e-", ".db")
        val config = ServerConfig.fromEnv(
            mapOf(
                "SKERRY_DB_URL" to "jdbc:sqlite:${dbFile.toAbsolutePath()}",
                "SKERRY_JWT_SECRET" to "e2e-test-secret-not-default",
                "SKERRY_PORT" to "$port",
            ),
        )
        val server = embeddedServer(Netty, port = port) { module(config) }.start(wait = false)
        val client = KtorSyncClient("http://localhost:$port")
        val vaultDir = Files.createTempDirectory("skerry-e2e-vault")
        try {
            // --- Устройство A: локальный vault + запись ---
            val vaultA = FileVault(
                path = vaultDir.resolve("vault.json").toString().toPath(),
                crypto = crypto,
                deviceId = "devA",
                fileSystem = FileSystem.SYSTEM,
                now = { "2026-06-29T00:00:00Z" },
            )
            vaultA.create(masterPassword.toCharArray())
            val payload = "192.168.1.45 root prod-web".encodeToByteArray()
            vaultA.put("h1", RecordType.HOST, payload)

            // Материал sync-аутентификации: соль = accountId (design §1), отдельная обёртка под неё.
            val syncSalt = crypto.deriveSyncSalt(accountId)
            val masterA = crypto.deriveMasterKey(masterPassword.toCharArray(), syncSalt)
            val authKeyA = crypto.deriveAuthKey(masterA)
            val dataKeyA = vaultA.exportDataKey()!!
            val serverWrapped = crypto.wrapDataKey(masterA, dataKeyA)

            val sessionA = client.register(accountId, authKeyA, serverWrapped, DeviceInfo("devA", "Laptop A"))
            val outcome = SyncEngine(client, vaultA).sync(sessionA)
            assertTrue(outcome.pushed >= 1, "device A should push its record")

            // --- Устройство B: только мастер-пароль и accountId, без локального vault ---
            val masterB = crypto.deriveMasterKey(masterPassword.toCharArray(), crypto.deriveSyncSalt(accountId))
            val authKeyB = crypto.deriveAuthKey(masterB)
            val sessionB = client.login(accountId, authKeyB, DeviceInfo("devB", "Phone B"))

            val wrappedFromServer = client.fetchWrappedDataKey(sessionB)
            val dataKeyB = crypto.unwrapDataKey(masterB, wrappedFromServer)
                ?: error("device B failed to unwrap dataKey — zero-knowledge bootstrap broken")

            val page = client.pull(sessionB, 0)
            val hostRecord = page.records.single { it.id == "h1" }

            // Сервер хранил только шифротекст: blob не содержит открытый payload дословно.
            assertFalse(hostRecord.blob.toList().windowed(payload.size).any { it == payload.toList() })

            // Устройство B расшифровывает запись A ключом, выведенным ТОЛЬКО из пароля + accountId.
            val decrypted = crypto.open(dataKeyB, hostRecord.blob, aad("h1", RecordType.HOST))
            assertContentEquals(payload, decrypted)

            // Устройства аккаунта видны обоим.
            assertEquals(setOf("devA", "devB"), client.listDevices(sessionB).map { it.id }.toSet())
        } finally {
            client.close()
            server.stop(100, 100)
            Files.deleteIfExists(dbFile)
        }
    }

    @Test
    fun `fully propagated tombstone is compacted and never resurrects`() = runBlocking {
        initializeVaultCrypto()
        val crypto = IonspinVaultCrypto()
        val port = ServerSocket(0).use { it.localPort }
        val dbFile = Files.createTempFile("skerry-e2e-", ".db")
        val config = ServerConfig.fromEnv(
            mapOf(
                "SKERRY_DB_URL" to "jdbc:sqlite:${dbFile.toAbsolutePath()}",
                "SKERRY_JWT_SECRET" to "e2e-test-secret-not-default",
                "SKERRY_PORT" to "$port",
            ),
        )
        val server = embeddedServer(Netty, port = port) { module(config) }.start(wait = false)
        val client = KtorSyncClient("http://localhost:$port")
        val vaultDir = Files.createTempDirectory("skerry-e2e-vault")
        try {
            val vault = FileVault(
                path = vaultDir.resolve("vault.json").toString().toPath(),
                crypto = crypto,
                deviceId = "devA",
                fileSystem = FileSystem.SYSTEM,
                now = { "2026-06-30T00:00:00Z" },
            )
            vault.create(masterPassword.toCharArray())
            vault.put("r1", RecordType.HOST, "secret".encodeToByteArray())

            val master = crypto.deriveMasterKey(masterPassword.toCharArray(), crypto.deriveSyncSalt(accountId))
            val authKey = crypto.deriveAuthKey(master)
            val dataKey = vault.exportDataKey()!!
            val session = client.register(accountId, authKey, crypto.wrapDataKey(master, dataKey), DeviceInfo("devA", "A"))

            val state = InMemorySyncStateStore()
            val engine = SyncEngine(client, vault, state)
            engine.sync(session)   // push живой r1
            vault.remove("r1")     // надгробие
            engine.sync(session)   // push надгробия; курсор единственного устройства догоняет его serverSeq

            // Единственное устройство дочитало надгробие → watermark его накрыл → сервер вернул его в
            // compactedIds → клиент физически забыл его. Больше не в records ⇒ больше не пушится.
            assertFalse(vault.records().any { it.id == "r1" }, "fully-propagated tombstone must be compacted away")

            // Ре-энролл/полный re-pull: курсор в 0. Сервер ещё держит надгробие и отдаст его в дельте —
            // но компакция в ТОМ ЖЕ ответе (merge → compact) не даёт ему воскреснуть. Это и есть фикс
            // «крота»: purge на сервере теперь не отменяется обратным push'ем клиента.
            state.setCursor(accountId, 0)
            engine.sync(session)
            assertFalse(vault.records().any { it.id == "r1" }, "tombstone must not resurrect on a full re-pull")
        } finally {
            client.close()
            server.stop(100, 100)
            Files.deleteIfExists(dbFile)
        }
    }

    @Test
    fun `wrong password cannot log in`() = runBlocking {
        initializeVaultCrypto()
        val crypto = IonspinVaultCrypto()
        val port = ServerSocket(0).use { it.localPort }
        val dbFile = Files.createTempFile("skerry-e2e-", ".db")
        val config = ServerConfig.fromEnv(
            mapOf(
                "SKERRY_DB_URL" to "jdbc:sqlite:${dbFile.toAbsolutePath()}",
                "SKERRY_JWT_SECRET" to "e2e-test-secret-not-default",
                "SKERRY_PORT" to "$port",
            ),
        )
        val server = embeddedServer(Netty, port = port) { module(config) }.start(wait = false)
        val client = KtorSyncClient("http://localhost:$port")
        try {
            val syncSalt = crypto.deriveSyncSalt(accountId)
            val authKey = crypto.deriveAuthKey(crypto.deriveMasterKey(masterPassword.toCharArray(), syncSalt))
            client.register(accountId, authKey, byteArrayOf(1, 2, 3), DeviceInfo("devA", "A"))

            val wrongAuth = crypto.deriveAuthKey(crypto.deriveMasterKey("wrong-password".toCharArray(), syncSalt))
            val failure = runCatching { client.login(accountId, wrongAuth, DeviceInfo("devB", "B")) }
            assertTrue(failure.isFailure, "login with wrong password must fail")
            assertEquals(SyncException.Kind.UNAUTHORIZED, (failure.exceptionOrNull() as? SyncException)?.kind)
        } finally {
            client.close()
            server.stop(100, 100)
            Files.deleteIfExists(dbFile)
        }
    }
}
