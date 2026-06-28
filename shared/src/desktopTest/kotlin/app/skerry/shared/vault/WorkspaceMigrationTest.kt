package app.skerry.shared.vault

import app.skerry.shared.host.FileHostStore
import app.skerry.shared.host.Host
import app.skerry.shared.host.VaultHostStore
import app.skerry.shared.snippet.FileSnippetStore
import app.skerry.shared.snippet.Snippet
import app.skerry.shared.snippet.VaultSnippetStore
import app.skerry.shared.ssh.FileKnownHostsStore
import app.skerry.shared.ssh.KnownHost
import app.skerry.shared.ssh.VaultKnownHostsStore
import app.skerry.shared.tunnel.FileTunnelStore
import app.skerry.shared.tunnel.Tunnel
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.shared.tunnel.VaultTunnelStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceMigrationTest {

    private val dir: Path = Files.createTempDirectory("skerry-migration-test")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun seedHosts(vararg hosts: Host) {
        val store = FileHostStore(dir.resolve("hosts.json"))
        hosts.forEach { store.put(it) }
    }

    @Test
    fun `migrates hosts snippets and tunnels into the vault and deletes legacy files`() {
        seedHosts(
            Host(id = "h1", label = "Web", address = "web", port = 22, username = "root", group = "prod"),
            Host(id = "h2", label = "Db", address = "db", port = 22, username = "root"),
        )
        FileSnippetStore(dir.resolve("snippets.json")).put(Snippet(id = "s1", label = "Disk", command = "df -h"))
        FileTunnelStore(dir.resolve("tunnels.json")).put(
            Tunnel(id = "t1", label = "DB", hostId = "h2", direction = TunnelDirection.Local, bindPort = 5432, destHost = "127.0.0.1", destPort = 5432),
        )
        FileKnownHostsStore(dir.resolve("known_hosts")).add(
            KnownHost("web.example.com", 22, "ssh-ed25519", "SHA256:AAA", "2026-06-29T00:00:00Z"),
        )
        Files.write(dir.resolve("custom_groups"), "empty-folder\nanother\n".toByteArray())

        val vault = FakeVault()
        val changed = WorkspaceMigration(vault, dir).migrate()
        assertTrue(changed)
        assertEquals(listOf("empty-folder", "another"), WorkspaceLayoutStore(vault).read().groups)
        assertFalse(dir.resolve("custom_groups").exists(), "legacy custom_groups must be removed")

        assertEquals(listOf("h1", "h2"), VaultHostStore(vault).all().map { it.id })
        assertEquals("prod", VaultHostStore(vault).all().first { it.id == "h1" }.group)
        assertEquals(listOf("s1"), VaultSnippetStore(vault).all().map { it.id })
        assertEquals(listOf("t1"), VaultTunnelStore(vault).all().map { it.id })
        assertEquals(listOf("web.example.com"), VaultKnownHostsStore(vault).all().map { it.host })

        assertFalse(dir.resolve("hosts.json").exists(), "legacy hosts.json must be removed")
        assertFalse(dir.resolve("snippets.json").exists())
        assertFalse(dir.resolve("tunnels.json").exists())
        assertFalse(dir.resolve("known_hosts").exists(), "legacy known_hosts must be removed")
    }

    @Test
    fun `is idempotent — a second run is a no-op without legacy files`() {
        seedHosts(Host(id = "h1", label = "Web", address = "web", port = 22, username = "root"))
        val vault = FakeVault()
        assertTrue(WorkspaceMigration(vault, dir).migrate())
        // Второй прогон: файлов уже нет → ничего не переносит и не падает.
        assertFalse(WorkspaceMigration(vault, dir).migrate())
        assertEquals(listOf("h1"), VaultHostStore(vault).all().map { it.id })
    }

    @Test
    fun `keeps a corrupt legacy file instead of deleting it (no data loss)`() {
        // Недописанный/битый JSON: файловый стор молча отдаёт пусто. Миграция НЕ должна удалить файл —
        // иначе единственная копия пропадёт безвозвратно (security M1).
        val file = dir.resolve("hosts.json")
        Files.write(file, "[{\"id\":\"h1\",\"label\":\"Web\",".toByteArray()) // оборван
        val vault = FakeVault()
        val changed = WorkspaceMigration(vault, dir).migrate()
        assertFalse(changed, "corrupt file yields nothing to migrate")
        assertTrue(file.exists(), "corrupt legacy file must be preserved, not deleted")
        assertEquals(emptyList(), VaultHostStore(vault).all().map { it.id })
    }

    @Test
    fun `does not duplicate hosts already present in the vault`() {
        seedHosts(Host(id = "h1", label = "Web", address = "web", port = 22, username = "root"))
        val vault = FakeVault()
        // id уже есть в vault (например, синканулся раньше) — миграция не задваивает.
        VaultHostStore(vault).put(Host(id = "h1", label = "Synced", address = "web", port = 22, username = "root"))
        WorkspaceMigration(vault, dir).migrate()
        val hosts = VaultHostStore(vault).all()
        assertEquals(1, hosts.size)
        assertEquals("Synced", hosts.single().label) // существующая запись не перетёрта
    }
}
