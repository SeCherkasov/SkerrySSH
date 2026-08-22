package app.skerry.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.CredentialUsage
import app.skerry.shared.vault.VaultCrypto
import app.skerry.shared.vault.securityMoment
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_time_days_ago
import app.skerry.ui.generated.resources.settings_time_today
import app.skerry.ui.generated.resources.settings_time_yesterday
import app.skerry.ui.generated.resources.vault_copies_window
import app.skerry.ui.generated.resources.vault_kdf_value
import app.skerry.ui.generated.resources.vault_kv_added
import app.skerry.ui.generated.resources.vault_kv_changed
import app.skerry.ui.generated.resources.vault_kv_cipher
import app.skerry.ui.generated.resources.vault_kv_copied
import app.skerry.ui.generated.resources.vault_kv_exported
import app.skerry.ui.generated.resources.vault_kv_fingerprint
import app.skerry.ui.generated.resources.vault_kv_kdf
import app.skerry.ui.generated.resources.vault_kv_last_used
import app.skerry.ui.generated.resources.vault_kv_passphrase
import app.skerry.ui.generated.resources.vault_kv_stored
import app.skerry.ui.generated.resources.vault_kv_type
import app.skerry.ui.generated.resources.vault_passphrase_none
import app.skerry.ui.generated.resources.vault_passphrase_set
import app.skerry.ui.generated.resources.vault_section_audit
import app.skerry.ui.generated.resources.vault_section_encryption
import app.skerry.ui.generated.resources.vault_stored_ciphertext
import app.skerry.ui.generated.resources.vault_stored_local
import app.skerry.ui.generated.resources.vault_never_exported
import app.skerry.ui.generated.resources.vault_value_never
import app.skerry.ui.generated.resources.vault_value_unknown
import app.skerry.ui.design.KeyValueRow
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Window the audit line counts copies over — long enough to show a habit, short enough to mean today. */
private const val COPY_WINDOW_DAYS = 30

/** Cipher every vault record is sealed with. An algorithm name, not UI copy — identical in every locale. */
private const val VAULT_CIPHER = "XChaCha20-Poly1305"

/**
 * Relative label for a stored ISO timestamp: "today 09:14" / "yesterday 18:02" / "12 days ago", the
 * same wording Settings → Security uses for its event log. A `null` timestamp (never recorded) or one
 * the platform clock can't parse reads as an em dash — the panel states what it knows and nothing more.
 */
@Composable
fun momentLabel(iso: String?): String {
    val moment = iso?.let { securityMoment(it) } ?: return stringResource(Res.string.vault_value_unknown)
    return when (moment.daysAgo) {
        0 -> stringResource(Res.string.settings_time_today, moment.timeOfDay)
        1 -> stringResource(Res.string.settings_time_yesterday, moment.timeOfDay)
        else -> stringResource(Res.string.settings_time_days_ago, moment.daysAgo)
    }
}

/**
 * The facts about the selected secret, as key/value rows: what it is, its fingerprint, whether a
 * passphrase guards it, and the dates this device recorded ([usage] — added / last authenticated).
 *
 * Everything here is read off real state. A secret older than the usage log (or never used) shows
 * "—"/"never" rather than a plausible date, and [fingerprint] is left out entirely for secret types
 * that have none (a password).
 */
@Composable
fun SecretFactRows(
    typeLabel: String,
    fingerprint: String?,
    secret: CredentialSecret,
    usage: CredentialUsage?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        KeyValueRow(stringResource(Res.string.vault_kv_type), typeLabel)
        if (fingerprint != null) KeyValueRow(stringResource(Res.string.vault_kv_fingerprint), fingerprint)
        passphraseGuarded(secret)?.let { guarded ->
            KeyValueRow(
                stringResource(Res.string.vault_kv_passphrase),
                stringResource(if (guarded) Res.string.vault_passphrase_set else Res.string.vault_passphrase_none),
            )
        }
        KeyValueRow(stringResource(Res.string.vault_kv_added), momentLabel(usage?.addedAt))
        // Only for a secret whose material was actually replaced — an untouched one has nothing to say.
        usage?.changedAt?.let { KeyValueRow(stringResource(Res.string.vault_kv_changed), momentLabel(it)) }
        KeyValueRow(
            stringResource(Res.string.vault_kv_last_used),
            usage?.lastUsedAt?.let { momentLabel(it) } ?: stringResource(Res.string.vault_value_never),
        )
    }
}

