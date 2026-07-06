package app.skerry.ui

import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.vault.SecurityLog
import app.skerry.ui.ai.LocalAiDeps
import app.skerry.shared.vault.SshCertificateInspector
import app.skerry.shared.vault.SshKeyGenerator
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.tunnel.TunnelManager

/**
 * App dependency graph, assembled by the platform entry point (desktop `main`) and supplied to the
 * root composable (`DesktopDesignApp`/`MobileDesignApp`).
 *
 * A single holder instead of a pile of nullable parameters: a new subsystem is a field here, not
 * another root-composable parameter. `null` means the subsystem isn't implemented on this platform
 * yet: desktop assembles the full graph (sshj transport, file-backed host manager, file-backed
 * vault); mobile targets currently supply an empty graph and show a placeholder.
 */
data class AppDependencies(
    val transport: SshTransport? = null,
    val hosts: HostManagerController? = null,
    val vault: Vault? = null,
    /** Manager for keychain secrets (keys/passwords/certificates); `null` if not wired up. */
    val credentials: CredentialManagerController? = null,
    /** Known-hosts manager (trusted keys + key-change events); `null` if not wired up. */
    val knownHosts: KnownHostsController? = null,
    /** SSH key generator/inspector (Vault section); `null` on a platform without key crypto. */
    val keyGenerator: SshKeyGenerator? = null,
    /** SSH certificate inspector (Vault → Certificates); `null` on a platform without cert parsing. */
    val certificateInspector: SshCertificateInspector? = null,
    /** Manager for globally saved tunnels (Tunnels section); `null` if not wired up. */
    val tunnels: TunnelManager? = null,
    /** Manager for saved snippets (Snippets section); `null` if not wired up. */
    val snippets: SnippetManager? = null,
    /** Biometric vault unlock; `null` on a platform without biometrics. */
    val biometrics: VaultBiometrics? = null,
    /** Self-hosted sync coordinator; `null` if sync isn't wired up on this platform. */
    val sync: SyncCoordinator? = null,
    /** Teams (sharing hosts/secrets/snippets between accounts); `null` if not wired up. */
    val teams: app.skerry.ui.teams.TeamsCoordinator? = null,
    /** Local security event log (Settings → Security); `null` if not logging. */
    val securityLog: SecurityLog? = null,
    /** Local AI: model store + downloader + runtime; `null` for preview/mock without the subsystem. */
    val localAi: LocalAiDeps? = null,
)
