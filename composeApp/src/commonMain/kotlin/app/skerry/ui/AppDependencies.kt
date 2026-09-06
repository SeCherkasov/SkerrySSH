package app.skerry.ui

import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.vnc.VncTransport
import app.skerry.shared.vault.SecurityLog
import app.skerry.ui.ai.LocalAiDeps
import app.skerry.shared.vault.SecretFileReader
import app.skerry.shared.vault.SshCertificateInspector
import app.skerry.shared.vault.SshKeyGenerator
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.known.TrustedCaController
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
    /**
     * Read-only probe transport for form-side checks (test connection, container listing): its
     * verifier does NOT add a new host key to known_hosts, so probing never establishes trust
     * (only a real connect via TOFU does). `null` — preview/mock without probing.
     */
    val probeTransport: SshTransport? = null,
    /** VNC/RFB transport for remote-desktop tabs; `null` if VNC isn't wired up on this platform. */
    val vncTransport: VncTransport? = null,
    /** RDP transport for remote-desktop tabs; `null` if RDP isn't wired up on this platform. */
    val rdpTransport: app.skerry.shared.rdp.RdpTransport? = null,
    /**
     * The platform's audio output devices, offered by the RDP profile form when audio redirection is
     * turned on; `null` where there is no audio backend (preview), and the session then plays
     * through whatever the system uses.
     */
    val audioOutputs: app.skerry.shared.audio.AudioOutputs? = null,
    val hosts: HostManagerController? = null,
    val vault: Vault? = null,
    /** Manager for keychain secrets (keys/passwords/certificates); `null` if not wired up. */
    val credentials: CredentialManagerController? = null,
    /** Known-hosts manager (trusted keys + key-change events); `null` if not wired up. */
    val knownHosts: KnownHostsController? = null,
    val trustedCas: TrustedCaController? = null,
    /** SSH key generator/inspector (Vault section); `null` on a platform without key crypto. */
    val keyGenerator: SshKeyGenerator? = null,
    /** SSH certificate inspector (Vault → Certificates); `null` on a platform without cert parsing. */
    val certificateInspector: SshCertificateInspector? = null,
    /**
     * Reader for files a credential references rather than stores
     * ([app.skerry.shared.vault.CredentialSecret.KeyFile]); `null` on a platform that can't read them,
     * where the Vault shows such a secret without a file status.
     */
    val secretFiles: SecretFileReader? = null,
    /** Manager for globally saved tunnels (Tunnels section); `null` if not wired up. */
    val tunnels: TunnelManager? = null,
    /** Manager for saved snippets (Snippets section); `null` if not wired up. */
    val snippets: SnippetManager? = null,
    /** Library of saved runbooks (Runbooks section); `null` if not wired up. */
    val runbooks: app.skerry.ui.runbook.RunbookManager? = null,
    /** The one in-flight runbook run, app-wide; `null` if not wired up. */
    val runbookRunner: app.skerry.ui.runbook.RunbookRunner? = null,
    /** Log of past runs; `null` if not wired up. */
    val runbookHistory: app.skerry.shared.runbook.VaultRunbookRunStore? = null,
    /** Biometric vault unlock; `null` on a platform without biometrics. */
    val biometrics: VaultBiometrics? = null,
    /** Self-hosted sync coordinator; `null` if sync isn't wired up on this platform. */
    val sync: SyncCoordinator? = null,
    /** Teams (sharing hosts/secrets/snippets between accounts); `null` if not wired up. */
    val teams: app.skerry.ui.teams.TeamsCoordinator? = null,
    /** Sharing the live session with a team; null without sync (the share toggle stays dimmed). */
    val sessionShare: app.skerry.ui.share.SessionShareController? = null,
    /** Directory of the teams' live shared sessions; null without sync. */
    val sharedSessions: app.skerry.ui.share.SharedSessionsController? = null,
    /** Local security event log (Settings → Security); `null` if not logging. */
    val securityLog: SecurityLog? = null,
    /** Local AI: model store + downloader + runtime; `null` for preview/mock without the subsystem. */
    val localAi: LocalAiDeps? = null,
    /**
     * The phone's background power management (wake lock, battery exemption, the ROM's autostart
     * page); `null` on every platform that has none, and the More screen then drops the row.
     */
    val keepAlivePower: app.skerry.ui.keepalive.KeepAlivePower? = null,
)
