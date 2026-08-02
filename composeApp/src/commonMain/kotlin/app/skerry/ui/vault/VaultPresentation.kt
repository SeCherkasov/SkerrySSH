package app.skerry.ui.vault

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.skerry.shared.host.Host
import app.skerry.shared.snippet.Snippet
import app.skerry.shared.snippet.SnippetTemplate
import app.skerry.shared.snippet.SnippetVariableKind
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.CredentialUsage
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vtail_category_certificates
import app.skerry.ui.generated.resources.vtail_category_passwords
import app.skerry.ui.generated.resources.vtail_category_ssh_keys
import app.skerry.ui.generated.resources.vtail_snippets_frag_one
import app.skerry.ui.generated.resources.vtail_snippets_frag_other
import app.skerry.ui.generated.resources.vtail_used_by_snippets_one
import app.skerry.ui.generated.resources.vtail_used_by_snippets_other
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.SkerryColors

/**
 * Vault manager categories ([icon] is a Material Symbols sidebar icon; [title] is the localized
 * label). The three keychain categories ([SSH_KEYS]/[PASSWORDS]/[CERTIFICATES]) hold [Credential]
 * entries by secret type.
 */
enum class VaultCategoryKind(val icon: String) {
    SSH_KEYS("key"),
    PASSWORDS("password"),
    CERTIFICATES("vpn_lock"),
}

/** Localized label for a Vault category (sidebar/header). */
@Composable
fun VaultCategoryKind.title(): String = when (this) {
    VaultCategoryKind.SSH_KEYS -> stringResource(Res.string.vtail_category_ssh_keys)
    VaultCategoryKind.PASSWORDS -> stringResource(Res.string.vtail_category_passwords)
    VaultCategoryKind.CERTIFICATES -> stringResource(Res.string.vtail_category_certificates)
}

/**
 * Icon, accent color, and tint for a keychain secret type. Single source of truth for every place
 * a secret type is rendered (desktop [VaultView], mobile cards/detail sheet, auth pickers).
 */
data class SecretTypeStyle(val icon: String, val color: Color, val tinted: Boolean)

/**
 * Pure presentation logic for the Vault section over keychain secrets ([Credential]) and the host
 * catalog: sorts secrets into categories and computes dependencies (which hosts reference a
 * secret). No Compose/IO; [VaultView] only renders the result.
 */
object VaultPresentation {

    /** How many host names a row spells out before switching to "+N". */
    private const val MAX_NAMED_HOSTS = 3

    /** Categories shown in the Vault sidebar. */
    val sidebarCategories: List<VaultCategoryKind> = VaultCategoryKind.entries

    /** Keychain category of a secret: private key -> [SSH_KEYS], password -> [PASSWORDS], cert -> [CERTIFICATES]. */
    fun categoryOf(credential: Credential): VaultCategoryKind = when (val secret = credential.secret) {
        is CredentialSecret.PrivateKey -> VaultCategoryKind.SSH_KEYS
        is CredentialSecret.Password -> VaultCategoryKind.PASSWORDS
        is CredentialSecret.Certificate -> VaultCategoryKind.CERTIFICATES
        // A file-backed secret is filed by what it authenticates with: an explicit certificate ref
        // makes it a certificate, otherwise a key. A sibling `*-cert.pub` can't be taken into
        // account here — that needs disk access, and categorising must stay pure.
        is CredentialSecret.KeyFile ->
            if (secret.certificateRef.isNullOrBlank()) VaultCategoryKind.SSH_KEYS else VaultCategoryKind.CERTIFICATES
    }

    /** Icon for a secret type. Theme-independent, so callers outside composition can use it. */
    fun secretIcon(secret: CredentialSecret): String = when (secret) {
        is CredentialSecret.Certificate -> "workspace_premium"
        is CredentialSecret.PrivateKey -> "key"
        is CredentialSecret.Password -> "password"
        is CredentialSecret.KeyFile -> if (secret.certificateRef.isNullOrBlank()) "key" else "workspace_premium"
    }

