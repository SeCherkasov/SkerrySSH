package app.skerry.shared.tunnel

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FileTunnelStoreTest {

    private val tempDir: Path = Files.createTempDirectory("skerry-tunnels")
    private val file: Path get() = tempDir.resolve("tunnels.json")

    @AfterTest
    fun cleanup() {
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    private fun local(id: String, label: String, hostId: String = "h1") =
        Tunnel(id, label, hostId, TunnelDirection.Local, "127.0.0.1", 8080, "10.0.0.5", 80)

    @Test
    fun `starts empty when the file does not exist`() {
        assertEquals(emptyList(), FileTunnelStore(file).all())
    }

    @Test
    fun `persists added tunnels across instances`() {
        val a = local("1", "web")
        val b = Tunnel("2", "socks", "h2", TunnelDirection.Dynamic, "127.0.0.1", 1080, null, null)

        FileTunnelStore(file).apply {
            put(a)
            put(b)
        }

        assertEquals(listOf(a, b), FileTunnelStore(file).all())
    }

    @Test
    fun `put replaces an existing tunnel with the same id`() {
        val store = FileTunnelStore(file)
        store.put(local("1", "old"))

        store.put(local("1", "renamed", hostId = "h9"))

        assertEquals(
            listOf(local("1", "renamed", hostId = "h9")),
            FileTunnelStore(file).all(),
        )
    }

    @Test
    fun `put keeps position when replacing in place`() {
        val store = FileTunnelStore(file)
        store.put(local("1", "a"))
        store.put(local("2", "b"))

        store.put(local("1", "a2"))

        assertEquals(listOf("a2", "b"), FileTunnelStore(file).all().map { it.label })
    }

    @Test
    fun `remove deletes the tunnel and persists`() {
        val store = FileTunnelStore(file)
        store.put(local("1", "a"))
        store.put(local("2", "b"))

        store.remove("1")

        assertEquals(listOf("2"), FileTunnelStore(file).all().map { it.id })
    }

    @Test
    fun `remove of unknown id is a no-op`() {
        val store = FileTunnelStore(file)
        store.put(local("1", "a"))

        store.remove("nope")

        assertEquals(1, FileTunnelStore(file).all().size)
    }

    @Test
    fun `creates parent directories on first put`() {
        val nested = tempDir.resolve("nested/dir/tunnels.json")
        val tunnel = local("1", "a")

        FileTunnelStore(nested).put(tunnel)

        assertEquals(listOf(tunnel), FileTunnelStore(nested).all())
    }

    @Test
    fun `starts empty when the file is corrupt`() {
        file.writeText("{ not json at all ][")

        assertEquals(emptyList(), FileTunnelStore(file).all())
    }

    @Test
    fun `does not leave a temp file behind after writes`() {
        FileTunnelStore(file).put(local("1", "a"))

        val leftovers = Files.list(tempDir).use { stream ->
            stream.map { it.fileName.toString() }.filter { it.endsWith(".tmp") }.toList()
        }
        assertEquals(emptyList(), leftovers)
    }
}
