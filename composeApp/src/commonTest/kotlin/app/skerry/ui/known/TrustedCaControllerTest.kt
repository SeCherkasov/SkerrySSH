package app.skerry.ui.known

import app.skerry.shared.ssh.CaKeyParser
import app.skerry.shared.ssh.ParsedCaKey
import app.skerry.shared.ssh.TrustedCa
import app.skerry.shared.ssh.TrustedCaStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val KEY_LINE = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5 ca@example.com"
private const val CA_LINE = "@cert-authority *.prod.example.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5 prod CA"

class TrustedCaControllerTest {

    private fun controller(
        store: FakeCaStore = FakeCaStore(),
        parser: CaKeyParser = FakeParser(),
    ) = TrustedCaController(store, parser, newId = { "ca-${store.all().size + 1}" }, now = { "2026-07-27T10:00:00Z" })

    @Test
    fun `adds a pasted key under the pattern from the form`() {
        val store = FakeCaStore()
        val result = controller(store).add(KEY_LINE, hostPattern = "*.prod.example.com")

        assertIs<AddCaResult.Added>(result)
        val added = store.all().single()
        assertEquals("*.prod.example.com", added.hostPattern)
        assertEquals("ssh-ed25519", added.keyType)
        assertEquals("SHA256:FAKE", added.fingerprint)
        assertEquals("2026-07-27T10:00:00Z", added.addedAt)
    }

    @Test
    fun `takes the pattern from a pasted cert-authority line when the field is empty`() {
        val store = FakeCaStore()
        controller(store).add(CA_LINE, hostPattern = "")
        assertEquals("*.prod.example.com", store.all().single().hostPattern)
    }

    @Test
    fun `an explicit pattern wins over the one in the pasted line`() {
        val store = FakeCaStore()
        controller(store).add(CA_LINE, hostPattern = "*.staging.example.com")
        assertEquals("*.staging.example.com", store.all().single().hostPattern)
    }

    @Test
    fun `labels the entry with the key comment when no label is given`() {
        val store = FakeCaStore()
        controller(store).add(KEY_LINE, hostPattern = "*.example.com")
        assertEquals("ca@example.com", store.all().single().label)
    }

    @Test
    fun `an unusable key is refused and nothing is stored`() {
        val store = FakeCaStore()
        val result = controller(store, parser = { null }).add("garbage", hostPattern = "*.example.com")

        assertEquals(AddCaResult.InvalidKey, result)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `a missing pattern is refused`() {
        val store = FakeCaStore()
        assertEquals(AddCaResult.MissingPattern, controller(store).add(KEY_LINE, hostPattern = "  "))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `a pattern that can never match anything is refused`() {
        // Only negations: OpenSSH semantics make this match no host at all, so storing it would be
        // a CA entry that silently does nothing.
        val store = FakeCaStore()
        assertEquals(AddCaResult.InvalidPattern, controller(store).add(KEY_LINE, hostPattern = "!admin.example.com"))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `the same key under the same pattern is not added twice`() {
        val store = FakeCaStore()
        val controller = controller(store)
        controller.add(KEY_LINE, hostPattern = "*.prod.example.com")

        assertEquals(AddCaResult.Duplicate, controller.add(KEY_LINE, hostPattern = "*.prod.example.com"))
        assertEquals(1, store.all().size)
    }

    @Test
    fun `the same key can cover a second pattern`() {
        val store = FakeCaStore()
        val controller = controller(store)
        controller.add(KEY_LINE, hostPattern = "*.prod.example.com")
        controller.add(KEY_LINE, hostPattern = "*.staging.example.com")
        assertEquals(2, store.all().size)
    }

    @Test
    fun `patterns are stored in canonical form`() {
        val store = FakeCaStore()
        controller(store).add(KEY_LINE, hostPattern = " *.Prod.Example.COM , DB.example.com ")
        assertEquals("*.prod.example.com,db.example.com", store.all().single().hostPattern)
    }

    @Test
    fun `the same coverage written differently is still a duplicate`() {
        val store = FakeCaStore()
        val controller = controller(store)
        controller.add(KEY_LINE, hostPattern = "*.prod.example.com")

        assertEquals(AddCaResult.Duplicate, controller.add(KEY_LINE, hostPattern = " *.PROD.example.com "))
        assertEquals(1, store.all().size)
    }

    @Test
    fun `a pattern too long to ever match is refused`() {
        val store = FakeCaStore()
        val result = controller(store).add(KEY_LINE, hostPattern = "a".repeat(400))
        assertEquals(AddCaResult.InvalidPattern, result)
    }

    @Test
    fun `a write that did not reach the vault is reported, not called success`() {
        // The vault auto-locks between the duplicate check and the write; the store drops it.
        val store = object : FakeCaStore() {
            override fun put(ca: TrustedCa) {}
        }
        assertEquals(AddCaResult.NotStored, controller(store).add(KEY_LINE, hostPattern = "*.example.com"))
    }

    @Test
    fun `remove forgets the authority`() {
        val store = FakeCaStore()
        val controller = controller(store)
        val added = controller.add(KEY_LINE, hostPattern = "*.example.com") as AddCaResult.Added

        controller.remove(added.id)

        assertTrue(store.all().isEmpty())
        assertTrue(controller.authorities.isEmpty())
    }

    @Test
    fun `refresh picks up entries written outside the controller`() {
        // Sync brings a CA trusted on another device while this screen is open.
        val store = FakeCaStore()
        val controller = controller(store)
        store.put(TrustedCa("ca-x", "*.example.com", "ssh-ed25519", "AAAA", "SHA256:OTHER"))

        controller.refresh()

        assertEquals(listOf("ca-x"), controller.authorities.map { it.id })
    }
}

private open class FakeCaStore : TrustedCaStore {
    private val entries = mutableListOf<TrustedCa>()
    override fun all(): List<TrustedCa> = entries.toList()
    override fun put(ca: TrustedCa) {
        entries.removeAll { it.id == ca.id }
        entries += ca
    }

    override fun remove(id: String) {
        entries.removeAll { it.id == id }
    }
}

/** Parses the two shapes the tests paste, without needing real crypto. */
private class FakeParser : CaKeyParser {
    override fun parse(text: String): ParsedCaKey? {
        val fields = text.trim().split(Regex("\\s+"))
        return when {
            fields.firstOrNull() == "@cert-authority" ->
                ParsedCaKey("ssh-ed25519", "AAAAC3NzaC1lZDI1NTE5", "SHA256:FAKE", fields[1], fields.drop(4).joinToString(" "))
            fields.size >= 2 && fields[0].startsWith("ssh-") ->
                ParsedCaKey("ssh-ed25519", "AAAAC3NzaC1lZDI1NTE5", "SHA256:FAKE", null, fields.drop(2).joinToString(" "))
            else -> null
        }
    }
}