    /** Style for a secret type: icon/accent color/tint (see [SecretTypeStyle]) in the active [colors]. */
    fun secretStyle(secret: CredentialSecret, colors: SkerryColors): SecretTypeStyle = when (secret) {
        is CredentialSecret.Certificate -> SecretTypeStyle(secretIcon(secret), colors.moss, tinted = true)
        is CredentialSecret.PrivateKey -> SecretTypeStyle(secretIcon(secret), colors.cyanBright, tinted = true)
        is CredentialSecret.Password -> SecretTypeStyle(secretIcon(secret), colors.dim, tinted = false)
        is CredentialSecret.KeyFile -> SecretTypeStyle(
            secretIcon(secret),
            if (secret.certificateRef.isNullOrBlank()) colors.cyanBright else colors.moss,
            tinted = true,
        )
    }

    /** Credentials belonging to the given category. */
    fun credentialsIn(kind: VaultCategoryKind, credentials: List<Credential>): List<Credential> =
        credentials.filter { categoryOf(it) == kind }

    /** Number of secrets in a category (for the sidebar count). */
    fun count(kind: VaultCategoryKind, credentials: List<Credential>): Int =
        credentialsIn(kind, credentials).size

    /**
     * How many clipboard copies of a secret fall inside the last [days] days. [daysAgo] resolves a
     * stored ISO timestamp to whole days back (the platform clock, [app.skerry.shared.vault.securityMoment]);
     * a timestamp it can't parse is not counted — an unreadable date is not evidence of a copy.
     */
    fun copiesWithin(usage: CredentialUsage?, days: Int, daysAgo: (String) -> Int?): Int =
        usage?.copiedAt?.count { at -> daysAgo(at)?.let { it <= days } == true } ?: 0

    /** Hosts bound to keychain secret [credentialId] (via [Host.credentialId]); used for "used by" and unbinding on delete. */
    fun hostsUsing(credentialId: String, hosts: List<Host>): List<Host> =
        hosts.filter { it.credentialId == credentialId }

    /**
     * Snippets whose command references this secret by name (`${{vault:label}}`, matched exactly —
     * the same lookup the run dialog performs). Snippet references are by label, not id: this is
     * what makes a rename break them, so "used by" must surface them next to hosts.
     */
    fun snippetsUsing(label: String, snippets: List<Snippet>): List<Snippet> =
        snippets.filter { snippet ->
            SnippetTemplate.variables(snippet.command)
                .any { it.kind == SnippetVariableKind.VAULT && it.format == label }
        }

    /**
     * Host names a secret is bound to, as one line: up to [max] of them, then "+N" for the rest.
     * `null` for none. Names, not a count — a row saying "prod-web-01, prod-web-02" answers the
     * question the count only hints at, and the row elides what doesn't fit anyway.
     */
    fun hostNames(labels: List<String>, max: Int = MAX_NAMED_HOSTS): String? = when {
        labels.isEmpty() -> null
        labels.size <= max -> labels.joinToString(", ")
        else -> labels.take(max).joinToString(", ") + " +" + (labels.size - max)
    }

    /**
     * Localized "prod-web-01, prod-web-02 · M snippet(s)" label for a secret row (desktop + mobile).
     * `null` when nothing references the secret — the row then carries only its own facts instead of
     * a noisy "used by 0 hosts".
     */
    @Composable
    fun usedByLabel(hostLabels: List<String>, snippetCount: Int): String? {
        val hostsPart = hostNames(hostLabels)
        return when {
            hostsPart != null && snippetCount > 0 -> {
                val fragment =
                    if (snippetCount == 1) stringResource(Res.string.vtail_snippets_frag_one)
                    else stringResource(Res.string.vtail_snippets_frag_other, snippetCount)
                "$hostsPart · $fragment"
            }
            hostsPart != null -> hostsPart
            snippetCount == 1 -> stringResource(Res.string.vtail_used_by_snippets_one)
            snippetCount > 1 -> stringResource(Res.string.vtail_used_by_snippets_other, snippetCount)
            else -> null
        }
    }
}
