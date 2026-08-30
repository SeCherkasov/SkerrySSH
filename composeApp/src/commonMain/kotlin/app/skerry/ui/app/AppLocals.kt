package app.skerry.ui.app

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import app.skerry.shared.audio.AudioOutputs
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.terminal.TerminalHistoryStore
import app.skerry.shared.vault.SecretFileReader
import app.skerry.shared.vault.SshCertificateInspector
import app.skerry.shared.vault.SshKeyGenerator
import app.skerry.shared.vault.SecurityLog
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultCrypto
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.ui.ai.AiAssistantController
import app.skerry.ui.terminal.CastOpenResult
import app.skerry.ui.terminal.openCastFile
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.known.TrustedCaController
import app.skerry.ui.session.SessionsController
import app.skerry.ui.runbook.RunbookManager
import app.skerry.shared.runbook.VaultRunbookRunStore
import app.skerry.ui.runbook.RunbookRunner
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.tunnel.TunnelManager
import app.skerry.ui.update.UpdateNoticeController

/**
 * Feature flags for the design layer. Supplied via [DesktopDesignApp] and available to any
 * composable through [LocalFeatures]. Unfinished features are hidden behind a flag rather than
 * removed from the layout.
 *
 * [ai] gates the AI assistant surfaces: the bottom AI bar, terminal suggestion cards, AI policy
 * choice in New connection, and the "AI" settings tab. Off by default.
 */
@Immutable
data class FeatureFlags(
    val ai: Boolean = false,
)

/** Current feature flags; default has all unfinished features off (mock path/preview). */
val LocalFeatures: ProvidableCompositionLocal<FeatureFlags> = staticCompositionLocalOf { FeatureFlags() }

/**
 * Dual-pane SFTP settings that persist across restarts. [showHidden] toggles dotfiles, like mc;
 * [showModified]/[showPermissions] toggle the listing's modified-date and permissions columns
 * (the header's Columns popup). Each setter updates the value and persists it. Supplied by
 * [DesktopDesignApp] from platform storage; defaults don't persist (mock/preview).
 */
@Immutable
data class SftpPrefs(
    val showHidden: Boolean = true,
    val setShowHidden: (Boolean) -> Unit = {},
    val showModified: Boolean = true,
    val setShowModified: (Boolean) -> Unit = {},
    val showPermissions: Boolean = false,
    val setShowPermissions: (Boolean) -> Unit = {},
)

/** Current SFTP settings; default shows hidden files and does not persist changes (mock path/preview). */
val LocalSftpPrefs: ProvidableCompositionLocal<SftpPrefs> = staticCompositionLocalOf { SftpPrefs() }

/**
 * Live backend supplied to the design layer via CompositionLocal (same approach as [LocalFonts]) so
 * controllers don't need to be threaded through every composable's parameters. `null` means the mock
 * path (offscreen render/preview): the composable renders static data from [DesktopMockData].
 */
val LocalHosts: ProvidableCompositionLocal<HostManagerController?> = staticCompositionLocalOf { null }

/**
 * Manager for keychain secrets (keys/passwords/certificates in the unlocked vault), referenced by
 * hosts via `credentialId`. `null` — mock path/preview without a vault: the keychain sections in
 * [app.skerry.ui.vault.VaultView] render a static layout. Supplied by [DesktopDesignApp] behind the
 * master-password gate.
 */
val LocalCredentials: ProvidableCompositionLocal<CredentialManagerController?> = staticCompositionLocalOf { null }

/**
 * SSH key generator/inspector (key-pair creation in the Vault section, fingerprint/type computation
 * for stored keys). `null` — mock path/preview without platform crypto: [app.skerry.ui.vault.VaultView]
 * renders a static layout with the generate button disabled. Supplied by [DesktopDesignApp] behind the
 * vault gate.
 */
val LocalSshKeyGenerator: ProvidableCompositionLocal<SshKeyGenerator?> = staticCompositionLocalOf { null }

/**
 * SSH certificate inspector (parses metadata of an imported `*-cert.pub`: principals, validity,
 * serial, CA). `null` — mock path/preview without a platform implementation: the Certificates section
 * in [app.skerry.ui.vault.VaultView] shows a placeholder. Supplied by [DesktopDesignApp] behind the
 * vault gate.
 */
val LocalSshCertificateInspector: ProvidableCompositionLocal<SshCertificateInspector?> = staticCompositionLocalOf { null }

