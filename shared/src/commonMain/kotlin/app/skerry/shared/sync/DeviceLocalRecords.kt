package app.skerry.shared.sync

import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.TrashEntry
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord
import app.skerry.shared.vault.VaultRecordCodec
import kotlinx.serialization.KSerializer

/**
 * The half of the sync filter that has to open the payload. [SyncSettings] decides by plaintext
 * metadata — type and id — which is all a zero-knowledge engine needs for a per-type switch; what
 * makes a record device-local is inside the encrypted blob.
 *
 * The three questions are deliberately not one. Outgoing, the answer has to be safe when the payload
 * cannot be read at all; incoming, it must not swallow a record [Vault.mergeRemote] would have
 * counted as tampered; and what a reconcile spares is narrower than either. See [DeviceLocalRecords]
 * for the rule itself.
 */
interface DeviceLocalFilter {

    /** Whether [record] may not leave this device: what the push drops. */
    fun blocksOutgoing(record: VaultRecord): Boolean

    /** Whether [record], arriving from another device, must not be applied. */
    fun blocksIncoming(record: VaultRecord): Boolean

    /**
     * Whether [record] must survive a reconcile's [Vault.clearRecords]. Narrower than
     * [blocksOutgoing] on purpose, and not a synonym for it: the clear is followed by a full re-pull,
     * so sparing is right only for a record the server can never hand back — one this device refuses
     * to push because of what its payload holds. Everything else the push merely *could* not judge
     * belongs in the clear, where it always was.
     */
    fun survivesClear(record: VaultRecord): Boolean

    /** Nothing is device-local: engines over a vault that cannot hold such a record, and tests. */
    object None : DeviceLocalFilter {
        override fun blocksOutgoing(record: VaultRecord): Boolean = false
        override fun blocksIncoming(record: VaultRecord): Boolean = false
        override fun survivesClear(record: VaultRecord): Boolean = false
    }
}

/**
 * Records that must stay on the device that made them: today exactly one thing, a
 * [CredentialSecret.KeyFile] credential. Its `privateKeyRef`/`certificateRef` are a *location* — a
 * filesystem path on desktop, a `content://` document Uri on Android — and a location does not
 * survive the trip: a desktop path means nothing on a phone, and a `content://` Uri is a per-app
 * grant that resolves on no other device at all, not even another Android one. Pushed anyway, it
 * lands in the other device's keychain as an entry that can never connect. So it never leaves, and
 * one arriving from an older client is never applied (issue #174).
 *
 * The exclusion is hard, not a setting: the one layout where the ref would resolve — two desktops
 * with the same `~/.ssh` — is a coincidence of paths, and on the far machine the same path may hold
 * a different key. A switch offering that would be offering to authenticate with whatever is there.
 *
 * Deletion is unaffected: a tombstone carries no payload, travels as always, and is what clears a
 * copy an older client already synced. The trash *snapshot* of such a credential holds the same ref
 * in its payload, so it is excluded on the same grounds.
 *
 * Two things this does NOT do, both deliberate. A record a previous release already pushed stays on
 * the server until the user deletes the credential — the same "off doesn't delete" semantics
 * [SyncSettings] documents, and the only alternative would be tombstoning a record the user still
 * has, which deletes it here too. And the tombstone of a credential whose live version never
 * travelled tells a server operator that this id was file-backed; suppressing it would cost the
 * cleanup path, which is worth more than hiding one bit from a server that already sees every id.
 */
class DeviceLocalRecords(private val vault: Vault) : DeviceLocalFilter {

    /**
     * Outgoing, an unreadable payload counts as device-local too. A blob this device cannot open is
     * one no device can: it authenticates for nobody (a leftover sealed under the account key this
     * one replaced), so pushing it can only add a record the server keeps for good and, through LWW,
     * overwrite a copy elsewhere that still opens. "Cannot tell" must not resolve to "upload it" on
     * the side where uploading is the irreversible move.
     */
    override fun blocksOutgoing(record: VaultRecord): Boolean = verdict(record) != Verdict.SHAREABLE

    /**
     * Incoming, only positive evidence counts. An incoming blob that does not open is a forged,
     * replayed or foreign-key record, and [Vault.mergeRemote] rejects it *and reports it* — dropping
     * it here instead would turn a tampering signal ([SyncOutcome.rejected]) into silence.
     */
    override fun blocksIncoming(record: VaultRecord): Boolean = verdict(record) == Verdict.DEVICE_LOCAL

    /**
     * The clear asks the third question, and it is not [blocksOutgoing]. A blob that does not open is
     * not device-local, it is dead — a leftover of the account key this vault replaced
     * ([Vault.adoptDataKey] keeps the records and drops the key that opened them), and the clear is
     * the only thing that has ever removed one. Spared, it would keep its id and version and squat
     * there: the server's readable copy comes back from the re-pull at a lower version, loses the LWW
     * and never lands, so the account's credential is gone from this device for good.
     */
    override fun survivesClear(record: VaultRecord): Boolean = verdict(record) == Verdict.DEVICE_LOCAL

