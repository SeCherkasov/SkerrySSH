package app.skerry.shared.sync

import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.CredentialStore
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.TrashEntry
import app.skerry.shared.vault.TrashStore
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord
import app.skerry.shared.vault.VaultRecordCodec
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.shared.vault.trashRecordId
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * File-backed credentials stay on the device that made them (issue #174): [CredentialSecret.KeyFile]
 * holds a *location* — a filesystem path on desktop, a `content://` Uri on Android — and a location
 * is meaningless anywhere else. The rule lives in [DeviceLocalRecords], which opens the payload
 * [SyncSettings] can't see, and applies to both directions plus the trash snapshot of such a record.
 */
class DeviceLocalCredentialSyncTest {

    private val password = "correct horse battery staple"
    private val session = SyncSession("acct", "access", "refresh")

    private fun VaultRecord.toRemote() = RemoteRecord(id, type.name, version, updatedAt, deviceId, deleted, blob)

    private fun newVault(deviceId: String) = FileVault(
        path = Files.createTempDirectory("skerry-devlocal-$deviceId").resolve("vault.json").toString().toPath(),
        crypto = IonspinVaultCrypto(),
        deviceId = deviceId,
        fileSystem = FileSystem.SYSTEM,
        now = { "2026-06-30T00:00:00Z" },
    )

    private fun engine(client: SyncClient, vault: Vault) = SyncEngine(
        client,
        vault,
        InMemorySyncStateStore(),
        deviceLocal = DeviceLocalRecords(vault),
    )

    private val keyFile = Credential(
        id = "cred-file",
        label = "tsh",
        secret = CredentialSecret.KeyFile(
            privateKeyRef = "/home/user/.tsh/keys/id",
            certificateRef = "/home/user/.tsh/keys/id-cert.pub",
            passphrase = null,
        ),
    )
    private val inVault = Credential(
        id = "cred-pem",
        label = "laptop",
        secret = CredentialSecret.PrivateKey(privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----"),
    )

    @Test
    fun `a file-backed credential is never pushed while the other secrets are`() = runBlocking {
        initializeVaultCrypto()
        val vault = newVault("devA")
        vault.create(password.toCharArray())
        val credentials = CredentialStore(vault)
        credentials.put(keyFile)
        credentials.put(inVault)
        credentials.put(
            Credential("cred-pass", "prod", CredentialSecret.Password("hunter2")),
        )
        credentials.put(
            Credential("cred-cert", "ca", CredentialSecret.Certificate("-----BEGIN-----", "ssh-ed25519-cert-v01@openssh.com AAAA")),
        )

        val client = FakeSyncClient()
        engine(client, vault).sync(session)

        val pushedIds = client.pushed.map { it.id }.toSet()
        assertFalse("cred-file" in pushedIds, "a KeyFile credential must not reach the server")
        assertTrue("cred-pem" in pushedIds, "a PrivateKey credential keeps syncing")
        assertTrue("cred-pass" in pushedIds, "a Password credential keeps syncing")
        assertTrue("cred-cert" in pushedIds, "a Certificate credential keeps syncing")
    }

    @Test
    fun `an incoming file-backed credential is not applied`() = runBlocking {
        initializeVaultCrypto()
        // Source A is an older client that still pushes the ref; B unwraps with the same account key.
        val source = newVault("devA")
        source.create(password.toCharArray())
        CredentialStore(source).put(keyFile)
        CredentialStore(source).put(inVault)
        val remote = source.records().filter { it.type == RecordType.CREDENTIAL }.map { it.toRemote() }

        val receiver = newVault("devB")
        receiver.create(password.toCharArray())
        receiver.unlockWithDataKey(source.exportDataKey()!!)

        engine(FakeSyncClient(serverRecords = remote), receiver).sync(session)

        assertFalse(receiver.records().any { it.id == "cred-file" }, "an incoming KeyFile credential must not be merged")
        assertTrue(receiver.records().any { it.id == "cred-pem" }, "an incoming PrivateKey credential still merges")
    }

    @Test
    fun `the trash snapshot of a file-backed credential is not pushed`() = runBlocking {
        initializeVaultCrypto()
        val vault = newVault("devA")
        vault.create(password.toCharArray())
        val credentials = CredentialStore(vault, TrashStore(vault))
        credentials.put(keyFile)
        credentials.put(inVault)
        credentials.remove(keyFile.id)
        credentials.remove(inVault.id)

        val client = FakeSyncClient()
        engine(client, vault).sync(session)

        val pushedIds = client.pushed.map { it.id }.toSet()
        assertFalse(
            trashRecordId(RecordType.CREDENTIAL, "cred-file") in pushedIds,
            "the snapshot carries the same ref and must not travel either",
        )
        assertTrue(
            trashRecordId(RecordType.CREDENTIAL, "cred-pem") in pushedIds,
            "the snapshot of a synced credential keeps syncing",
        )
        // The tombstone itself still travels: it is what clears an already-synced copy on device B.
        assertTrue(
            client.pushed.any { it.id == "cred-file" && it.deleted },
            "the deletion must still propagate",
        )
    }

    @Test
    fun `an incoming trash snapshot of a file-backed credential is not applied either`() = runBlocking {
        initializeVaultCrypto()
        val source = newVault("devA")
        source.create(password.toCharArray())
        val sourceCredentials = CredentialStore(source, TrashStore(source))
        sourceCredentials.put(keyFile)
        sourceCredentials.put(inVault)
        sourceCredentials.remove(keyFile.id)
        sourceCredentials.remove(inVault.id)
        val remote = source.records().filter { it.type == RecordType.TRASH }.map { it.toRemote() }

        val receiver = newVault("devB")
        receiver.create(password.toCharArray())
        receiver.unlockWithDataKey(source.exportDataKey()!!)

        engine(FakeSyncClient(serverRecords = remote), receiver).sync(session)

        val landed = receiver.records().map { it.id }.toSet()
        assertFalse(trashRecordId(RecordType.CREDENTIAL, "cred-file") in landed, "the ref must not arrive in the trash either")
        assertTrue(trashRecordId(RecordType.CREDENTIAL, "cred-pem") in landed, "an ordinary snapshot still arrives")
    }

    /**
     * A device that ran a release without this rule already holds the record. The newer version
     * arriving from its origin must be dropped rather than merged — and the stale local copy must be
     * left exactly as it is: an incoming record that is refused is not a deletion.
     */
    @Test
    fun `an update to an already-synced file-backed credential is refused without touching the local copy`() = runBlocking {
        initializeVaultCrypto()
        val source = newVault("devA")
        source.create(password.toCharArray())
        CredentialStore(source).put(keyFile)
        val first = source.records().first { it.id == "cred-file" }
        CredentialStore(source).put(keyFile.copy(label = "renamed"))
        val second = source.records().first { it.id == "cred-file" } // version 2 — wins LWW

        val receiver = newVault("devB")
        receiver.create(password.toCharArray())
        receiver.unlockWithDataKey(source.exportDataKey()!!)
        receiver.mergeRemote(listOf(first)) // what the older release already put here
        assertEquals(first.version, receiver.records().first { it.id == "cred-file" }.version)

        val client = FakeSyncClient(serverRecords = listOf(second.toRemote()))
        engine(client, receiver).sync(session)

        assertEquals(
            first.version,
            receiver.records().first { it.id == "cred-file" }.version,
            "the refused update must not be applied",
        )
        assertFalse(client.pushed.any { it.id == "cred-file" }, "nor must the stale local copy be pushed back")
    }

    /**
     * The two answers the payload cannot give. Outgoing they differ: a blob this device cannot open
     * authenticates for nobody, so pushing it can only add server garbage or overwrite a readable
     * copy elsewhere. Incoming they must not — [Vault.mergeRemote] rejects such a record and REPORTS
     * it, and swallowing it here would turn a tampering signal into silence.
     */
    @Test
    fun `an unreadable payload blocks the push but never the merge`() = runBlocking {
        initializeVaultCrypto()
        val vault = newVault("devA")
        vault.create(password.toCharArray())
        CredentialStore(vault).put(inVault)
        val readable = vault.records().first { it.id == "cred-pem" }
        // What an adopted account key leaves behind: a live record whose blob no longer authenticates.
        val unreadable = readable.copy(id = "cred-stale", blob = readable.blob.copyOf().also { it[0] = (it[0] + 1).toByte() })
        val subject = DeviceLocalRecords(vault)

        assertTrue(subject.blocksOutgoing(unreadable), "an unopenable payload must not be pushed")
        assertFalse(subject.blocksIncoming(unreadable), "but the merge must still see it and reject it")
        assertFalse(subject.blocksOutgoing(readable), "a readable ordinary credential is unaffected")
    }

    /**
     * What a reconcile spares is a narrower question than what the push refuses. Only a record the
     * server can never hand back — one held here because of what its payload says — may survive the
     * clear; a blob that no longer opens is dead weight the clear has always been the one to remove.
     */
    @Test
    fun `the clear spares only what is genuinely device-local, not what merely cannot be read`() = runBlocking {
        initializeVaultCrypto()
        val vault = newVault("devA")
        vault.create(password.toCharArray())
        val credentials = CredentialStore(vault)
        credentials.put(keyFile)
        credentials.put(inVault)
        val subject = DeviceLocalRecords(vault)
        val fileBacked = vault.records().first { it.id == "cred-file" }
        val ordinary = vault.records().first { it.id == "cred-pem" }
        val unreadable = ordinary.copy(id = "cred-stale", blob = ordinary.blob.copyOf().also { it[0] = (it[0] + 1).toByte() })

        assertTrue(subject.survivesClear(fileBacked), "nothing else holds this secret — the clear must spare it")
        assertFalse(subject.survivesClear(ordinary), "a synced credential comes back from the re-pull")
        assertTrue(subject.blocksOutgoing(unreadable), "an unopenable blob is still not pushed")
        assertFalse(
            subject.survivesClear(unreadable),
            "but it must not survive the clear: the server may hold a readable copy of that id",
        )
    }

    @Test
    fun `a payload this build cannot parse keeps syncing`() = runBlocking {
        initializeVaultCrypto()
        val vault = newVault("devA")
        vault.create(password.toCharArray())
        // A schema a newer client wrote: it decrypts, it does not parse as a Credential. That is not
        // evidence of a file-backed key, so it must not be stranded here.
        vault.put("cred-future", RecordType.CREDENTIAL, """{"id":"cred-future","shape":"unknown"}""".encodeToByteArray())
        val subject = DeviceLocalRecords(vault)
        val record = vault.records().first { it.id == "cred-future" }

        assertFalse(subject.blocksOutgoing(record))
        assertFalse(subject.blocksIncoming(record))
    }

    /**
     * The byte scan that spares the parse is a NEGATIVE only: absent, the discriminator settles the
     * question; present, it settles nothing. A label or a note is in the same payload, so treating a
     * hit as the answer would strand an ordinary password whose owner named it after the file it
     * replaced.
     */
    @Test
    fun `a credential that merely mentions the wire name in its text still syncs`() = runBlocking {
        initializeVaultCrypto()
        val vault = newVault("devA")
        vault.create(password.toCharArray())
        CredentialStore(vault).put(
            Credential(
                id = "cred-named",
                label = "key_file",
                secret = CredentialSecret.Password("hunter2"),
                note = "replaced the key_file one",
            ),
        )
        val subject = DeviceLocalRecords(vault)
        val record = vault.records().first { it.id == "cred-named" }

        assertFalse(subject.blocksOutgoing(record))
        assertFalse(subject.blocksIncoming(record))
        assertFalse(subject.survivesClear(record))
    }

    /**
     * The byte scan reads what the encoder writes; the decoder reads more. `\u006bey_file` is the same
     * discriminator to kotlinx-serialization and different bytes to a scan, so a device holding the
     * account key could otherwise seal a record that passes the filter and decodes as file-backed —
     * the exact direction the exclusion exists to defend.
     */
    @Test
    fun `an incoming credential that spells the wire name in escapes is still not applied`() = runBlocking {
        initializeVaultCrypto()
        val source = newVault("devA")
        source.create(password.toCharArray())
        source.put(
            "cred-craft",
            RecordType.CREDENTIAL,
            ("""{"id":"cred-craft","label":"prod","secret":{"type":"\u006bey_file",""" +
                """"privateKeyRef":"/home/victim/.ssh/id_ed25519"}}""").encodeToByteArray(),
        )
        val remote = source.records().map { it.toRemote() }

        val receiver = newVault("devB")
        receiver.create(password.toCharArray())
        receiver.unlockWithDataKey(source.exportDataKey()!!)

        engine(FakeSyncClient(serverRecords = remote), receiver).sync(session)

        assertFalse(
            receiver.records().any { it.id == "cred-craft" },
            "an escaped discriminator is the same secret to the parser and must be refused as one",
        )
    }

    /**
     * The id of a trash record is plaintext metadata; what [TrashStore] restores by is the origin
     * type inside the entry. Judging on the id would let a record filed under `skerry.trash:HOST:…`
     * carry a file-backed credential past the filter and be restored as one.
     */
    @Test
    fun `an incoming trash record filed under another type is judged by what it holds`() = runBlocking {
        initializeVaultCrypto()
        val source = newVault("devA")
        source.create(password.toCharArray())
        val entry = TrashEntry(
            originId = "cred-x",
            originType = RecordType.CREDENTIAL,
            label = "prod",
            deletedAt = 0,
            originVersion = 1,
            payload = VaultRecordCodec.json.encodeToString(Credential.serializer(), keyFile),
        )
        // Filed under HOST on purpose — only a device holding the account key can write this.
        val misfiled = trashRecordId(RecordType.HOST, "cred-x")
        source.put(
            misfiled,
            RecordType.TRASH,
            VaultRecordCodec.json.encodeToString(TrashEntry.serializer(), entry).encodeToByteArray(),
        )
        val remote = source.records().map { it.toRemote() }

        val receiver = newVault("devB")
        receiver.create(password.toCharArray())
        receiver.unlockWithDataKey(source.exportDataKey()!!)

        engine(FakeSyncClient(serverRecords = remote), receiver).sync(session)

        assertFalse(
            receiver.records().any { it.id == misfiled },
            "the entry names a credential, so the snapshot is one whatever its id says",
        )
    }

    @Test
    fun `a trash snapshot this build cannot parse keeps syncing`() = runBlocking {
        initializeVaultCrypto()
        val vault = newVault("devA")
        vault.create(password.toCharArray())
        // The snapshot opens and the entry parses; the credential it wrapped does not. Same reasoning
        // as the live record: an unknown schema is not evidence of a file-backed key.
        val entry = TrashEntry(
            originId = "cred-future",
            originType = RecordType.CREDENTIAL,
            label = "x",
            deletedAt = 0,
            originVersion = 1,
            payload = """{"id":"cred-future","shape":"unknown"}""",
        )
        val id = entry.recordId
        vault.put(
            id,
            RecordType.TRASH,
            VaultRecordCodec.json.encodeToString(TrashEntry.serializer(), entry).encodeToByteArray(),
        )
        val subject = DeviceLocalRecords(vault)
        val record = vault.records().first { it.id == id }

        assertFalse(subject.blocksOutgoing(record))
        assertFalse(subject.blocksIncoming(record))
        assertFalse(subject.survivesClear(record))
    }

    @Test
    fun `the push filter reads the vault under one hold, so an auto-lock cannot land inside it`() = runBlocking {
        initializeVaultCrypto()
        val vault = newVault("devA")
        vault.create(password.toCharArray())
        CredentialStore(vault).put(keyFile)
        CredentialStore(vault).put(inVault)
        val watched = TransactionWatchingVault(vault)

        engine(FakeSyncClient(), watched).sync(session)

        assertTrue(watched.sawRecords, "the engine must list the records")
        assertTrue(
            watched.readsOutsideTransaction.isEmpty(),
            "every payload read must happen inside the transaction: ${watched.readsOutsideTransaction}",
        )
    }

    /** Records whether each payload read happened while the engine held the vault. */
    private class TransactionWatchingVault(private val delegate: Vault) : Vault by delegate {
        private var depth = 0
        var sawRecords = false
            private set
        val readsOutsideTransaction = mutableListOf<String>()

        override fun <T> transaction(block: () -> T): T {
            depth++
            try {
                return delegate.transaction(block)
            } finally {
                depth--
            }
        }

        override fun records(): List<VaultRecord> {
            sawRecords = true
            return delegate.records()
        }

        override fun openRecordPayload(record: VaultRecord): ByteArray? {
            if (depth == 0) readsOutsideTransaction += record.id
            return delegate.openRecordPayload(record)
        }
    }

    @Test
    fun `the verdict is decided by the payload and leaves everything else alone`() = runBlocking {
        initializeVaultCrypto()
        val vault = newVault("devA")
        vault.create(password.toCharArray())
        CredentialStore(vault, TrashStore(vault)).also {
            it.put(keyFile)
            it.put(inVault)
        }
        vault.put("h1", RecordType.HOST, """{"id":"h1"}""".encodeToByteArray())
        val subject = DeviceLocalRecords(vault)
        val record = { id: String -> vault.records().first { it.id == id } }

        assertTrue(subject.blocksOutgoing(record("cred-file")))
        assertTrue(subject.blocksIncoming(record("cred-file")))
        assertFalse(subject.blocksOutgoing(record("cred-pem")))
        assertFalse(subject.blocksIncoming(record("cred-pem")))
        assertFalse(subject.blocksOutgoing(record("h1")), "a host is never device-local, whatever its payload")
        assertEquals(2, vault.records().count { it.type == RecordType.CREDENTIAL })
    }
}
