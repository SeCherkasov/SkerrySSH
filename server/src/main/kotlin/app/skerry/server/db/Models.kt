package app.skerry.server.db

/**
 * An account as the server stores it (everything but [wrappedDataKey] is plaintext).
 *
 * equals/hashCode are overridden manually because of the [ByteArray] field: the data class
 * autogeneration would compare it by reference.
 */
data class AccountRow(
    val id: String,
    val srpSalt: String,
    val srpVerifier: String,
    val wrappedDataKey: ByteArray,
    val syncSeq: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AccountRow) return false
        return id == other.id && srpSalt == other.srpSalt && srpVerifier == other.srpVerifier &&
            syncSeq == other.syncSeq && wrappedDataKey.contentEquals(other.wrappedDataKey)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + srpSalt.hashCode()
        result = 31 * result + srpVerifier.hashCode()
        result = 31 * result + wrappedDataKey.contentHashCode()
        result = 31 * result + syncSeq.hashCode()
        return result
    }
}

data class DeviceRow(
    val id: String,
    val accountId: String,
    val name: String,
    val platform: String?,
    val createdAt: Long,
    val lastSeenAt: Long,
    val lastSyncVersion: Long?,
    val revoked: Boolean,
)

/**
 * An encrypted vault record on the server. Mirrors the client `VaultRecord` plus [serverSeq], a
 * server-assigned monotonic cursor for delta sync.
 */
data class StoredRecord(
    val id: String,
    val type: String,
    val version: Long,
    val updatedAt: String,
    val deviceId: String,
    val deleted: Boolean,
    val blob: ByteArray,
    val serverSeq: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredRecord) return false
        return id == other.id && type == other.type && version == other.version &&
            updatedAt == other.updatedAt && deviceId == other.deviceId &&
            deleted == other.deleted && serverSeq == other.serverSeq &&
            blob.contentEquals(other.blob)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + deleted.hashCode()
        result = 31 * result + blob.contentHashCode()
        result = 31 * result + serverSeq.hashCode()
        return result
    }
}

data class PairingRow(
    val code: String,
    val accountId: String,
    val encryptedDataKey: ByteArray,
    val expiresAt: Long,
    val consumed: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingRow) return false
        return code == other.code && accountId == other.accountId &&
            expiresAt == other.expiresAt && consumed == other.consumed &&
            encryptedDataKey.contentEquals(other.encryptedDataKey)
    }

    override fun hashCode(): Int {
        var result = code.hashCode()
        result = 31 * result + accountId.hashCode()
        result = 31 * result + encryptedDataKey.contentHashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + consumed.hashCode()
        return result
    }
}

/** An audit log row (sync metadata only, never record content). */
data class ActivityRow(
    val seq: Long,
    val accountId: String,
    val deviceId: String?,
    val event: String,
    val detail: String,
    val createdAt: Long,
)
