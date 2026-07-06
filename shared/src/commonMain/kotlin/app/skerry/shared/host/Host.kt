package app.skerry.shared.host

import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.ssh.ConnectionType
import kotlinx.serialization.Serializable

/**
 * Saved connection profile in the host manager. Identity is the stable [id] (assigned at creation,
 * unchanged by edits), so renaming [label] or changing the address doesn't lose history/references.
 * [label] is the display name, [address] is the host or IP to dial, [group] is an optional folder
 * for list grouping.
 *
 * The secret itself is not stored here: it lives in the encrypted vault as
 * [app.skerry.shared.vault.Credential] (keychain), and the profile references it by [credentialId]
 * (a reusable secret — one key/password for multiple hosts). `null` means no secret is attached and
 * the password is entered at connect time.
 *
 * [tags] are optional labels for filtering the host list (#prod/#docker chips). Stored in canonical
 * form (no `#`, lowercase, deduplicated, ≤ [MAX_TAG_LENGTH]) via [normalizeTag]; [group] (folder) and
 * [tags] (labels) are independent.
 *
 * [identityId] is a legacy pointer from the old two-tier model (host → account → secret). New code
 * never writes it; it exists only so [app.skerry.shared.vault.VaultMigration] can read old saved
 * host files (`identityId` key) and collapse them into [credentialId], after which the field is
 * cleared. TODO: remove once no old files remain.
 *
 * [aiPolicy] is the per-host AI policy ("AI under policy" principle). Default [AiPolicy.Strict] is
 * safe: for both existing hosts (field absent) and new ones, cloud is denied until the user
 * deliberately relaxes the policy. Serialized by name (backward compatible).
 *
 * [connectionType] is the profile's transport (see [ConnectionType]). Default [ConnectionType.SSH]
 * preserves backward compatibility: old files without the field read as SSH. For
 * [ConnectionType.TELNET] only [address]/[port] matter (no auth/secret). For [ConnectionType.SERIAL]
 * [address] holds the device name (e.g. `/dev/ttyUSB0`, `COM3`) and [port] holds the baud rate;
 * [username]/[credentialId] are unused.
 */
@Serializable
data class Host(
    val id: String,
    val label: String,
    val address: String,
    val port: Int = 22,
    val username: String,
    val group: String? = null,
    val credentialId: String? = null,
    val identityId: String? = null,
    val tags: List<String> = emptyList(),
    val aiPolicy: AiPolicy = AiPolicy.Strict,
    val connectionType: ConnectionType = ConnectionType.SSH,
)