/**
 * How the secret is protected and where its ciphertext goes. Static facts of this build's crypto
 * (see [VaultCrypto]) plus one live one: whether the ciphertext reaches a server at all.
 *
 * That last answer takes the secret and not just [syncing] on purpose. An account existing is
 * account-level; whether THIS secret is pushed is not — a file-backed one never is (issue #174,
 * [app.skerry.shared.sync.DeviceLocalRecords]). Asking for both leaves no way to pass the
 * account-level fact where the per-secret one belongs, which is how the row came to claim a server
 * copy of a record that never left.
 */
@Composable
fun SecretEncryptionRows(syncing: Boolean, secret: CredentialSecret, modifier: Modifier = Modifier) {
    val onServer = syncing && secret !is CredentialSecret.KeyFile
    Column(modifier.fillMaxWidth()) {
        KeyValueRow(stringResource(Res.string.vault_kv_cipher), VAULT_CIPHER)
        KeyValueRow(
            stringResource(Res.string.vault_kv_kdf),
            stringResource(Res.string.vault_kdf_value, VaultCrypto.KDF_MEMORY_MIB),
        )
        KeyValueRow(
            stringResource(Res.string.vault_kv_stored),
            stringResource(if (onServer) Res.string.vault_stored_ciphertext else Res.string.vault_stored_local),
        )
    }
}

/**
 * What this device did with the secret — the ways its material left the vault. A password leaves
 * via the clipboard, so its row counts copies inside the audit window; a key or a certificate
 * leaves via "Export key", so its row states the last export ("never" until there was one — the
 * panel of an exported key must not look like the panel of one that never left, issue #221).
 * Per-device by design (see [app.skerry.shared.vault.CredentialUsageLog]) — a copy or an export
 * made on the phone is not counted here, and the log is not synced.
 */
@Composable
fun SecretAuditRows(credential: Credential, usage: CredentialUsage?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        if (credential.secret is CredentialSecret.Password) {
            val copies = VaultPresentation.copiesWithin(usage, COPY_WINDOW_DAYS) { at -> securityMoment(at)?.daysAgo }
            KeyValueRow(
                stringResource(Res.string.vault_kv_copied),
                pluralStringResource(Res.plurals.vault_copies_window, copies, copies, COPY_WINDOW_DAYS),
            )
        }
        // Type check, not privateKeyExport(...) != null — same recomposition-allocation reasoning
        // as hasAuditTrail.
        if (credential.secret is CredentialSecret.PrivateKey || credential.secret is CredentialSecret.Certificate) {
            KeyValueRow(
                stringResource(Res.string.vault_kv_exported),
                usage?.exportedAt?.lastOrNull()?.let { momentLabel(it) }
                    ?: stringResource(Res.string.vault_never_exported),
            )
        }
    }
}

/** Section caption used by the panels above; kept next to them so both platforms space it alike. */
@Composable
fun SecretSectionLabel(text: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 16.dp, bottom = 6.dp)) { DetailLabel(text) }
}

/** Localized caption for the encryption section. */
@Composable
fun encryptionSectionTitle(): String = stringResource(Res.string.vault_section_encryption)

/** Localized caption for the audit section. */
@Composable
fun auditSectionTitle(): String = stringResource(Res.string.vault_section_audit)

/**
 * Whether a passphrase guards the private material, or `null` for a secret type that has none (a
 * stored password): the row is omitted rather than answering a question that doesn't apply.
 */
private fun passphraseGuarded(secret: CredentialSecret): Boolean? = when (secret) {
    is CredentialSecret.Password -> null
    is CredentialSecret.PrivateKey -> !secret.passphrase.isNullOrEmpty()
    is CredentialSecret.Certificate -> !secret.passphrase.isNullOrEmpty()
    is CredentialSecret.KeyFile -> !secret.passphrase.isNullOrEmpty()
}
