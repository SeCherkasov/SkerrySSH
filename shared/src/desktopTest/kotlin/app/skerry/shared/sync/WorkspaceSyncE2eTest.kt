package app.skerry.shared.sync

import app.skerry.server.config.ServerConfig
import app.skerry.server.module
import app.skerry.shared.host.Host
import app.skerry.shared.host.VaultHostStore
import app.skerry.shared.snippet.Snippet
import app.skerry.shared.snippet.VaultSnippetStore
import app.skerry.shared.ssh.KnownHost
import app.skerry.shared.ssh.VaultKnownHostsStore
import app.skerry.shared.tunnel.Tunnel
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.shared.tunnel.VaultTunnelStore
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.WorkspaceLayout
import app.skerry.shared.vault.WorkspaceLayoutStore
import app.skerry.shared.vault.initializeVaultCrypto
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase A «как у Termius»: всё рабочее пространство (хосты + их порядок, сниппеты, туннели) живёт
 * записями vault и E2E-синкается, а не только секреты. Доказывает ключевой результат фазы — новое
 * устройство B, имея лишь мастер-пароль и accountId, видит хосты/сниппеты/туннели устройства A
 * в правильном порядке, расшифровав их из одной обёртки dataKey (сервер видит только шифротекст).
 */
class WorkspaceSyncE2eTest {

    private val accountId = "alice@example.com"
    private val masterPassword = "correct horse battery staple"

    @Test
    fun `device B sees hosts snippets and tunnels of device A in tree order`() = runBlocking {
        initializeVaultCrypto()
        val crypto = IonspinVaultCrypto()
        val port = ServerSocket(0).use { it.localPort }
        val dbFile = Files.createTempFile("skerry-ws-e2e-", ".db")
        val config = ServerConfig.fromEnv(
            mapOf(
                "SKERRY_DB_URL" to "jdbc:sqlite:${dbFile.toAbsolutePath()}",
                "SKERRY_JWT_SECRET" to "e2e-test-secret-not-default",
                "SKERRY_PORT" to "$port",
            ),
        )
        val server = embeddedServer(Netty, port = port) { module(config) }.start(wait = false)
        val client = KtorSyncClient("http://localhost:$port")
        val dirA = Files.createTempDirectory("skerry-ws-a")
        val dirB = Files.createTempDirectory("skerry-ws-b")
        try {
            // --- Устройство A: наполняем рабочее пространство через vault-сторы ---
            val vaultA = FileVault(dirA.resolve("vault.json").toString().toPath(), crypto, "devA", FileSystem.SYSTEM) { "2026-06-29T00:00:00Z" }
            vaultA.create(masterPassword.toCharArray())
            val hostsA = VaultHostStore(vaultA)
            hostsA.put(Host("h1", "Web", "web.example.com", 22, "root", group = "prod"))
            hostsA.put(Host("h2", "Db", "db.example.com", 22, "root", group = "prod"))
            hostsA.put(Host("h3", "Bastion", "bastion.example.com", 22, "ubuntu"))
            hostsA.reorder { it.reversed() } // порядок дерева: h3, h2, h1
            VaultSnippetStore(vaultA).put(Snippet("s1", "Disk", "df -h", tags = listOf("ops")))
            VaultTunnelStore(vaultA).put(
                Tunnel("t1", "DB tunnel", hostId = "h2", direction = TunnelDirection.Local, bindPort = 5432, destHost = "127.0.0.1", destPort = 5432),
            )
            VaultKnownHostsStore(vaultA).add(KnownHost("web.example.com", 22, "ssh-ed25519", "SHA256:AAA", "2026-06-29T00:00:00Z"))
            // Пустая папка (нет хостов) живёт в записи-макете — она тоже синкается (как в Termius).
            WorkspaceLayoutStore(vaultA).apply { write(read().copy(groups = listOf("staging"))) }

            val syncSalt = crypto.deriveSyncSalt(accountId)
            val masterA = crypto.deriveMasterKey(masterPassword.toCharArray(), syncSalt)
            val sessionA = client.register(accountId, crypto.deriveAuthKey(masterA), crypto.wrapDataKey(masterA, vaultA.exportDataKey()!!), DeviceInfo("devA", "Laptop A"))
            SyncEngine(client, vaultA).sync(sessionA)

            // --- Устройство B: пустой локальный vault, бутстрап только из пароля + accountId ---
            val masterB = crypto.deriveMasterKey(masterPassword.toCharArray(), crypto.deriveSyncSalt(accountId))
            val sessionB = client.login(accountId, crypto.deriveAuthKey(masterB), DeviceInfo("devB", "Phone B"))
            val dataKeyB = crypto.unwrapDataKey(masterB, client.fetchWrappedDataKey(sessionB))
                ?: error("device B failed to unwrap dataKey")
            val vaultB = FileVault(dirB.resolve("vault.json").toString().toPath(), crypto, "devB", FileSystem.SYSTEM) { "2026-06-29T00:00:00Z" }
            vaultB.create(masterPassword.toCharArray())
            vaultB.unlockWithDataKey(dataKeyB) // тот же dataKey, что у A (как делает SyncCoordinator)
            SyncEngine(client, vaultB).sync(sessionB)

            // Устройство B видит то же рабочее пространство, что у A — расшифрованное и в порядке дерева.
            val hostsB = VaultHostStore(vaultB)
            assertEquals(listOf("h3", "h2", "h1"), hostsB.all().map { it.id })
            assertEquals("Web", hostsB.all().first { it.id == "h1" }.label)
            assertEquals("prod", hostsB.all().first { it.id == "h2" }.group)
            assertEquals(listOf("s1"), VaultSnippetStore(vaultB).all().map { it.id })
            assertEquals("df -h", VaultSnippetStore(vaultB).all().single().command)
            assertEquals(listOf("t1"), VaultTunnelStore(vaultB).all().map { it.id })
            assertEquals("h2", VaultTunnelStore(vaultB).all().single().hostId)
            assertEquals(listOf("web.example.com"), VaultKnownHostsStore(vaultB).all().map { it.host })
            assertEquals(listOf("staging"), WorkspaceLayoutStore(vaultB).read().groups)
        } finally {
            client.close()
            server.stop(100, 100)
            Files.deleteIfExists(dbFile)
        }
    }
}