    private enum class Verdict { DEVICE_LOCAL, SHAREABLE, UNREADABLE }

    /**
     * A record is judged on its payload, so the vault has to be open: [Vault.openRecordPayload]
     * throws on a locked one and the throw is left alone. Swallowing it would answer "shareable" for
     * a reason that says nothing about the record — the whole cycle failing is the honest outcome,
     * and [SyncEngine] holds the vault for the length of the push filter so it cannot happen there.
     */
    private fun verdict(record: VaultRecord): Verdict {
        // A tombstone carries no payload to judge, and is exactly what must keep travelling.
        if (record.deleted) return Verdict.SHAREABLE
        // Every trash record is opened, not just the ones whose id says CREDENTIAL: the id is
        // plaintext metadata, and [TrashStore.restore] restores by the origin type inside the
        // *entry*. A record filed under `skerry.trash:HOST:…` whose entry says CREDENTIAL would
        // otherwise pass unopened and be restored as the very credential this refuses.
        if (record.type != RecordType.CREDENTIAL && record.type != RecordType.TRASH) return Verdict.SHAREABLE
        // Wiped on the way out: this is a full credential in the clear — password, PEM, passphrase —
        // read to answer one bit, on a loop that runs unattended (FileVault.authenticates does the
        // same). The String the parse makes is the accepted JVM limitation named in [Credential].
        val payload = vault.openRecordPayload(record) ?: return Verdict.UNREADABLE
        val credential = try {
            // A sound negative first, on the bytes. Every [CredentialSecret.KeyFile] carries its
            // `@SerialName` verbatim in the JSON, so a payload without those bytes cannot be one and
            // is answered without ever building a String. That matters here and not elsewhere: this
            // runs for every credential AND every credential trash snapshot on every push cycle, and
            // a snapshot's payload is a *deleted* secret in full, which would otherwise be
            // re-materialised as unwipeable Strings all through the retention window.
            //
            // Sound only against what the writer emits, and the reader accepts more: the lexer
            // unescapes `\uXXXX` inside a string, discriminator included, so `\u006bey_file` parses
            // as this very secret while spelling none of its bytes. A device holding the account key
            // could seal exactly that. `\u` is the only way JSON can write an ASCII letter without
            // writing it, so a payload carrying one is not answered by a scan at all — it goes the
            // long way, like a real file-backed secret does.
            if (!payload.holds(UNICODE_ESCAPE) && !payload.holds(KEY_FILE_MARK)) return Verdict.SHAREABLE
            if (record.type == RecordType.TRASH) {
                decode(payload, TrashEntry.serializer())
                    ?.takeIf { it.originType == RecordType.CREDENTIAL }
                    ?.let { parse(it.payload) }
            } else {
                decode(payload, Credential.serializer())
            }
        } finally {
            payload.fill(0)
        }
        // A payload that opened but did not parse is a schema this build does not know, not evidence
        // of a file-backed key: it keeps syncing, so a newer client's record is not stranded here.
        return if (credential?.secret is CredentialSecret.KeyFile) Verdict.DEVICE_LOCAL else Verdict.SHAREABLE
    }

    /** [TrashEntry.payload] is already the deleted record's plaintext JSON — no byte round trip. */
    private fun parse(payload: String): Credential? =
        runCatching { VaultRecordCodec.json.decodeFromString(Credential.serializer(), payload) }.getOrNull()

    private fun <T> decode(payload: ByteArray, serializer: KSerializer<T>): T? =
        runCatching { VaultRecordCodec.json.decodeFromString(serializer, payload.decodeToString()) }.getOrNull()

    /**
     * [mark] is spelled out per call rather than held in a shared array: the one thing this file also
     * does to a [ByteArray] is `fill(0)`, and a wiped needle would quietly answer "no payload holds
     * it" — which is the whole filter saying yes to everything.
     */
    private fun ByteArray.holds(mark: String): Boolean {
        val needle = mark.encodeToByteArray()
        if (needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
            return true
        }
        return false
    }
}

/**
 * The discriminator [CredentialSecret.KeyFile] is written under — a wire name fixed by `@SerialName`,
 * so it survives package renames and R8 exactly as the payloads that carry it do. Present in the
 * plaintext of every file-backed secret this build writes; a hit proves nothing on its own, since a
 * label, a note or a PEM comment sits in the same blob.
 */
private const val KEY_FILE_MARK = "key_file"

/** The one JSON spelling that hides an ASCII letter from a byte scan. */
private const val UNICODE_ESCAPE = "\\u"
