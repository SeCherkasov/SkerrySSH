package app.skerry.ui.snippet

import app.skerry.shared.snippet.Snippet
import app.skerry.shared.snippet.SnippetMoment
import app.skerry.shared.snippet.SnippetRunEnvironment
import app.skerry.shared.snippet.SnippetStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnippetManagerTest {

    private val fixedEnvironment = SnippetRunEnvironment(
        moment = SnippetMoment(year = 2026, month = 7, day = 3, hour = 9, minute = 5, second = 42, epochSeconds = 1_782_000_000L),
        newUuid = { "fixed-uuid" },
        randomChars = { n -> "r".repeat(n) },
    )

    private fun managerWith(
        store: SnippetStore = FakeSnippetStore(),
        ids: List<String> = List(20) { "id-$it" },
    ): SnippetManager {
        val it = ids.iterator()
        return SnippetManager(store, newId = { it.next() }, environment = { fixedEnvironment })
    }

    private fun draft(label: String = "Disk usage", command: String = "df -h", tags: List<String> = emptyList()) =
        SnippetDraft(label = label, command = command, tags = tags)

    @Test
    fun `save persists a new snippet and lists it`() {
        val store = FakeSnippetStore()
        val manager = managerWith(store)

        val id = manager.save(draft(tags = listOf("monitoring")))

        assertEquals("id-0", id)
        val entry = manager.snippets.single()
        assertEquals("Disk usage", entry.snippet.label)
        assertEquals(listOf("monitoring"), entry.snippet.tags)
        assertEquals(listOf(id), store.all().map { it.id }) // made it to the store
    }

    @Test
    fun `save with existing id updates in place`() {
        val manager = managerWith()
        val id = manager.save(draft(label = "old"))

        manager.save(draft(label = "renamed").copy(id = id))

        val entry = manager.snippets.single()
        assertEquals("renamed", entry.snippet.label)
    }

    @Test
    fun `delete removes the snippet from store and list`() {
        val store = FakeSnippetStore()
        val manager = managerWith(store)
        val id = manager.save(draft())

        manager.delete(id)

        assertTrue(manager.snippets.isEmpty())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `run sends the command followed by a newline`() {
        val manager = managerWith()
        val id = manager.save(draft(command = "uptime"))
        var sent: String? = null

        manager.run(id) { sent = it }

        assertEquals("uptime\n", sent)
    }

    @Test
    fun `run strips bidi format characters from a plain command`() {
        // Snippets can arrive via Teams sharing — the "user-saved text" trust assumption does not
        // hold for the literal part either (Trojan Source in the palette row vs the PTY).
        val manager = managerWith()
        val id = manager.save(draft(command = "echo a\u202Eb"))
        var sent: String? = null

        manager.run(id) { sent = it }

        assertEquals("echo ab\n", sent)
    }

    @Test
    fun `run with unknown id is a no-op`() {
        val manager = managerWith()
        var sent: String? = null

        manager.run("nope") { sent = it }

        assertNull(sent)
    }

    @Test
    fun `run with variables opens a pending run instead of sending`() {
        val manager = managerWith()
        val id = manager.save(draft(command = "echo ${'$'}{{date}}"))
        var sent: String? = null

        manager.run(id) { sent = it }

        assertNull(sent)
        val pending = assertNotNull(manager.pendingRun)
        assertEquals(id, pending.snippet.id)
        assertEquals(2026, pending.environment.moment.year) // environment captured at request time
    }

    @Test
    fun `confirmRun sends the resolved line with a newline and clears the pending run`() {
        val manager = managerWith()
        val id = manager.save(draft(command = "echo ${'$'}{{date}}"))
        var sent: String? = null
        manager.run(id) { sent = it }

        manager.confirmRun("echo 2026-07-03", emptyMap())

        assertEquals("echo 2026-07-03\n", sent)
        assertNull(manager.pendingRun)
    }

    @Test
    fun `dismissRun drops the pending run without sending`() {
        val manager = managerWith()
        val id = manager.save(draft(command = "echo ${'$'}{{date}}"))
        var sent: String? = null
        manager.run(id) { sent = it }

        manager.dismissRun()

        assertNull(sent)
        assertNull(manager.pendingRun)
    }

    @Test
    fun `confirmRun remembers parameters and prefills the next run of the same snippet`() {
        val manager = managerWith()
        val id = manager.save(draft(command = "ping ${'$'}{{target_host}}"))
        manager.run(id) { }
        assertTrue(manager.pendingRun!!.initialParams.isEmpty())

        manager.confirmRun("ping web1", mapOf("target_host" to "web1"))
        manager.run(id) { }

        assertEquals(mapOf("target_host" to "web1"), manager.pendingRun!!.initialParams)
    }

    @Test
    fun `run captures the recording flag on the pending run`() {
        val manager = managerWith()
        val id = manager.save(draft(command = "echo ${'$'}{{date}}"))

        manager.run(id, recording = true) { }

        assertTrue(manager.pendingRun!!.recording)
    }

    @Test
    fun `confirmRun without a pending run is a no-op`() {
        val manager = managerWith()

        manager.confirmRun("ls", emptyMap()) // must not throw
    }

    @Test
    fun `run while a pending run is open is ignored`() {
        val manager = managerWith()
        val first = manager.save(draft(label = "first", command = "echo ${'$'}{{date}}"))
        val second = manager.save(draft(label = "second", command = "uptime"))
        manager.run(first) { }
        var sent: String? = null

        manager.run(second) { sent = it } // hotkey/palette while the dialog is up

        assertNull(sent) // not even the plain fast path — first request wins
        assertEquals(first, manager.pendingRun!!.snippet.id)
    }

    @Test
    fun `save persists shortcut`() {
        val manager = managerWith()

        val id = manager.save(draft().copy(shortcut = "Ctrl+Shift+D"))

        val s = manager.find(id)!!.snippet
        assertEquals("Ctrl+Shift+D", s.shortcut)
    }

    @Test
    fun `save normalizes a blank shortcut to null`() {
        val manager = managerWith()

        val id = manager.save(draft().copy(shortcut = "   "))

        assertNull(manager.find(id)!!.snippet.shortcut)
    }

    @Test
    fun `forShortcut finds the snippet bound to a hotkey`() {
        val manager = managerWith()
        val id = manager.save(draft(label = "Disk").copy(shortcut = "Ctrl+Shift+D"))
        manager.save(draft(label = "Mem")) // no hotkey

        assertEquals(id, manager.forShortcut("Ctrl+Shift+D")?.id)
    }

    @Test
    fun `forShortcut returns null for blank or unmatched`() {
        val manager = managerWith()
        manager.save(draft().copy(shortcut = "Ctrl+Shift+D"))

        assertNull(manager.forShortcut(null))
        assertNull(manager.forShortcut(""))
        assertNull(manager.forShortcut("Ctrl+Shift+X"))
    }

    @Test
    fun `shortcutConflict finds another snippet holding the hotkey`() {
        val manager = managerWith()
        val owner = manager.save(draft(label = "Disk").copy(shortcut = "Ctrl+Shift+D"))

        val conflict = manager.shortcutConflict("Ctrl+Shift+D", excludingId = null)

        assertEquals(owner, conflict?.id)
    }

    @Test
    fun `shortcutConflict ignores the snippet being edited`() {
        val manager = managerWith()
        val id = manager.save(draft().copy(shortcut = "Ctrl+Shift+D"))

        assertNull(manager.shortcutConflict("Ctrl+Shift+D", excludingId = id))
    }

    @Test
    fun `shortcutConflict is null for blank or free hotkey`() {
        val manager = managerWith()
        manager.save(draft().copy(shortcut = "Ctrl+Shift+D"))

        assertNull(manager.shortcutConflict(null, excludingId = null))
        assertNull(manager.shortcutConflict("", excludingId = null))
        assertNull(manager.shortcutConflict("Ctrl+Shift+X", excludingId = null))
    }

    @Test
    fun `find returns null for null or unknown id`() {
        val manager = managerWith()
        manager.save(draft())

        assertNull(manager.find(null))
        assertNull(manager.find("nope"))
    }

    @Test
    fun `save canonicalizes tags`() {
        val store = FakeSnippetStore()
        val manager = managerWith(store)

        val id = manager.save(draft(tags = listOf("#DB", "db", "  Disk  ")))

        assertEquals(listOf("db", "disk"), manager.find(id)!!.snippet.tags)
        assertEquals(listOf("db", "disk"), store.all().single().tags) // canonical in the store too
    }

    @Test
    fun `reads legacy non-canonical tags in canonical form`() {
        val store = FakeSnippetStore()
        store.put(Snippet("x", "saved", "ls -la", listOf("FS", "#fs", "Disk")))
        val manager = managerWith(store)

        assertEquals(listOf("fs", "disk"), manager.snippets.single().snippet.tags)
    }

    @Test
    fun `renameTag rewrites the tag on every snippet that carries it`() {
        val store = FakeSnippetStore()
        val manager = managerWith(store)
        val a = manager.save(draft(label = "a", tags = listOf("db")))
        val b = manager.save(draft(label = "b", tags = listOf("db", "prod")))
        val c = manager.save(draft(label = "c", tags = listOf("prod")))

        manager.renameTag("db", "database")

        assertEquals(listOf("database"), manager.find(a)!!.snippet.tags)
        assertEquals(listOf("database", "prod"), manager.find(b)!!.snippet.tags)
        assertEquals(listOf("prod"), manager.find(c)!!.snippet.tags) // untouched
        // Persisted to the store too.
        assertEquals(listOf("database"), store.all().first { it.id == a }.tags)
    }

    @Test
    fun `renameTag merges into an existing tag without duplicating`() {
        val store = FakeSnippetStore()
        val manager = managerWith(store)
        val id = manager.save(draft(tags = listOf("db", "prod")))

        manager.renameTag("db", "prod")

        assertEquals(listOf("prod"), manager.find(id)!!.snippet.tags)
        // The dedup must reach the store, not only the in-memory entry.
        assertEquals(listOf("prod"), store.all().single().tags)
    }

    @Test
    fun `renameTag to the same tag is a no-op`() {
        val store = FakeSnippetStore()
        val manager = managerWith(store)
        val id = manager.save(draft(tags = listOf("db", "prod")))

        manager.renameTag("db", "db")

        assertEquals(listOf("db", "prod"), manager.find(id)!!.snippet.tags)
        assertEquals(listOf("db", "prod"), store.all().single().tags)
    }

    @Test
    fun `renameTag normalizes the new tag`() {
        val manager = managerWith()
        val id = manager.save(draft(tags = listOf("db")))

        manager.renameTag("db", "#Database")

        assertEquals(listOf("database"), manager.find(id)!!.snippet.tags)
    }

    @Test
    fun `renameTag ignores a blank new tag`() {
        val manager = managerWith()
        val id = manager.save(draft(tags = listOf("db")))

        manager.renameTag("db", "   ")

        assertEquals(listOf("db"), manager.find(id)!!.snippet.tags)
    }

    @Test
    fun `renameTag leaves untagged snippets alone`() {
        val store = FakeSnippetStore()
        val manager = managerWith(store)
        val untagged = manager.save(draft(label = "plain"))

        manager.renameTag("db", "database")

        assertTrue(manager.find(untagged)!!.snippet.tags.isEmpty())
    }

    @Test
    fun `loads previously saved snippets on construction`() {
        val store = FakeSnippetStore()
        store.put(Snippet("x", "saved", "ls -la", listOf("fs")))
        val manager = managerWith(store)

        assertEquals(listOf("saved"), manager.snippets.map { it.snippet.label })
        assertEquals(listOf("fs"), manager.snippets.single().snippet.tags)
    }
}

private class FakeSnippetStore : SnippetStore {
    private val entries = mutableListOf<Snippet>()
    override fun all(): List<Snippet> = entries.toList()
    override fun put(snippet: Snippet) {
        val i = entries.indexOfFirst { it.id == snippet.id }
        if (i >= 0) entries[i] = snippet else entries += snippet
    }
    override fun remove(id: String) {
        entries.removeAll { it.id == id }
    }
}