/**
 * Reader for key/certificate files a credential only references
 * ([app.skerry.shared.vault.CredentialSecret.KeyFile]). The Vault section uses it to show whether a
 * referenced file is actually readable here and to parse the certificate behind it; connections read
 * the same files through their own resolver. `null` — mock path/preview: file-backed secrets render
 * without a status. Supplied behind the vault gate.
 */
val LocalSecretFileReader: ProvidableCompositionLocal<SecretFileReader?> = staticCompositionLocalOf { null }

/**
 * The platform's audio output devices, for the RDP profile form to offer a place to play the remote
 * session's sound. `null` — no audio backend on this platform (or preview): the form offers the
 * toggle without a device picker, and the session plays through the system default.
 */
val LocalAudioOutputs: ProvidableCompositionLocal<AudioOutputs?> = staticCompositionLocalOf { null }

/**
 * Manager for open sessions (tabs + live connections). `null` — mock path without a connection
 * backend: the titlebar and terminal render static layout data.
 */
val LocalSessions: ProvidableCompositionLocal<SessionsController?> = staticCompositionLocalOf { null }

/**
 * Known-hosts manager (trusted host keys + unresolved key-change events). `null` — mock path without
 * a backend: [app.skerry.ui.known.KnownHostsView] renders the static table and key-change panel from
 * the layout. Supplied by [DesktopDesignApp] behind the vault gate.
 */
val LocalKnownHosts: ProvidableCompositionLocal<KnownHostsController?> = staticCompositionLocalOf { null }

/**
 * Trusted certificate authorities (`@cert-authority`), shown on the known-hosts screen. `null` —
 * mock path without a backend, where the section is simply absent. Supplied by the chrome root.
 */
val LocalTrustedCas: ProvidableCompositionLocal<TrustedCaController?> = staticCompositionLocalOf { null }

/**
 * "Connect to host" action: resolves the secret (a keychain secret from the vault, or a password
 * prompt) and opens a session. Supplied by the chrome root ([DesktopDesignApp]); default is a no-op
 * (mock path/preview).
 */
val LocalConnectHost: ProvidableCompositionLocal<(Host) -> Unit> = staticCompositionLocalOf { {} }

/**
 * "Bring the terminal forward" action — what [LocalConnectHost] does after connecting, for the paths
 * that open a session from another screen (joining a colleague's shared session from Teams). Supplied
 * by the chrome root on each platform, since the navigation state is theirs; default is a no-op.
 */
val LocalShowTerminal: ProvidableCompositionLocal<() -> Unit> = staticCompositionLocalOf { {} }

/**
 * Controller behind the keyboard-interactive prompt: a server asking for a second factor mid-connect
 * (2FA code, SMS token, push confirmation). Supplied by the chrome root so any screen the prompt
 * lands over keeps its own state; `null` — mock path/preview, nothing is connecting.
 */
val LocalKeyboardInteractive:
    ProvidableCompositionLocal<app.skerry.ui.connection.KeyboardInteractivePromptController?> =
    staticCompositionLocalOf { null }

/**
 * Controller behind the host-trust dialog: a server whose key or certificate nobody has vouched for
 * yet, or whose key has changed, asked about while the handshake waits. Supplied by the chrome root
 * for the same reason as [LocalKeyboardInteractive]; `null` — mock path/preview.
 */
val LocalHostTrust: ProvidableCompositionLocal<app.skerry.ui.trust.HostTrustPromptController?> =
    staticCompositionLocalOf { null }


/** How a host row click connects: single click or double click. Desktop-only setting. */
enum class HostClickConnectMode(val id: String) {
    SingleClick("single"),
    DoubleClick("double"),
    ;

