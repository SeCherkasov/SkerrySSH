package app.skerry.ui.sync

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.stail_local_vault
import app.skerry.ui.generated.resources.stail_encrypted_on_device
import app.skerry.ui.generated.resources.stail_syncing
import app.skerry.ui.generated.resources.stail_synced_host
import app.skerry.ui.generated.resources.stail_synced
import app.skerry.ui.generated.resources.stail_linked_locked
import app.skerry.ui.generated.resources.stail_sync_error
import org.jetbrains.compose.resources.stringResource

/**
 * Testable projection of sync state onto the profile/account card (desktop Settings → Sync,
 * mobile More). Not configured → local vault with a "set up sync" prompt; linked but locked →
 * "linked, locked" (offer reconnect); active session → accountId + server host.
 */
data class AccountCardModel(
    /** Up to two characters for the avatar. */
    val initials: String,
    val title: String,
    val subtitle: String,
    /** [SyncStatus.Online]: active session — show devices and "Disconnect". */
    val connected: Boolean,
    /** [SyncStatus.Configured]: linked but no session (vault locked) — offer "Reconnect". */
    val linked: Boolean,
) {
    /** Sync not configured (or preview/error) — card shows local vault and "Set up sync". */
    val localOnly: Boolean get() = !connected && !linked
}

/**
 * Reduces [SyncStatus] (plus the server URL from the saved link, for the subtitle) to [AccountCardModel].
 * [status] == null means no sync backend (preview/offscreen): treated as local vault.
 */
fun accountCardModel(status: SyncStatus?, serverUrl: String? = null): AccountCardModel = when (status) {
    null, SyncStatus.Disabled -> localVaultCard("Encrypted on this device")
    SyncStatus.Busy, is SyncStatus.NeedsPasswordReplaceConfirm -> localVaultCard("Syncing…")
    is SyncStatus.Online -> AccountCardModel(
        initials = accountInitials(status.accountId),
        title = status.accountId,
        subtitle = serverHost(serverUrl)?.let { "Synced · $it" } ?: "Synced",
        connected = true,
        linked = false,
    )
    is SyncStatus.Configured -> AccountCardModel(
        initials = accountInitials(status.accountId),
        title = status.accountId,
        subtitle = "Linked · locked",
        connected = false,
        linked = true,
    )
    // Sync error is shown by a separate Sync section; the card falls back to local vault.
    is SyncStatus.Failed -> localVaultCard("Sync error")
}

private fun localVaultCard(subtitle: String) =
    AccountCardModel(initials = "S", title = "Local vault", subtitle = subtitle, connected = false, linked = false)

/**
 * UI variant of [accountCardModel] with localized title/subtitle (strings resolved via
 * [stringResource]). accountId/host are data and stay verbatim; only static labels are localized.
 */
@Composable
fun accountCardModelLocalized(status: SyncStatus?, serverUrl: String? = null): AccountCardModel = when (status) {
    null, SyncStatus.Disabled -> localizedLocalVaultCard(stringResource(Res.string.stail_encrypted_on_device))
    SyncStatus.Busy, is SyncStatus.NeedsPasswordReplaceConfirm -> localizedLocalVaultCard(stringResource(Res.string.stail_syncing))
    is SyncStatus.Online -> AccountCardModel(
        initials = accountInitials(status.accountId),
        title = status.accountId,
        subtitle = serverHost(serverUrl)?.let { stringResource(Res.string.stail_synced_host, it) }
            ?: stringResource(Res.string.stail_synced),
        connected = true,
        linked = false,
    )
    is SyncStatus.Configured -> AccountCardModel(
        initials = accountInitials(status.accountId),
        title = status.accountId,
        subtitle = stringResource(Res.string.stail_linked_locked),
        connected = false,
        linked = true,
    )
    is SyncStatus.Failed -> localizedLocalVaultCard(stringResource(Res.string.stail_sync_error))
}

@Composable
private fun localizedLocalVaultCard(subtitle: String) = AccountCardModel(
    initials = "S",
    title = stringResource(Res.string.stail_local_vault),
    subtitle = subtitle,
    connected = false,
    linked = false,
)

/** Avatar initials: up to two leading letters/digits of the accountId local part, uppercased. */
fun accountInitials(accountId: String): String {
    val local = accountId.substringBefore('@')
    val letters = local.filter { it.isLetterOrDigit() }
    return if (letters.isEmpty()) "S" else letters.take(2).uppercase()
}

/** Host from the server URL for the subtitle (no scheme/port/path). null if it can't be parsed. */
fun serverHost(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val trimmed = url.trim()
    val authority = trimmed.substringAfter("://", trimmed).substringBefore('/').substringBefore('?').trim()
    // IPv6 literals are bracketed (http://[::1]:8080); a naive substringBefore(':') would keep only
    // "[". Take the bracket contents as the host, ignore the port after "]".
    if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        return if (close > 1) authority.substring(1, close) else null
    }
    val host = authority.substringBefore(':').trim()
    return host.ifEmpty { null }
}
