package app.skerry.shared.vault

/** Encrypted snapshot with first-id lookup, preserving file order and legacy duplicate-id behavior. */
internal class IndexedVaultRecords(records: List<VaultRecord> = emptyList()) : AbstractList<VaultRecord>() {
    private val entries = records.toList()
    val positions: Map<String, Int> = buildMap {
        this@IndexedVaultRecords.entries.forEachIndexed { index, record -> if (record.id !in this) put(record.id, index) }
    }
    override val size: Int get() = entries.size
    override fun get(index: Int): VaultRecord = entries[index]
    fun indexOfId(id: String): Int = positions[id] ?: -1
}

/** Caller-owned plaintext for one upsert. No generated toString: payloads can contain secrets. */
class VaultWrite(val id: String, val type: RecordType, val payload: ByteArray)

/** Stages a whole local batch; no disk/cache mutation until every write has validated and sealed. */
internal fun IndexedVaultRecords.sealWrites(
    writes: List<VaultWrite>,
    crypto: VaultCrypto,
    key: DataKey,
    deviceId: String,
    now: () -> String,
): List<VaultRecord> {
    val working = toMutableList()
    val positions = this.positions.toMutableMap()
    for (write in writes) {
        val index = positions[write.id]
        val current = index?.let { working[it] }
        require(current == null || current.type == write.type) { "record id already holds another type" }
        val version = (current?.version ?: 0L) + 1
        val at = now()
        val blob = crypto.seal(key, write.payload, recordAad(write.id, write.type, version, deviceId, false, at))
        val record = VaultRecord(write.id, write.type, version, at, deviceId, false, blob)
        if (index != null) working[index] = record else {
            positions[write.id] = working.size
            working += record
        }
    }
    return working
}
