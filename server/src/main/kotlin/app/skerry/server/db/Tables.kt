package app.skerry.server.db

import org.jetbrains.exposed.sql.Table

/**
 * Sync server storage schema. The server is zero-knowledge: everything related to user content
 * ([Records.blob], [Pairing.encryptedDataKey], [Accounts.wrappedDataKey]) is ciphertext the server
 * has no key for. Only sync metadata is stored in the clear (`docs/skerry-sync-design.md` §2).
 *
 * Types are chosen to be portable between SQLite and PostgreSQL: text identifiers, `long` for
 * counters, `blob` for ciphertext (BLOB in SQLite, bytea in PostgreSQL).
 */
object Accounts : Table("accounts") {
    /** accountId (also the client-side Argon2id salt and SRP identity). */
    val id = varchar("id", 320)
    /** SRP salt `s` (hex), separate from the Argon2id salt. */
    val srpSalt = text("srp_salt")
    /** SRP verifier `v` (hex); the server checks login against it without knowing the password. */
    val srpVerifier = text("srp_verifier")
    /** dataKey wrapped under masterKey; the server stores only ciphertext. */
    val wrappedDataKey = blob("wrapped_data_key")
    /** Monotonic per-account sync cursor (delta watermark). */
    val syncSeq = long("sync_seq").default(0)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

object Devices : Table("devices") {
    val id = varchar("id", 64)
    val accountId = varchar("account_id", 320).references(Accounts.id)
    val name = text("name")
    /** Device platform (e.g. "Android 34", "Linux"), a plaintext label like name. */
    val platform = varchar("platform", 64).nullable()
    val createdAt = long("created_at")
    val lastSeenAt = long("last_seen_at")
    /** Sync cursor the device has read/written up to (plaintext counter). */
    val lastSyncVersion = long("last_sync_version").nullable()
    val revoked = bool("revoked").default(false)

    // PK on (accountId, id): deviceId is unique per account, not globally — otherwise a client
    // supplying another account's deviceId could hijack or make un-revocable someone else's
    // device record.
    override val primaryKey = PrimaryKey(accountId, id)
}

/**
 * Encrypted vault records. LWW by ([version], then `deviceId`); [serverSeq] is a separate axis:
 * a monotonic per-account cursor clients use for delta selection.
 */
object Records : Table("records") {
    val accountId = varchar("account_id", 320).references(Accounts.id)
    val recordId = varchar("record_id", 64)
    val type = varchar("type", 32)
    val version = long("version")
    val updatedAt = text("updated_at")
    val deviceId = varchar("device_id", 64)
    val deleted = bool("deleted")
    val blob = blob("blob")
    /** Assigned by the server on each accepted record; monotonically increasing per account. */
    val serverSeq = long("server_seq")

    override val primaryKey = PrimaryKey(accountId, recordId)

    init {
        index("idx_records_delta", false, accountId, serverSeq)
    }
}

/**
 * Metadata audit log for the admin console (`docs/skerry-sync-prototype.html` -> Recent activity).
 * Append-only, zero-knowledge: stores only the event, device, and a human-readable summary
 * ([detail] — counters/cursors, never record content). No FK to [Accounts]: the log survives
 * account deletion and allows events before account creation. Retention is [ActivityRepository].
 */
object ActivityLog : Table("activity_log") {
    val seq = long("seq").autoIncrement()
    val accountId = varchar("account_id", 320)
    val deviceId = varchar("device_id", 64).nullable()
    val event = varchar("event", 32)
    val detail = text("detail")
    /** Team the event belongs to (for team-scoped history); null for account-level events. */
    val teamId = varchar("team_id", 64).nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(seq)

    init {
        index("idx_activity_team", false, teamId, seq)
    }
}

/**
 * Public X25519 account keys for Teams invitations. The public key isn't secret; server
 * substitution is detected by members comparing fingerprints (see `docs/skerry-sync-design.md`,
 * Teams section).
 */
object AccountKeys : Table("account_keys") {
    val accountId = varchar("account_id", 320).references(Accounts.id)
    val publicKey = blob("public_key")
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(accountId)
}

/**
 * Teams (record sharing between accounts). Zero-knowledge: the server knows only membership and
 * roles; the team name and record content are encrypted with teamKey, which the server never has.
 * [teamSeq] is a monotonic per-team delta cursor (analogous to [Accounts.syncSeq]).
 */
object Teams : Table("teams") {
    val id = varchar("id", 64)
    val ownerAccountId = varchar("owner_account_id", 320).references(Accounts.id)
    val teamSeq = long("team_seq").default(0)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Team members. [envelope] is a sealed box (crypto_box_seal) containing teamKey and the team name,
 * sealed to the invitee's public key: the server delivers it but can't read it.
 * Statuses: `invited` -> `active`; removing a member deletes the row (ACL revocation).
 */
object TeamMembers : Table("team_members") {
    val teamId = varchar("team_id", 64).references(Teams.id)
    val accountId = varchar("account_id", 320).references(Accounts.id)
    /** `owner` | `member`. */
    val role = varchar("role", 16)
    /** `invited` | `active`. */
    val status = varchar("status", 16)
    val envelope = blob("envelope").nullable()
    val invitedBy = varchar("invited_by", 320)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(teamId, accountId)
}

/**
 * Encrypted team records — same model as [Records] but team-scoped: LWW by (version, deviceId),
 * [teamSeq] as the delta cursor. Tombstones aren't watermark-compacted (membership churns, making
 * the watermark unstable); periodic age-based cleanup purges them instead.
 */
object TeamRecords : Table("team_records") {
    val teamId = varchar("team_id", 64).references(Teams.id)
    val recordId = varchar("record_id", 64)
    val type = varchar("type", 32)
    val version = long("version")
    val updatedAt = text("updated_at")
    val deviceId = varchar("device_id", 64)
    val deleted = bool("deleted")
    val blob = blob("blob")
    val teamSeq = long("team_seq")

    override val primaryKey = PrimaryKey(teamId, recordId)

    init {
        index("idx_team_records_delta", false, teamId, teamSeq)
    }
}

/** One-time pairing sessions (variant B): dataKey encrypted with a transferKey, with a TTL. */
object Pairing : Table("pairing") {
    val code = varchar("code", 64)
    val accountId = varchar("account_id", 320).references(Accounts.id)
    /** dataKey encrypted with a one-time transferKey; the server sees only ciphertext. */
    val encryptedDataKey = blob("encrypted_data_key")
    val expiresAt = long("expires_at")
    val consumed = bool("consumed").default(false)

    override val primaryKey = PrimaryKey(code)
}
