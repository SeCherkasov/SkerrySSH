package app.skerry.shared.snippet

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FileSnippetStoreTest {

    private val tempDir: Path = Files.createTempDirectory("skerry-snippets")
    private val file: Path get() = tempDir.resolve("snippets.json")

    @AfterTest
    fun cleanup() {
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    private fun snip(id: String, label: String, command: String = "df -h", tags: List<String> = emptyList()) =
        Snippet(id, label, command, tags)

    @Test
    fun `starts empty when the file does not exist`() {
        assertEquals(emptyList(), FileSnippetStore(file).all())
    }

    @Test
    fun `persists added snippets across instances`() {
        val a = snip("1", "Disk usage", "df -h | sort -k5 -r", tags = listOf("monitoring", "disk"))
        val b = snip("2", "Restart nginx", "sudo systemctl reload nginx")

        FileSnippetStore(file).apply {
            put(a)
            put(b)
        }

        assertEquals(listOf(a, b), FileSnippetStore(file).all())
    }

    @Test
    fun `put replaces an existing snippet with the same id`() {
        val store = FileSnippetStore(file)
        store.put(snip("1", "old"))

        store.put(snip("1", "renamed", command = "uptime"))

        assertEquals(
            listOf(snip("1", "renamed", command = "uptime")),
            FileSnippetStore(file).all(),
        )
    }

    @Test
    fun `put keeps position when replacing in place`() {
        val store = FileSnippetStore(file)
        store.put(snip("1", "a"))
        store.put(snip("2", "b"))

        store.put(snip("1", "a2"))

        assertEquals(listOf("a2", "b"), FileSnippetStore(file).all().map { it.label })
    }

    @Test
    fun `remove deletes the snippet and persists`() {
        val store = FileSnippetStore(file)
        store.put(snip("1", "a"))
        store.put(snip("2", "b"))

        store.remove("1")

        assertEquals(listOf("2"), FileSnippetStore(file).all().map { it.id })
    }

    @Test
    fun `remove of unknown id is a no-op`() {
        val store = FileSnippetStore(file)
        store.put(snip("1", "a"))

        store.remove("nope")

        assertEquals(1, FileSnippetStore(file).all().size)
    }

    @Test
    fun `creates parent directories on first put`() {
        val nested = tempDir.resolve("nested/dir/snippets.json")
        val snippet = snip("1", "a")

        FileSnippetStore(nested).put(snippet)

        assertEquals(listOf(snippet), FileSnippetStore(nested).all())
    }

    @Test
    fun `persists shortcut across instances`() {
        val s = Snippet("1", "Disk usage", "df -h", tags = listOf("disk"), shortcut = "Ctrl+Shift+D")

        FileSnippetStore(file).put(s)

        assertEquals(listOf(s), FileSnippetStore(file).all())
    }

    @Test
    fun `reads legacy snippets ignoring the removed runOnHostId field`() {
        // Старый файл с уже удалённым полем runOnHostId: лишнее поле игнорируется (ignoreUnknownKeys),
        // shortcut при отсутствии = null.
        file.writeText("""[{"id":"1","label":"a","command":"ls","tags":["fs"],"runOnHostId":"host-7"}]""")

        val loaded = FileSnippetStore(file).all().single()

        assertEquals(snip("1", "a", command = "ls", tags = listOf("fs")), loaded)
        assertEquals(null, loaded.shortcut)
    }

    @Test
    fun `starts empty when the file is corrupt`() {
        file.writeText("{ not json at all ][")

        assertEquals(emptyList(), FileSnippetStore(file).all())
    }

    @Test
    fun `writes the file owner-only - it may carry inline credentials`() {
        FileSnippetStore(file).put(snip("1", "a"))

        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file),
        )
    }

    @Test
    fun `does not leave a temp file behind after writes`() {
        FileSnippetStore(file).put(snip("1", "a"))

        val leftovers = Files.list(tempDir).use { stream ->
            stream.map { it.fileName.toString() }.filter { it.endsWith(".tmp") }.toList()
        }
        assertEquals(emptyList(), leftovers)
    }
}
