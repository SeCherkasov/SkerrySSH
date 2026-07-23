package app.skerry.ui.vault

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.skerry.shared.host.Host
import app.skerry.shared.snippet.Snippet
import app.skerry.shared.snippet.SnippetTemplate
import app.skerry.shared.snippet.SnippetVariableKind
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vtail_category_certificates
import app.skerry.ui.generated.resources.vtail_category_passwords
import app.skerry.ui.generated.resources.vtail_category_ssh_keys
import app.skerry.ui.generated.resources.vtail_snippets_frag_one
import app.skerry.ui.generated.resources.vtail_snippets_frag_other
import app.skerry.ui.generated.resources.vtail_used_by_one
import app.skerry.ui.generated.resources.vtail_used_by_other
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

    /** Categories shown in the Vault sidebar. */
    val sidebarCategories: List<VaultCategoryKind> = VaultCategoryKind.entries

    /** Keychain category of a secret: private key -> [SSH_KEYS], password -> [PASSWORDS], cert -> [CERTIFICATES]. */
    fun categoryOf(credential: Credential): VaultCategoryKind = when (credential.secret) {
        is CredentialSecret.PrivateKey -> VaultCategoryKind.SSH_KEYS
        is CredentialSecret.Password -> VaultCategoryKind.PASSWORDS
        is CredentialSecret.Certificate -> VaultCategoryKind.CERTIFICATES
    }

    /** Icon for a secret type. Theme-independent, so callers outside composition can use it. */
    fun secretIcon(secret: CredentialSecret): String = when (secret) {
        is CredentialSecret.Certificate -> "workspace_premium"
        is CredentialSecret.PrivateKey -> "key"
        is CredentialSecret.Password -> "password"
    }

    /** Style for a secret type: icon/accent color/tint (see [SecretTypeStyle]) in the active [colors]. */
    fun secretStyle(secret: CredentialSecret, colors: SkerryColors): SecretTypeStyle = when (secret) {
        is CredentialSecret.Certificate -> SecretTypeStyle(secretIcon(secret), colors.moss, tinted = true)
        is CredentialSecret.PrivateKey -> SecretTypeStyle(secretIcon(secret), colors.cyanBright, tinted = true)
        is CredentialSecret.Password -> SecretTypeStyle(secretIcon(secret), colors.dim, tinted = false)
    }

    /** Credentials belonging to the given category. */
    fun credentialsIn(kind: VaultCategoryKind, credentials: List<Credential>): List<Credential> =
        credentials.filter { categoryOf(it) == kind }

    /** Number of secrets in a category (for the sidebar count). */
    fun count(kind: VaultCategoryKind, credentials: List<Credential>): Int =
        credentialsIn(kind, credentials).size

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
     * Localized "used by N host(s) · M snippet(s)" label for a secret card (desktop + mobile).
     * `null` when nothing references the secret — the card then shows only the type word instead
     * of a noisy "used by 0 hosts".
     */
    @Composable
    fun usedByLabel(hostCount: Int, snippetCount: Int): String? {
        val hostsPart = when {
            hostCount == 1 -> stringResource(Res.string.vtail_used_by_one)
            hostCount > 1 -> stringResource(Res.string.vtail_used_by_other, hostCount)
            else -> null
        }
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
