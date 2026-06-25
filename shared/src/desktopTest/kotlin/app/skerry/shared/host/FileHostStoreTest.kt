package app.skerry.shared.host

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileHostStoreTest {

    private val tempDir: Path = Files.createTempDirectory("skerry-hosts")
    private val file: Path get() = tempDir.resolve("hosts.json")

    @AfterTest
    fun cleanup() {
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    @Test
    fun `starts empty when the file does not exist`() {
        assertEquals(emptyList(), FileHostStore(file).all())
    }

    @Test
    fun `persists added hosts across instances`() {
        val a = Host("1", "prod-web", "10.0.0.5", 22, "deploy", "Production")
        val b = Host("2", "homelab-pi", "pi.local", 2222, "pi")

        FileHostStore(file).apply {
            put(a)
            put(b)
        }

        assertEquals(listOf(a, b), FileHostStore(file).all())
    }

    @Test
    fun `put replaces an existing host with the same id`() {
        val store = FileHostStore(file)
        store.put(Host("1", "old", "10.0.0.5", 22, "deploy"))

        store.put(Host("1", "renamed", "10.0.0.6", 2022, "admin", "Production"))

        assertEquals(
            listOf(Host("1", "renamed", "10.0.0.6", 2022, "admin", "Production")),
            FileHostStore(file).all(),
        )
    }

    @Test
    fun `put keeps position when replacing in place`() {
        val store = FileHostStore(file)
        store.put(Host("1", "a", "a.local", 22, "u"))
        store.put(Host("2", "b", "b.local", 22, "u"))

        store.put(Host("1", "a2", "a2.local", 22, "u"))

        assertEquals(listOf("a2", "b"), FileHostStore(file).all().map { it.label })
    }

    @Test
    fun `remove deletes the host and persists`() {
        val store = FileHostStore(file)
        store.put(Host("1", "a", "a.local", 22, "u"))
        store.put(Host("2", "b", "b.local", 22, "u"))

        store.remove("1")

        assertEquals(listOf("2"), FileHostStore(file).all().map { it.id })
    }

    @Test
    fun `remove of unknown id is a no-op`() {
        val store = FileHostStore(file)
        store.put(Host("1", "a", "a.local", 22, "u"))

        store.remove("nope")

        assertEquals(1, FileHostStore(file).all().size)
    }

    @Test
    fun `creates parent directories on first put`() {
        val nested = tempDir.resolve("nested/dir/hosts.json")
        val host = Host("1", "a", "a.local", 22, "u")

        FileHostStore(nested).put(host)

        assertEquals(listOf(host), FileHostStore(nested).all())
    }

    @Test
    fun `starts empty when the file is corrupt`() {
        file.writeText("{ not json at all ][")

        assertEquals(emptyList(), FileHostStore(file).all())
    }

    @Test
    fun `reorder rewrites the whole set in the given order and persists`() {
        val store = FileHostStore(file)
        store.put(Host("1", "a", "a.local", 22, "u", "Prod"))
        store.put(Host("2", "b", "b.local", 22, "u", "Prod"))

        // Перенос «b» в начало с новой группой — как делает ручная сортировка.
        store.reorder {
            listOf(
                Host("2", "b", "b.local", 22, "u", "Lab"),
                Host("1", "a", "a.local", 22, "u", "Prod"),
            )
        }

        assertEquals(
            listOf("2" to "Lab", "1" to "Prod"),
            FileHostStore(file).all().map { it.id to it.group },
        )
    }

    @Test
    fun `reorder rejects a result that changes the id set`() {
        val store = FileHostStore(file)
        store.put(Host("1", "a", "a.local", 22, "u"))
        store.put(Host("2", "b", "b.local", 22, "u"))

        assertFailsWith<IllegalArgumentException> {
            store.reorder { it.filter { host -> host.id == "1" } } // потеря «2»
        }
        // Файл не тронут — оба профиля на месте.
        assertEquals(listOf("1", "2"), FileHostStore(file).all().map { it.id })
    }

    @Test
    fun `does not leave a temp file behind after writes`() {
        FileHostStore(file).put(Host("1", "a", "a.local", 22, "u"))

        val leftovers = Files.list(tempDir).use { stream ->
            stream.map { it.fileName.toString() }.filter { it.endsWith(".tmp") }.toList()
        }
        assertEquals(emptyList(), leftovers)
    }
}