    companion object {
        // Single click keeps the historic behavior; double click is opt-in via Settings.
        val DEFAULT = SingleClick

        fun fromId(id: String): HostClickConnectMode = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Whether a host row connects on single or double click (Settings → Terminal → Behavior).
 * Desktop-only; mobile always connects on tap. Supplied by [DesktopDesignApp]; default is
 * [HostClickConnectMode.DEFAULT].
 */
val LocalHostClickConnectMode: ProvidableCompositionLocal<HostClickConnectMode> =
    staticCompositionLocalOf { HostClickConnectMode.DEFAULT }

/**
 * "Open host in this pane" action: same secret resolution as [LocalConnectHost], but the session
 * lands in the named pane of the active tab instead of a new tab. Takes the pane's id, since a tab
 * can hold several and the picker belongs to one of them. Supplied by [DesktopDesignApp]; default is
 * a no-op (mock path/preview).
 */
val LocalConnectPane: ProvidableCompositionLocal<(Host, String) -> Unit> = staticCompositionLocalOf { { _, _ -> } }

/**
 * SSH transport for one-off "Test connection" checks from the form (connect without opening a
 * session). Deliberately separate from the live-session transport: behind it is a read-only verifier
 * ([app.skerry.shared.ssh.ReadOnlyHostKeyVerifier]) that does not add the new host's key to known_hosts,
 * since a probe must not establish permanent trust (only a real connect via TOFU does). Do not use
 * this slot to open real sessions. `null` — mock path/preview without a live transport: the Test
 * button is disabled. Supplied by [DesktopDesignApp]; sole consumer is
 * [app.skerry.ui.host.NewConnectionModal].
 */
val LocalTestTransport: ProvidableCompositionLocal<SshTransport?> = staticCompositionLocalOf { null }

/**
 * "Open host's SFTP" action: same connection path as [LocalConnectHost] (secret resolution, resuming
 * a live session), but ends on the Files tab (remote browser) instead of the terminal, so the SFTP
 * button on host detail shows files without a separate Connect step. Default is a no-op (preview).
 */
val LocalOpenSftp: ProvidableCompositionLocal<(Host) -> Unit> = staticCompositionLocalOf { {} }

/**
 * Manager for globally saved tunnels: the list of forwards plus enable/disable, each opening its own
 * connection to the host. `null` — mock path/preview without a backend: [app.skerry.ui.tunnel.TunnelsView]
 * renders a static layout. Supplied by [DesktopDesignApp] behind the vault gate (resolving a host's
 * secret requires an unlocked vault).
 */
val LocalTunnels: ProvidableCompositionLocal<TunnelManager?> = staticCompositionLocalOf { null }

/**
 * Manager for saved snippets: the command library plus running one in the active terminal. `null` —
 * mock path/preview without a backend: [app.skerry.ui.snippet.SnippetsView] renders a static layout.
 * Supplied by [DesktopDesignApp] (snippets are plain config, not vault-gated).
 */
val LocalSnippets: ProvidableCompositionLocal<SnippetManager?> = staticCompositionLocalOf { null }

/**
 * Library of saved runbooks (ordered checklists of commands). `null` — mock path/preview without a
 * backend, where [app.skerry.ui.runbook.RunbooksView] renders nothing. Supplied by the app shells
 * behind the vault gate: runbook steps can carry inline credentials, like snippets.
 */
val LocalRunbooks: ProvidableCompositionLocal<RunbookManager?> = staticCompositionLocalOf { null }

/**
 * The one in-flight runbook run, app-wide: it outlives the panel showing it, so switching tabs
 * doesn't abandon a half-finished procedure. `null` on the mock path.
 */
val LocalRunbookRunner: ProvidableCompositionLocal<RunbookRunner?> = staticCompositionLocalOf { null }

/**
 * Reports user input to the vault's idle auto-lock timer from the places the gate's own
 * [app.skerry.ui.vault.idleActivity] modifier cannot see it: a soft keyboard delivers no key event
 * to the composition, so on Android text is the only trace the user is still there. Provided by
 * [app.skerry.ui.vault.VaultGate]; a no-op wherever there is no gate (mock path, previews).
 */
val LocalUserActivity: ProvidableCompositionLocal<() -> Unit> = staticCompositionLocalOf { {} }

/**
 * Log of past runs, per runbook — what the run screen's "previous runs" card and the library's
 * "last run" line read. `null` on the mock path and wherever no vault is behind the section.
 */
val LocalRunbookHistory: ProvidableCompositionLocal<VaultRunbookRunStore?> = staticCompositionLocalOf { null }

/**
 * Per-host terminal command history over the encrypted vault: the sessions graph writes it for
 * autocomplete, the command palette reads every host's at once. `null` — mock path/preview without a
 * vault, where the palette has nothing to show.
 */
val LocalTerminalHistory: ProvidableCompositionLocal<TerminalHistoryStore?> = staticCompositionLocalOf { null }

/**
 * Snippet "Run on host" action: open/reuse a session to [Host] and send the given command line
 * right after connecting, rather than only in the active terminal. The line arrives fully resolved
 * from [app.skerry.ui.snippet.SnippetManager.run] — dynamic variables already confirmed, trailing
 * newline included — so it is sent verbatim. Resolves the secret the same way as
 * [LocalConnectHost] (keychain or password prompt). Supplied by [DesktopDesignApp]; default is a no-op
 * (mock path/preview).
 */
val LocalRunSnippetOnHost: ProvidableCompositionLocal<(Host, String, List<String>) -> Unit> =
    staticCompositionLocalOf { { _, _, _ -> } }

/**
 * Unlocked [Vault] behind the master-password gate, needed by the settings screen (More) to toggle
 * biometric unlock (wrapping `dataKey` under `bioKey`). `null` — mock path/preview without a vault.
 */
val LocalVault: ProvidableCompositionLocal<Vault?> = staticCompositionLocalOf { null }

/**
 * Vault crypto primitives — needed by the data backup flow (Settings → Security) to derive the
 * backup-file key from the master password. Supplied behind the vault gate next to [LocalVault];
 * `null` in mock/preview.
 */
val LocalVaultCrypto: ProvidableCompositionLocal<VaultCrypto?> = staticCompositionLocalOf { null }

/**
 * Vault biometrics orchestrator. `null` — biometrics not configured on this platform (desktop
 * without hardware/offscreen): the settings screen hides the toggle. Supplied behind the vault gate
 * by the same providers.
 */
val LocalVaultBiometrics: ProvidableCompositionLocal<VaultBiometrics?> = staticCompositionLocalOf { null }

/**
 * Local security event log (Settings → Security): recent events plus a derived "last password
 * change". `null` — mock/preview: the section renders an empty log and a neutral caption. Not synced
 * (per-device audit trail). Supplied behind the vault gate.
 */
val LocalSecurityLog: ProvidableCompositionLocal<SecurityLog?> = staticCompositionLocalOf { null }

/**
 * Self-hosted sync coordinator: register/login/syncNow/disconnect plus a status flow. `null` — mock
 * path/preview or a platform without sync: the Sync section in settings renders a static layout.
 * Supplied by [DesktopDesignApp]/[MobileDesignApp] behind the vault gate (the server wrapper needs
 * dataKey).
 */
val LocalSync: ProvidableCompositionLocal<SyncCoordinator?> = staticCompositionLocalOf { null }

/**
 * Teams coordinator (sharing hosts/secrets/snippets between accounts on top of the sync server).
 * `null` — mock path/preview or sync not connected: the Teams screen renders an empty state prompting
 * to set up sync. Supplied together with [LocalSync].
 */
val LocalTeams: ProvidableCompositionLocal<app.skerry.ui.teams.TeamsCoordinator?> = staticCompositionLocalOf { null }

/**
 * Sharing the live session with a team (session sharing over the sync server's relay). `null` —
 * mock path/preview or sync not connected: the toolbar's share toggle is dimmed. One controller per
 * app: only one session is shared at a time.
 */
val LocalSessionShare: ProvidableCompositionLocal<app.skerry.ui.share.SessionShareController?> =
    staticCompositionLocalOf { null }

/**
 * Directory of sessions the account's teams are sharing right now, and the way into one. `null` on
 * the mock path/preview or without sync: the team screens simply show no live sessions.
 */
val LocalSharedSessions: ProvidableCompositionLocal<app.skerry.ui.share.SharedSessionsController?> =
    staticCompositionLocalOf { null }

/**
 * AI assistant controller (external OpenAI-compatible provider, BYOK). `null` — mock path/preview or
 * a platform without AI: the "AI" settings tab renders a static layout. When set, the tab is live
 * (key/model input, quick chat) independent of [FeatureFlags.ai], which still gates the unfinished
 * AI surfaces in the terminal. Supplied behind the vault gate (the key is stored encrypted in the
 * vault).
 */
val LocalAi: ProvidableCompositionLocal<AiAssistantController?> = staticCompositionLocalOf { null }

/**
 * Update-notice controller (GitHub Releases check + the "check for updates" toggle). `null` — mock
 * path/preview: the About section hides the toggle and no notice is shown. Supplied behind the
 * vault gate (the toggle is a synced SETTINGS record in the vault).
 */
val LocalUpdates: ProvidableCompositionLocal<UpdateNoticeController?> = staticCompositionLocalOf { null }

/**
 * How the `.cast` player asks the user for a file. The default is the real picker; a test replaces
 * it, because the driver behind it ([app.skerry.ui.terminal.CastOpenDriver]) is composed by the
 * window chrome and a native file dialog cannot be answered from a test — without this seam the
 * whole playback path is reachable only by hand.
 */
val LocalCastPicker: ProvidableCompositionLocal<suspend () -> CastOpenResult> =
    staticCompositionLocalOf { ::openCastFile }

/**
 * Mobile session keep-alive bridge for battery optimization exemption and autostart settings.
 */
val LocalKeepAliveBridge: ProvidableCompositionLocal<app.skerry.ui.keepalive.SessionKeepAliveBridge?> =
    staticCompositionLocalOf { null }

