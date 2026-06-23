package app.skerry.shared.vault

import app.skerry.shared.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultMigrationTest {

    // Кладёт запись IDENTITY в старом формате сырого секрета: {id,label,auth:<secretJson>}.
    private fun seedLegacySecret(vault: Vault, id: String, label: String, secretJson: String) {
        vault.put(id, RecordType.IDENTITY, """{"id":"$id","label":"$label","auth":$secretJson}""".encodeToByteArray())
    }

    // Кладёт запись IDENTITY в старом формате учётки-обёртки: {id,label,username,credentialId}.
    private fun seedAccountWrapper(vault: Vault, id: String, label: String, username: String, credentialId: String) {
        vault.put(
            id,
            RecordType.IDENTITY,
            """{"id":"$id","label":"$label","username":"$username","credentialId":"$credentialId"}""".encodeToByteArray(),
        )
    }

    private fun liveIdentities(vault: Vault) =
        vault.records().filter { it.type == RecordType.IDENTITY && !it.deleted }

    @Test
    fun `legacy raw secret becomes a credential and host points straight at it`() {
        val vault = FakeVault()
        val hosts = FakeHostStore()
        seedLegacySecret(vault, "sec-1", "Prod key", """{"type":"private_key","privateKeyPem":"pem"}""")
        hosts.put(Host("h1", "prod", "1.2.3.4", username = "root", identityId = "sec-1"))

        val changed = VaultMigration(vault, hosts).migrate()

        assertTrue(changed)
        // Секрет переехал в keychain (тот же id), как CREDENTIAL.
        assertEquals(
            Credential("sec-1", "Prod key", CredentialSecret.PrivateKey("pem")),
            CredentialStore(vault).get("sec-1"),
        )
        // Хост ссылается прямо на секрет; legacy-указатель занулён.
        val host = hosts.all().single()
        assertEquals("sec-1", host.credentialId)
        assertNull(host.identityId)
        // Живых IDENTITY-записей не осталось.
        assertTrue(liveIdentities(vault).isEmpty())
    }

    @Test
    fun `two-level account wrapper collapses to a direct credential reference`() {
        val vault = FakeVault()
        val hosts = FakeHostStore()
        CredentialStore(vault).put(Credential("c1", "Key", CredentialSecret.Password("x")))
        seedAccountWrapper(vault, "a1", "root@a", "root", "c1")
        hosts.put(Host("h1", "a", "a.example", username = "root", identityId = "a1"))

        val changed = VaultMigration(vault, hosts).migrate()

        assertTrue(changed)
        val host = hosts.all().single()
        assertEquals("c1", host.credentialId)
        assertNull(host.identityId)
        // Обёртка снесена, живых IDENTITY-записей нет; сам credential жив.
        assertTrue(liveIdentities(vault).isEmpty())
        assertNotNull(CredentialStore(vault).get("c1"))
    }

    @Test
    fun `migration is idempotent`() {
        val vault = FakeVault()
        val hosts = FakeHostStore()
        seedLegacySecret(vault, "sec-1", "Key", """{"type":"password","password":"x"}""")
        hosts.put(Host("h1", "a", "a.example", username = "root", identityId = "sec-1"))

        assertTrue(VaultMigration(vault, hosts).migrate())
        val credIdAfterFirst = hosts.all().single().credentialId

        // Второй прогон ничего не меняет: нет IDENTITY-записей и identityId у хостов занулён.
        assertFalse(VaultMigration(vault, hosts).migrate())
        assertEquals(credIdAfterFirst, hosts.all().single().credentialId)
    }

    @Test
    fun `host without a binding is left untouched but the legacy secret still migrates`() {
        val vault = FakeVault()
        val hosts = FakeHostStore()
        seedLegacySecret(vault, "sec-1", "Key", """{"type":"password","password":"x"}""")
        hosts.put(Host("h1", "a", "a.example", username = "root", identityId = null))

        VaultMigration(vault, hosts).migrate()

        // Хост без привязки не получает credentialId.
        assertNull(hosts.all().single().credentialId)
        assertNull(hosts.all().single().identityId)
        // Секрет всё равно переезжает в keychain (он есть в vault, даже если хост его не использует).
        assertNotNull(CredentialStore(vault).get("sec-1"))
    }

    @Test
    fun `host identityId pointing straight at a live credential is resolved`() {
        // Прерванный прогон: шаг (a) уже перевёл секрет в CREDENTIAL, но шаг (c) не успел занулить
        // identityId хоста. Повторный прогон должен перепривязать хост на сам этот секрет.
        val vault = FakeVault()
        val hosts = FakeHostStore()
        CredentialStore(vault).put(Credential("c1", "Key", CredentialSecret.Password("x")))
        hosts.put(Host("h1", "a", "a.example", username = "root", identityId = "c1"))

        assertTrue(VaultMigration(vault, hosts).migrate())

        val host = hosts.all().single()
        assertEquals("c1", host.credentialId)
        assertNull(host.identityId)
        assertNotNull(CredentialStore(vault).get("c1"))
    }

    @Test
    fun `host bound to a vanished secret is unbound rather than left dangling`() {
        // Повреждение/прерванный прогон: legacy-указатель ни на обёртку, ни на живой секрет не
        // резолвится (секрет/обёртка пропали). Хост развязываем (спросит пароль), а не оставляем
        // висячую ссылку на несуществующий id.
        val vault = FakeVault()
        val hosts = FakeHostStore()
        hosts.put(Host("h1", "a", "a.example", username = "root", identityId = "gone"))

        VaultMigration(vault, hosts).migrate()

        val host = hosts.all().single()
        assertNull(host.credentialId)
        assertNull(host.identityId)
        // Повторный прогон стабилен (хост уже развязан).
        assertFalse(VaultMigration(vault, hosts).migrate())
    }

    @Test
    fun `account wrapper with a missing target credential is not collapsed and host is unbound`() {
        val vault = FakeVault()
        val hosts = FakeHostStore()
        // Обёртка ссылается на секрет "c1", которого нет в vault.
        seedAccountWrapper(vault, "a1", "root@a", "root", "c1")
        hosts.put(Host("h1", "a", "a.example", username = "root", identityId = "a1"))

        VaultMigration(vault, hosts).migrate()

        // Висячая ссылка не создана — хост развязан; обёртку без target не сносим (её свидетель связи).
        val host = hosts.all().single()
        assertNull(host.credentialId)
        assertNull(host.identityId)
    }

    @Test
    fun `record with both auth and credentialId is treated as a wrapper not a raw secret`() {
        // Дискриминатор: запись с credentialId — учётка, даже если у неё нашёлся бы auth. Её не
        // конвертируем в CREDENTIAL (иначе потеряли бы связь), а схлопываем как обёртку.
        val vault = FakeVault()
        val hosts = FakeHostStore()
        CredentialStore(vault).put(Credential("c1", "Key", CredentialSecret.Password("x")))
        vault.put(
            "a1",
            RecordType.IDENTITY,
            """{"id":"a1","label":"root@a","username":"root","credentialId":"c1","auth":{"type":"password","password":"x"}}""".encodeToByteArray(),
        )
        hosts.put(Host("h1", "a", "a.example", username = "root", identityId = "a1"))

        VaultMigration(vault, hosts).migrate()

        val host = hosts.all().single()
        assertEquals("c1", host.credentialId)
        assertNull(host.identityId)
        // "a1" НЕ стал отдельным credential (не сконвертирован как сырой секрет).
        assertNull(CredentialStore(vault).get("a1"))
        assertNotNull(CredentialStore(vault).get("c1"))
    }

    @Test
    fun `nothing to migrate returns false`() {
        val vault = FakeVault()
        val hosts = FakeHostStore()
        CredentialStore(vault).put(Credential("c1", "Key", CredentialSecret.Password("x")))
        hosts.put(Host("h1", "a", "a.example", username = "root", credentialId = "c1"))

        assertFalse(VaultMigration(vault, hosts).migrate())
    }
}
