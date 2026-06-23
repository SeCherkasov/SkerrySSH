package app.skerry.shared.vault

import app.skerry.shared.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class VaultMigrationTest {

    // Кладёт запись IDENTITY в старом формате (сырой секрет {id,label,auth}) прямо в vault.
    private fun seedLegacySecret(vault: Vault, id: String, label: String, secretJson: String) {
        vault.put(id, RecordType.IDENTITY, """{"id":"$id","label":"$label","auth":$secretJson}""".encodeToByteArray())
    }

    private fun ids(): () -> String {
        var n = 0
        return { "acct-${++n}" }
    }

    @Test
    fun `legacy secret becomes a credential and host gets a wrapping account`() {
        val vault = FakeVault()
        val hosts = FakeHostStore()
        seedLegacySecret(vault, "sec-1", "Prod key", """{"type":"private_key","privateKeyPem":"pem"}""")
        hosts.put(Host("h1", "prod", "1.2.3.4", username = "root", identityId = "sec-1"))

        val changed = VaultMigration(vault, hosts, ids()).migrate()

        assertTrue(changed)
        // Секрет переехал в keychain (тот же id), как CREDENTIAL.
        val cred = CredentialStore(vault).get("sec-1")
        assertEquals(Credential("sec-1", "Prod key", CredentialSecret.PrivateKey("pem")), cred)
        // Учётка-обёртка создана и привязана к хосту; ссылается на секрет.
        val host = hosts.all().single()
        val account = IdentityStore(vault).get(host.identityId!!)
        assertNotNull(account)
        assertEquals("root", account.username)
        assertEquals("sec-1", account.credentialId)
        // Старая запись больше не видна как учётка (она стала credential).
        assertNull(IdentityStore(vault).get("sec-1"))
    }

    @Test
    fun `hosts sharing username and secret share one account`() {
        val vault = FakeVault()
        val hosts = FakeHostStore()
        seedLegacySecret(vault, "sec-1", "Key", """{"type":"password","password":"x"}""")
        hosts.put(Host("h1", "a", "a.example", username = "deploy", identityId = "sec-1"))
        hosts.put(Host("h2", "b", "b.example", username = "deploy", identityId = "sec-1"))
        hosts.put(Host("h3", "c", "c.example", username = "root", identityId = "sec-1"))

        VaultMigration(vault, hosts, ids()).migrate()

        val byId = hosts.all().associateBy { it.id }
        // Одинаковые (username, секрет) делят учётку; разный username — отдельная.
        assertEquals(byId["h1"]!!.identityId, byId["h2"]!!.identityId)
        assertTrue(byId["h1"]!!.identityId != byId["h3"]!!.identityId)
        assertEquals(2, IdentityStore(vault).all().size)
    }

    @Test
    fun `migration is idempotent`() {
        val vault = FakeVault()
        val hosts = FakeHostStore()
        seedLegacySecret(vault, "sec-1", "Key", """{"type":"password","password":"x"}""")
        hosts.put(Host("h1", "a", "a.example", username = "root", identityId = "sec-1"))

        assertTrue(VaultMigration(vault, hosts, ids()).migrate())
        val accountsAfterFirst = IdentityStore(vault).all().map { it.id }.toSet()

        // Второй прогон ничего не меняет: нет записей старого формата.
        assertFalse(VaultMigration(vault, hosts, ids()).migrate())
        assertEquals(accountsAfterFirst, IdentityStore(vault).all().map { it.id }.toSet())
        assertEquals(1, CredentialStore(vault).all().size)
    }

    @Test
    fun `hosts without identity are left untouched`() {
        val vault = FakeVault()
        val hosts = FakeHostStore()
        seedLegacySecret(vault, "sec-1", "Key", """{"type":"password","password":"x"}""")
        hosts.put(Host("h1", "a", "a.example", username = "root", identityId = null))

        VaultMigration(vault, hosts, ids()).migrate()

        assertNull(hosts.all().single().identityId)
        // Секрет всё равно переезжает в keychain (он есть в vault, даже если хост его не использует).
        assertNotNull(CredentialStore(vault).get("sec-1"))
    }

    @Test
    fun `interrupted run is completed - host pointing straight at a credential gets an account`() {
        // Симулируем падение прошлого прогона: шаг 1 уже перевёл секрет в CREDENTIAL, но шаг 2
        // (перепривязка хоста на учётку) не успел — хост ссылается прямо на CREDENTIAL.
        val vault = FakeVault()
        val hosts = FakeHostStore()
        CredentialStore(vault).put(Credential("sec-1", "Key", CredentialSecret.Password("x")))
        hosts.put(Host("h1", "a", "a.example", username = "root", identityId = "sec-1"))

        val changed = VaultMigration(vault, hosts, ids()).migrate()

        assertTrue(changed)
        val host = hosts.all().single()
        // Хост теперь ссылается на учётку (IDENTITY), а не на сам секрет (CREDENTIAL).
        assertTrue(host.identityId != "sec-1")
        val account = IdentityStore(vault).get(host.identityId!!)
        assertNotNull(account)
        assertEquals("sec-1", account.credentialId)
        assertEquals("root", account.username)
        // Повторный прогон идемпотентен.
        assertFalse(VaultMigration(vault, hosts, ids()).migrate())
    }

    @Test
    fun `nothing to migrate returns false`() {
        val vault = FakeVault()
        val hosts = FakeHostStore()
        CredentialStore(vault).put(Credential("c1", "Key", CredentialSecret.Password("x")))
        IdentityStore(vault).put(Identity("a1", "Acct", "root", "c1"))

        assertFalse(VaultMigration(vault, hosts, ids()).migrate())
    }
}
