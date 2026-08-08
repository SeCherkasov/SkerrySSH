package app.skerry.ui

import app.skerry.ui.app.DesktopSettingsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.skerry_icon
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.window.rememberWindowState
import app.skerry.ui.desktop.DisplayScaleReadiness
import app.skerry.ui.desktop.SkerryWindowFrame
import app.skerry.ui.desktop.optimalWindowSize
import app.skerry.ui.desktop.rememberSkerryWindowChrome
import java.awt.GraphicsEnvironment
import app.skerry.shared.host.VaultHostStore
import app.skerry.shared.io.PrivateConfig
import app.skerry.shared.ssh.FileHostKeyMismatchStore
import app.skerry.shared.ssh.VaultKnownHostsStore
import app.skerry.shared.ssh.ReadOnlyHostKeyVerifier
import app.skerry.shared.ssh.UnknownHost
import app.skerry.shared.ssh.RoutingTransport
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.ssh.SshjTransport
import app.skerry.shared.ssh.KeyFileResolver
import app.skerry.shared.vault.OkioSecretFileReader
import app.skerry.ui.connection.KeyboardInteractivePromptController
import app.skerry.shared.ssh.HostCertificateVerifier
import app.skerry.shared.ssh.SshjCaKeyParser
import app.skerry.shared.ssh.TofuHostKeyVerifier
import app.skerry.shared.ssh.VaultTrustedCaStore
import app.skerry.shared.vault.BouncyCastleSshKeyGenerator
import app.skerry.shared.vault.FileCredentialUsageLog
import app.skerry.shared.vault.FileSecurityLog
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.CredentialStore
import app.skerry.shared.vault.TrashStore
import app.skerry.shared.vault.WorkspaceLayoutStore
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.SshjCertificateInspector
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.shared.snippet.VaultSnippetStore
import app.skerry.shared.sync.FileSyncStateStore
import app.skerry.shared.sync.KtorSyncClient
import app.skerry.shared.tunnel.VaultTunnelStore
import app.skerry.ui.sync.FileReconcileDebtStore
import app.skerry.ui.sync.FileSyncConfigStore
import app.skerry.shared.ai.AiSettingsStore
import app.skerry.shared.ai.local.IsolatedLlmRuntime
import app.skerry.shared.ai.local.LlmHostCommandLine
import app.skerry.shared.ai.local.LlmHostMain
import app.skerry.shared.ai.local.ProcessLlmHostLauncher
import app.skerry.shared.ai.local.LocalModelStore
import app.skerry.shared.ai.local.ModelDownloader
import app.skerry.ui.ai.LocalAiDeps
import app.skerry.ui.ai.aiProviderFactory
import app.skerry.ui.ai.AiAssistantController
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.app.CustomGroup
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.host.HostSection
import app.skerry.ui.i18n.AppLocaleProvider
import app.skerry.ui.i18n.UiLanguage
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.known.TrustedCaController
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LETTER_SPACING
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LINE_HEIGHT
import app.skerry.ui.terminal.DEFAULT_TERMINAL_SCROLLBACK
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_RANGE
import app.skerry.ui.terminal.TERMINAL_SCROLLBACK_OPTIONS
import app.skerry.ui.terminal.clampTerminalLetterSpacing
import app.skerry.ui.terminal.clampTerminalLineHeight
import app.skerry.ui.terminal.TerminalCursorStyle
import app.skerry.ui.terminal.TerminalFont
import app.skerry.ui.terminal.TerminalThemes
import app.skerry.ui.tunnel.TunnelManager
import app.skerry.ui.tunnel.resolveTunnelHost
import app.skerry.ui.vault.AutoLockDuration
import app.skerry.ui.app.HostClickConnectMode
import app.skerry.ui.vault.ResetScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Skerry config directory. Defaults to `~/.config/skerry`; honors XDG_CONFIG_HOME. Created with
 * mode 0700 (and upgraded if an old install left 0755), so UI prefs and config files inside are
 * not accessible to other local users regardless of their permissions. Internal: shared by the
 * app root and per-feature caches that live in the config dir (see `AiModelCache`).
 */
internal fun configDir(): Path {
    val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
    val base = xdg?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".config")
    return base.resolve("skerry").also { PrivateConfig.ensureDir(it) }
}

/**
 * Skerry data directory (not config): large artifacts such as downloaded local-AI GGUF models.
 * Defaults to `~/.local/share/skerry`; honors XDG_DATA_HOME (the app data dir inside a Flatpak
 * sandbox). Models are public weights, so 0600 hardening is not required.
 */
private fun dataDir(): Path {
    val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
    val base = xdg?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".local", "share")
    return base.resolve("skerry").also { Files.createDirectories(it) }
}

/**
 * Stable device identifier for vault records (provenance + sync LWW). Generated once and
 * persisted to `device_id` so it survives restarts.
 */
private fun deviceId(dir: Path): String {
    val file = dir.resolve("device_id")
    if (Files.exists(file)) return Files.readString(file).trim()
    Files.createDirectories(dir)
    val id = UUID.randomUUID().toString()
    Files.writeString(file, id)
    return id
}

// Reads UI prefs with range/option validation, falling back to the default on an invalid value
// (I/O and unreadable-file defaults live in FilePrefs).

/** Terminal font size, px: falls back to default outside [TERMINAL_FONT_SIZE_RANGE]. */
private fun readTerminalFontSize(prefs: FilePrefs): Int =
    prefs.int("terminal_font_size", DEFAULT_TERMINAL_FONT_SIZE)
        .takeIf { it in TERMINAL_FONT_SIZE_RANGE } ?: DEFAULT_TERMINAL_FONT_SIZE

/** Scrollback depth for a new session: falls back to default (10,000) outside [TERMINAL_SCROLLBACK_OPTIONS]. */
private fun readTerminalScrollback(prefs: FilePrefs): Int =
    prefs.int("terminal_scrollback", DEFAULT_TERMINAL_SCROLLBACK)
        .takeIf { it in TERMINAL_SCROLLBACK_OPTIONS } ?: DEFAULT_TERMINAL_SCROLLBACK

/**
 * Live dependency graph for the desktop app, built before `application {}` by a plain function
 * with no Compose state reads: vault/transports/managers/sync/AI and vault lifecycle callbacks.
 */
private class DesktopGraph(
    val deps: AppDependencies,
    val keyboardInteractive: KeyboardInteractivePromptController,
    val securityLog: FileSecurityLog,
    val probeTransport: SshTransport,
    val vncTransport: app.skerry.shared.vnc.VncTransport,
    val rdpTransport: app.skerry.shared.rdp.RdpTransport,
    val workspaceLayout: WorkspaceLayoutStore,
    val ai: AiAssistantController,
    val updates: app.skerry.ui.update.UpdateNoticeController,
    val onVaultUnlocked: () -> Unit,
    val onVaultReset: (ResetScope) -> Unit,
)

private fun buildDesktopGraph(dir: Path, prefs: FilePrefs): DesktopGraph {
    // Local encrypted vault is created FIRST: the whole workspace (hosts/groups/snippets/tunnels/
    // known-hosts) lives in its records and E2E-syncs. The master-password gate (App -> VaultGate)
    // blocks the whole UI, so by the time anything connects to or reads it the vault is unlocked.
    val vault = FileVault(
        dir.resolve("vault.json").toString().toPath(),
        IonspinVaultCrypto(),
        deviceId(dir),
        FileSystem.SYSTEM,
        now = { Instant.now().toString() },
        // The main secrets file itself is 0600, not just the directory (unlike security_events.json
        // below): protection should not be single-layered in case of a permission-preserving copy/backup.
        harden = { PrivateConfig.harden(Path.of(it.toString())) },
    )
    // Local (non-synced) security event log: master password change, biometrics, unlock. Written
    // by the controller behind the gate; read by Settings -> Security.
    val securityLog = FileSecurityLog(
        dir.resolve("security_events.json").toString().toPath(),
        FileSystem.SYSTEM,
        harden = { PrivateConfig.harden(Path.of(it.toString())) },
    ) { Instant.now().toString() }
    // TOFU: a host's first key is remembered in the vault (RecordType.KNOWN_HOST, synced across
    // devices); on key change, the connection is refused and an event is recorded to the local
    // (non-synced) known_hosts_mismatches store so the manager can warn and offer accept/reject.
    // The clock stamps firstSeen/observedAt.
    val knownHostsStore = VaultKnownHostsStore(vault)
    // Certificate authorities trusted to vouch for host keys (@cert-authority). Wrapped around the
    // TOFU verifier below: a certificate from a trusted CA is accepted outright, anything else
    // falls through to trust-on-first-use exactly as before.
    val trustedCaStore = VaultTrustedCaStore(vault)
    val mismatchStore = FileHostKeyMismatchStore(dir.resolve("known_hosts_mismatches"))
    // Live session transport: routes by connection type (SSH/Telnet/Serial). SSH carries the TOFU
    // verifier/known-hosts; Telnet/Serial are stateless (created internally with defaults).
    // Keyboard-interactive challenges (2FA codes) travel from the transport to a dialog through this
    // controller; it is shared by every transport that authenticates, so a code asked for while
    // dialing a tunnel or probing a container prompts the same way a session does.
    val keyboardInteractive = KeyboardInteractivePromptController()
    // File-backed credentials (key/certificate kept outside the vault) are read at connect time, so
    // every transport that authenticates gets the same resolver. `~` expands against the user's home
    // the way an OpenSSH config would; the inspector lets an expired certificate be refused here,
    // with its date, instead of turning into "server rejected the credentials".
    val certificateInspector = SshjCertificateInspector()
    val secretFiles = OkioSecretFileReader(FileSystem.SYSTEM, homeDir = System.getProperty("user.home"))
    val keyFileResolver = KeyFileResolver(files = secretFiles, inspector = certificateInspector)
    val transport = RoutingTransport(
        ssh = SshjTransport(
            HostCertificateVerifier(
                trustedCaStore,
                TofuHostKeyVerifier(knownHostsStore, mismatchStore) { Instant.now().toString() },
            ) { Instant.now().epochSecond },
            keyFiles = keyFileResolver,
            keyboardInteractiveResponder = keyboardInteractive.responder,
        ),
    )
    // "Test connection" and the container listing in the form: read-only verifier, so neither can
    // establish trust — only a real connection (TOFU above) does. A host with no entry is accepted,
    // because the form names a host that is usually not saved yet and the user is reading the answer.
    val probeTransport = SshjTransport(
        HostCertificateVerifier(
            trustedCaStore,
            ReadOnlyHostKeyVerifier(knownHostsStore, UnknownHost.Accept),
        ) { Instant.now().epochSecond },
        keyFiles = keyFileResolver,
        keyboardInteractiveResponder = keyboardInteractive.responder,
    )
    val knownHosts = KnownHostsController(knownHostsStore, mismatchStore) { Instant.now().toString() }
    val trustedCas = TrustedCaController(
        trustedCaStore,
        SshjCaKeyParser(),
        newId = { UUID.randomUUID().toString() },
        now = { Instant.now().toString() },
    )
    // Host manager: profiles are HOST records in the vault, tree order lives in the layout record
    // ([VaultHostStore]/[WorkspaceLayout]). The vault starts locked (empty list); the controller
    // reloads via reload() after unlock. id is a random UUID.
    // Trash for the personal vault: deletions made through these stores keep a restorable snapshot
    // (Settings -> Trash). Passed explicitly — the stores default to deleting outright so a team
    // vault never grows a trash of its own.
    val trash = TrashStore(vault)
    val hostStore = VaultHostStore(vault, trash = trash)
    val hosts = HostManagerController(hostStore) { UUID.randomUUID().toString() }
    // Workspace layout in the vault: empty folders (and tree order) sync as a single record. Read
    // after unlock (vault starts locked) and written on change.
    val workspaceLayout = WorkspaceLayoutStore(vault)
    // Flat vault model: keychain secrets are CREDENTIAL records; a host references a secret
    // directly via credentialId.
    // Local (non-synced) usage trail behind the Vault panel's dates: when a secret was added, when it
    // last authenticated, how often it was copied. Ids and timestamps only — hardened like the
    // security log, since it is audit metadata about this device.
    val credentialUsage = FileCredentialUsageLog(
        dir.resolve("credential_usage.json").toString().toPath(),
        FileSystem.SYSTEM,
        harden = { PrivateConfig.harden(Path.of(it.toString())) },
    ) { Instant.now().toString() }
    val credentials = CredentialManagerController(CredentialStore(vault, trash), credentialUsage) { UUID.randomUUID().toString() }
    // Self-hosted sync: coordinator ties together the network client (Ktor+SRP), crypto, and the
    // local vault. The server binding persists to sync.json (0600): non-secret data
    // (URL/accountId/deviceId) plus an optional refresh token sealed under dataKey (keep-connected).
    // deviceId is the stable one reused elsewhere. The sync cursor is in-memory for now (re-pull on
    // start; LWW is idempotent). Reloading list managers after sync/unlock is deferred via a var:
    // tunnels/snippets are created below, and sync references reload through this var (called only
    // after full initialization).
    var reloadManagers: () -> Unit = {}
    // The teams coordinator is created below (it needs the sync session), but onSynced must call
    // it: the team key arrives as a TEAM record via the regular account sync. Wired late via a var.
    var teamsForSync: app.skerry.ui.teams.TeamsCoordinator? = null
    val sync = SyncCoordinator(
        clientFactory = { url -> KtorSyncClient(url) },
        crypto = IonspinVaultCrypto(),
        vault = vault,
        configStore = FileSyncConfigStore(dir.resolve("sync.json")),
        // Reactivation rebuilds this device still owes — beside the link, not on it: a disconnect or a
        // connect to another server must not take a debt the reconcile never paid (issue #170).
        debtStore = FileReconcileDebtStore(dir.resolve("sync-reconcile")),
        // Persistent delta-sync cursor: survives restarts, otherwise every start would be a full re-pull since 0.
        syncState = FileSyncStateStore(dir.resolve("sync-cursor.json")),
        deviceIdProvider = { deviceId(dir) },
        deviceName = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Skerry desktop",
        // Sync pulled records directly into the vault; refresh managers or the data stays invisible until re-entry.
        onSynced = {
            reloadManagers()
            teamsForSync?.onAccountSynced()
        },
    )
    // Teams (zero-knowledge record sharing between accounts): coordinator layered on the same sync
    // session. Per-team vaults live in config/teams/ (dataKey = teamKey from the account vault's
    // TEAM record); team-sync cursors are in their own file. Team WS signals arrive via onTeamSignal.
    val teams = app.skerry.ui.teams.TeamsCoordinator(
        session = { sync.currentSession() },
        client = { sync.currentTeamClient() },
        vault = vault,
        crypto = IonspinVaultCrypto(),
        teamVaults = app.skerry.shared.team.TeamVaults(
            dir = dir.resolve("teams").toString().toPath(),
            crypto = IonspinVaultCrypto(),
            deviceId = deviceId(dir),
            fileSystem = FileSystem.SYSTEM,
            harden = { PrivateConfig.harden(Path.of(it.toString())) },
            now = { Instant.now().toString() },
        ),
        teamState = FileSyncStateStore(dir.resolve("team-cursor.json")),
        newId = { UUID.randomUUID().toString() },
        onTeamsChanged = { reloadManagers() },
    )
    teamsForSync = teams
    // A missing team key (skipped by an older client's delta sync) is only fixed by a full re-pull.
    teams.onKeyMissing = { sync.recoverFullPull() }
    sync.onTeamSignal = teams::onSignal
    // SSH key generation in the Vault section: BouncyCastle over the sshj format (the same one the transport reads).
    val keyGenerator = BouncyCastleSshKeyGenerator()
    // Parses imported SSH certificates (Vault -> Certificates section) via sshj over the ssh-wire format.
    // Global tunnels: saved forwards in tunnels.json. Its own transport, not [probeTransport]: a
    // tunnel opens with no terminal and no prompt, so an unknown host is refused rather than accepted
    // — this connection must not be the one that settles a host's identity. CA-aware for the same
    // reason the real transport is: a host claimed by a trusted CA has to present a valid certificate
    // here too, or the CA is trusted everywhere except where nobody is looking. Host/secret resolution
    // goes through the graph (hosts + credentials in the unlocked vault). Scope lives for the app's
    // lifetime.
    val tunnelTransport = SshjTransport(
        HostCertificateVerifier(
            trustedCaStore,
            ReadOnlyHostKeyVerifier(knownHostsStore, UnknownHost.Refuse),
        ) { Instant.now().epochSecond },
        keyFiles = keyFileResolver,
        keyboardInteractiveResponder = keyboardInteractive.responder,
    )
    val tunnelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tunnels = TunnelManager(
        store = VaultTunnelStore(vault, trash),
        transport = tunnelTransport,
        // useForConnect, not find: a tunnel authenticates with the secret every time it comes up,
        // which is exactly what "last used" in the Vault panel reports.
        resolve = { hostId -> resolveTunnelHost(hostId, findHost = hosts::find, findCredential = credentials::useForConnect) },
        scope = tunnelScope,
        scanTransport = probeTransport,
    ) { UUID.randomUUID().toString() }
    // Saved snippets: the command library is SNIPPET records in the vault (commands may contain
    // inline credentials, so they share encryption and E2E sync). Run targets the active terminal.
    val snippets = SnippetManager(VaultSnippetStore(vault, trash)) { UUID.randomUUID().toString() }
    // Runbooks: RUNBOOK records in the same vault (steps carry the same kind of inline secrets as
    // snippets). The runner is app-scoped so a procedure survives switching tabs; its coroutine
    // scope is the app's, and the vault gate ends any run on lock (see tearDownForLock).
    val runbooks = app.skerry.ui.runbook.RunbookManager(
        app.skerry.shared.runbook.VaultRunbookStore(vault, trash),
    ) { UUID.randomUUID().toString() }
    // History of past runs: RUNBOOK_RUN records next to the runbooks themselves, capped per
    // runbook. It holds outcomes and timings only — never a command line or its output.
    val runbookHistory = app.skerry.shared.runbook.VaultRunbookRunStore(vault)
    val runbookRunner = app.skerry.ui.runbook.RunbookRunner(
        scope = tunnelScope,
        newId = { UUID.randomUUID().toString() },
        onFinished = runbookHistory::record,
    )
    // AI assistant: settings (provider/BYOK/local model) are an encrypted SETTINGS record in the
    // vault; a request routes to the cloud or to the local runtime (Llamatik/llama.cpp). Local AI:
    // GGUF models under ~/.local/share/skerry/models (XDG), downloaded with resume and sha256
    // verification. The vault starts locked (settings default); the controller reloads settings after unlock.
    val aiSettingsStore = AiSettingsStore(vault)
    val localModelStore = LocalModelStore(FileSystem.SYSTEM, dataDir().resolve("models").toString().toPath())
    val localAi = LocalAiDeps(
        store = localModelStore,
        downloader = ModelDownloader(FileSystem.SYSTEM, localModelStore),
        // Inference runs in a child JVM: llama.cpp aborts the process on some inputs and corrupts
        // memory when loaded next to Skia/AWT (issue #37). Out of process, a native crash costs one
        // answer instead of every open SSH session.
        runtime = IsolatedLlmRuntime(ProcessLlmHostLauncher(contextLength = 4096)),
    )
    val ai = AiAssistantController(
        initialSettings = aiSettingsStore.load(),
        persist = aiSettingsStore::save,
        providerFactory = aiProviderFactory(localAi),
        scope = tunnelScope,
        reload = aiSettingsStore::load,
        localInstalled = localAi::installed,
        models = localAi.modelsController(tunnelScope),
    )
    // Update notice: the "check for updates" toggle is a synced SETTINGS record in the vault; the
    // daily GitHub Releases check starts only after unlock (updates.refresh() in reloadManagers).
    val updates = app.skerry.ui.update.updateNoticeController(vault, tunnelScope)
    // Secret migration into the vault (IDENTITY -> CREDENTIAL, host -> direct credentialId) on
    // unlock is idempotent. Afterward, managers are reloaded and the sync session is silently
    // restored. There is no more migration of the old local workspace (hosts/snippets/tunnels.json):
    // the workspace lives in vault records.
    // All managers now exist, so reload is wired up (used both from onSynced and on unlock).
    reloadManagers = {
        hosts.reload()
        snippets.reload()
        runbooks.reload()
        tunnels.reload()
        knownHosts.refresh()
        // Keychain secrets are CREDENTIAL records too: a key/password pulled by live sync must show
        // up without a restart. Safe on a locked vault (all() degrades to an empty list).
        credentials.reload()
        // AI BYOK settings (key/model) are also a SETTINGS vault record, so they're reread here too:
        // an edit that arrives via live sync from another device (onSynced calls reloadManagers)
        // reflects in the UI immediately instead of only after a re-login.
        ai.refresh()
        // Same story for the update-check toggle (also a synced SETTINGS record); refresh() only
        // reconciles the loop, it does not re-run the check on every synced change.
        updates.refresh()
    }
    val onVaultUnlocked: () -> Unit = {
        // Vault opened, so reload managers (including AI BYOK settings) from decrypted records.
        reloadManagers()
        // Tunnels flagged for autostart come up now, not in reloadManagers: that one also runs on
        // every synced change, and raising there would fight the user's own toggles.
        tunnels.startAutostart()
        // Apply the trash retention window here, not only when its screen is opened: otherwise a
        // deleted secret a user never goes looking for would sit in the vault (and keep being
        // pushed to the server) long past the 30 days the UI promises.
        trash.purgeExpired()
        // Resume the live sync paused by the lock, or — on a cold start with keep-connected — silently
        // restore the session (the open vault means a dataKey to unseal the refresh token with).
        sync.resumeAfterUnlock()
    }
    // Vault reset (forgotten password / corrupted file). Hosts/snippets/tunnels are vault records,
    // so Vault.reset() already erased them along with the secrets (zero-knowledge: they can't be
    // recovered without the master password — "keep profiles, wipe only secrets" is technically
    // impossible). This only cleans data OUTSIDE the vault and reflects the emptied vault in the
    // managers. The vault is locked at this point.
    val onVaultReset: (ResetScope) -> Unit = { resetScope ->
        tunnels.closeAll()
        // Team keys lived in the erased vault, so local team vaults can no longer be opened; lock them.
        teams.lock()
        // The security log refers to the erased vault (password change/biometrics/pairing); on any
        // reset it becomes stale and could leak device names, so it's always cleared.
        securityLog.clear()
        // Same for the usage trail: its ids point at secrets that no longer exist, and a new vault
        // must not inherit dates from the erased one.
        credentialUsage.clear()
        // The reset erased the dataKey, so the sealed sync refresh token is wrapped under a dead key.
        // Disconnects from the server, otherwise settings would show "Linked" with no way to log in.
        // (No biometrics on desktop: deps.biometrics=null.) Clean start: create a new vault and
        // reconnect sync.
        sync.disconnect()
        // Hosts/groups are erased along with the vault on any reset, so their local UI traces
        // (recents, collapse state, empty folders) are cleared too: otherwise group names and host
        // UUIDs that no longer exist would remain visible.
        prefs.setLines("recent_connections", emptyList())
        prefs.setLines("collapsed_groups", emptyList())
        prefs.setLines("custom_groups", emptyList())
        // Factory reset: also wipes trusted keys (non-vault) and terminal settings.
        if (resetScope == ResetScope.Everything) {
            knownHosts.mismatches.toList().forEach { knownHosts.reject(it) }
            knownHosts.entries.toList().forEach { knownHosts.forget(it) }
            prefs.set("terminal_font", TerminalFont.DEFAULT.id)
            prefs.set("terminal_font_size", DEFAULT_TERMINAL_FONT_SIZE)
            prefs.set("terminal_line_height", DEFAULT_TERMINAL_LINE_HEIGHT.toString())
            prefs.set("terminal_letter_spacing", DEFAULT_TERMINAL_LETTER_SPACING.toString())
            prefs.set("ui_language", UiLanguage.DEFAULT.id)
            prefs.set("terminal_scrollback", DEFAULT_TERMINAL_SCROLLBACK)
            prefs.set("terminal_cursor_style", TerminalCursorStyle.DEFAULT.id)
            prefs.set("terminal_show_title", false)
            prefs.set("terminal_clipboard_write", false)
            prefs.set("terminal_prod_warnings", false)
            prefs.set("terminal_highlight_input", true)
            prefs.set("terminal_highlight_output", false)
            prefs.set("auto_lock", AutoLockDuration.DEFAULT.id)
        }
        hosts.reload()
        snippets.reload()
        runbooks.reload()
        tunnels.reload()
        // The vault is locked after reset, so this clears the in-memory secret list (all() is empty
        // on a locked vault); the list rereads when a new vault is created and unlocked.
        credentials.reload()
    }
    // Session sharing (relay on the sync server): the host side and the directory of what the
    // account's teams are sharing right now. Both ride the same sync session and team keys.
    val sessionShare = app.skerry.ui.share.SessionShareController(
        client = { sync.currentShareClient() },
        session = { sync.currentSession() },
        teamKey = { teamId -> teams.teamKey(teamId) },
        crypto = IonspinVaultCrypto(),
        newShareId = { UUID.randomUUID().toString().lowercase() },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    val sharedSessions = app.skerry.ui.share.SharedSessionsController(
        client = { sync.currentShareClient() },
        session = { sync.currentSession() },
        teams = { teams.teams.value.filter { it.hasKey }.map { t -> t.id to t.name } },
        teamKey = { teamId -> teams.teamKey(teamId) },
        crypto = IonspinVaultCrypto(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    // A share started or ended somewhere in the team: re-read the directory rather than wait for
    // the user to reopen the screen.
    teams.onSharesChanged = { sharedSessions.refresh() }
    val deps = AppDependencies(transport = transport, hosts = hosts, vault = vault, credentials = credentials, knownHosts = knownHosts, trustedCas = trustedCas, keyGenerator = keyGenerator, certificateInspector = certificateInspector, secretFiles = secretFiles, tunnels = tunnels, snippets = snippets, runbooks = runbooks, runbookRunner = runbookRunner, runbookHistory = runbookHistory, sync = sync, teams = teams, sessionShare = sessionShare, sharedSessions = sharedSessions, localAi = localAi, audioOutputs = app.skerry.shared.audio.JavaSoundOutputs())
    return DesktopGraph(
        deps = deps,
        keyboardInteractive = keyboardInteractive,
        securityLog = securityLog,
        probeTransport = probeTransport,
        vncTransport = app.skerry.shared.vnc.VncTcpTransport(),
        rdpTransport = app.skerry.shared.rdp.RdpTcpTransport(
            // Trust on first use, hardened to 0600 like known_hosts: an RDP host signs its own
            // certificate unless an enterprise CA issued one, so the platform trust store would
            // refuse nearly every server.
            app.skerry.shared.rdp.FileRdpCertificateStore(
                dir.resolve("rdp_known_certs").toString().toPath(),
                FileSystem.SYSTEM,
                harden = { PrivateConfig.harden(Path.of(it.toString())) },
            ),
            // Playback for a profile that asks for the session's sound (MS-RDPEA).
            audioPlayers = app.skerry.shared.audio.JavaSoundPlayers(),
            // H.264 in the graphics pipeline, on a machine that has an ffmpeg to decode it with.
            h264Decoders = app.skerry.shared.rdp.egfx.FfmpegH264Decoders(),
        ),
        workspaceLayout = workspaceLayout,
        ai = ai,
        updates = updates,
        onVaultUnlocked = onVaultUnlocked,
        onVaultReset = onVaultReset,
    )
}

fun main(args: Array<String>) {
    // Started as the isolated inference host (issue #37): serve llama.cpp and nothing else — no
    // vault, no window. A packaged build has no bundled `java` to spawn, so the app re-launches its
    // own launcher with this flag; the branch must come before anything touches AWT or Skia.
    if (LlmHostCommandLine.isHostRun(args)) return LlmHostMain.main(args)
    // Before AWT/Skiko: the UI scale is read from the X resource database exactly once, and the
    // first X11 client of a Wayland session gets there before the settings daemon has published it.
    DisplayScaleReadiness.awaitDisplayScale()
    // libsodium (ionspin) requires async init before the first VaultCrypto call; on desktop startup
    // this is done blocking so the dependency graph is already built and ready.
    runBlocking { initializeVaultCrypto() }
    // The dependency graph is built before application{} by a plain function: no Compose state is
    // read inside it, and the build isn't part of composition (so it wouldn't rebuild on root recomposition).
    val dir = configDir()
    val prefs = FilePrefs(dir)
    val graph = buildDesktopGraph(dir, prefs)
    application {
        val deps = graph.deps
        val workspaceLayout = graph.workspaceLayout
        // Window size is fit to the available screen area (excluding the taskbar): ~90% of the
        // screen within MIN_WINDOW..MAX_WINDOW, never larger than the screen itself.
        // maximumWindowBounds accounts for OS panels.
        // The UI language lives at the root: the locale provider above the theme must react to
        // changes from settings and recompose the whole tree. onUiLanguageChange (from
        // DesktopDesignState) updates this state and persists it; DesktopDesignState keeps a copy
        // for the dropdown display.
        val currentUiLanguage = remember { mutableStateOf(prefs.id("ui_language", UiLanguage.DEFAULT, UiLanguage::fromId)) }
        // App theme: held above SkerryTheme so a change from Settings recomposes the whole tree with
        // the new palette. onThemeModeChange (from DesktopDesignState) updates this state and persists.
        val currentThemeMode = remember { mutableStateOf(prefs.id("app_theme", app.skerry.ui.theme.ThemeMode.DEFAULT, app.skerry.ui.theme.ThemeMode::fromId)) }
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val screenSize = DpSize(screen.width.dp, screen.height.dp)
        val windowState = rememberWindowState(
            size = optimalWindowSize(screenSize),
            position = WindowPosition(Alignment.Center),
        )
        val appIcon = painterResource(Res.drawable.skerry_icon)
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Skerry",
            icon = appIcon,
            // The OS titlebar looks different on every system and never matches the app palette:
            // the app draws its own chrome (drag/buttons — WindowChrome, resize — SkerryWindowFrame).
            undecorated = true,
        ) {
          val windowChrome = rememberSkerryWindowChrome(windowState, ::exitApplication)
          // The frame owns the whole resize contract of the undecorated window, the minimum size included.
          SkerryWindowFrame(windowState, screenSize) {
            // Live vault + hosts + sessions + known-hosts are wired up: chrome is behind the master
            // password gate, clicking a host opens a live SSH terminal in a tab (transport+identities
            // from `deps`), and the known-hosts manager runs over its own stores (knownHosts from `deps`).
            AppLocaleProvider(currentUiLanguage.value) {
              app.skerry.ui.theme.SkerryTheme(mode = currentThemeMode.value) {
                app.skerry.ui.desktop.DesktopDesignApp(
                    keyboardInteractive = graph.keyboardInteractive,
                    sessionShare = graph.deps.sessionShare,
                    sharedSessions = graph.deps.sharedSessions,
                    initialCollapsedGroups = prefs.lines("collapsed_groups").toSet(),
                    onCollapsedGroupsChange = { prefs.setLines("collapsed_groups", it.toList()) },
                    initialRecentHostIds = prefs.lines("recent_connections"),
                    onRecentHostIdsChange = { prefs.setLines("recent_connections", it) },
                    // Empty folders sync via the vault: starts empty (vault is locked), reads through
                    // customGroupsProvider after unlock, writes changes to the layout record.
                    initialCustomGroups = emptyList(),
                    onCustomGroupsChange = { groups ->
                        workspaceLayout.write(
                            workspaceLayout.read().copy(
                                groups = groups.filter { it.section == HostSection.Terminal }.map { it.name },
                                remoteDesktopGroups = groups.filter { it.section == HostSection.RemoteDesktops }.map { it.name },
                            ),
                        )
                    },
                    customGroupsProvider = {
                        workspaceLayout.read().let { layout ->
                            layout.groups.map { CustomGroup(it, HostSection.Terminal) } +
                                layout.remoteDesktopGroups.map { CustomGroup(it, HostSection.RemoteDesktops) }
                        }
                    },
                    initialSftpShowHidden = prefs.bool("sftp_show_hidden", true),
                    onSftpShowHiddenChange = { prefs.set("sftp_show_hidden", it) },
                    initialSftpShowModified = prefs.bool("sftp_show_modified", true),
                    onSftpShowModifiedChange = { prefs.set("sftp_show_modified", it) },
                    initialSftpShowPermissions = prefs.bool("sftp_show_permissions", false),
                    onSftpShowPermissionsChange = { prefs.set("sftp_show_permissions", it) },
                    // remember: DesktopDesignState keeps the first instance, so rebuilding this on
                    // every recomposition would only re-read prefs and throw the result away.
                    settings = remember {
                        DesktopSettingsState(
                            initialTerminalFont = prefs.id("terminal_font", TerminalFont.DEFAULT, TerminalFont::fromId),
                            onTerminalFontChange = { prefs.set("terminal_font", it.id) },
                            initialTerminalFontSize = readTerminalFontSize(prefs),
                            onTerminalFontSizeChange = { prefs.set("terminal_font_size", it) },
                            initialTerminalLineHeight = clampTerminalLineHeight(prefs.id("terminal_line_height", DEFAULT_TERMINAL_LINE_HEIGHT) { it.toFloat() }),
                            onTerminalLineHeightChange = { prefs.set("terminal_line_height", it.toString()) },
                            initialTerminalLetterSpacing = clampTerminalLetterSpacing(prefs.id("terminal_letter_spacing", DEFAULT_TERMINAL_LETTER_SPACING) { it.toFloat() }),
                            onTerminalLetterSpacingChange = { prefs.set("terminal_letter_spacing", it.toString()) },
                            initialTerminalTheme = prefs.id("terminal_theme", TerminalThemes.DEFAULT, TerminalThemes::fromId),
                            onTerminalThemeChange = { prefs.set("terminal_theme", it.id) },
                            initialCustomTerminalTheme = prefs.bool("custom_terminal_theme", false),
                            onCustomTerminalThemeChange = { prefs.set("custom_terminal_theme", it) },
                            initialThemeMode = currentThemeMode.value,
                            onThemeModeChange = { prefs.set("app_theme", it.id); currentThemeMode.value = it },
                            initialLocalShellPath = prefs.id("local_shell_path", "") { it },
                            onLocalShellPathChange = { prefs.set("local_shell_path", it) },
                            initialUiLanguage = currentUiLanguage.value,
                            onUiLanguageChange = { currentUiLanguage.value = it; prefs.set("ui_language", it.id) },
                            initialTerminalScrollback = readTerminalScrollback(prefs),
                            onTerminalScrollbackChange = { prefs.set("terminal_scrollback", it) },
                            initialTerminalCursorStyle = prefs.id("terminal_cursor_style", TerminalCursorStyle.DEFAULT, TerminalCursorStyle::fromId),
                            onTerminalCursorStyleChange = { prefs.set("terminal_cursor_style", it.id) },
                            initialShowTerminalTitleOnTabs = prefs.bool("terminal_show_title", false),
                            onShowTerminalTitleOnTabsChange = { prefs.set("terminal_show_title", it) },
                            initialHostClickConnectMode = prefs.id("host_click_connect", HostClickConnectMode.DEFAULT, HostClickConnectMode::fromId),
                            onHostClickConnectModeChange = { prefs.set("host_click_connect", it.id) },
                            initialAllowServerClipboardWrite = prefs.bool("terminal_clipboard_write", false),
                            onAllowServerClipboardWriteChange = { prefs.set("terminal_clipboard_write", it) },
                            initialReportTeamSessions = prefs.bool("teams_report_sessions", true),
                            onReportTeamSessionsChange = { prefs.set("teams_report_sessions", it) },
                            initialOpenFilePathsInSftp = prefs.bool("terminal_open_paths", true),
                            onOpenFilePathsInSftpChange = { prefs.set("terminal_open_paths", it) },
                            initialHighlightCommandLine = prefs.bool("terminal_highlight_input", true),
                            onHighlightCommandLineChange = { prefs.set("terminal_highlight_input", it) },
                            initialHighlightOutput = prefs.bool("terminal_highlight_output", false),
                            onHighlightOutputChange = { prefs.set("terminal_highlight_output", it) },
                            initialConfirmProductionWarnings = prefs.bool("terminal_prod_warnings", false),
                            onConfirmProductionWarningsChange = { prefs.set("terminal_prod_warnings", it) },
                            initialAutoLock = prefs.id("auto_lock", AutoLockDuration.DEFAULT, AutoLockDuration::fromId),
                            onAutoLockChange = { prefs.set("auto_lock", it.id) },
                            initialShowRecent = prefs.bool("recent_show", true),
                            onShowRecentChange = { prefs.set("recent_show", it) },
                            initialRecentLimit = prefs.int("recent_limit", DesktopSettingsState.MAX_RECENT_HOSTS),
                            onRecentLimitChange = { prefs.set("recent_limit", it) },
                            )
                    },
                    vault = deps.vault,
                    biometrics = deps.biometrics,
                    securityLog = graph.securityLog,
                    hosts = deps.hosts,
                    transport = deps.transport,
                    vncTransport = graph.vncTransport,
                    rdpTransport = graph.rdpTransport,
                    audioOutputs = deps.audioOutputs,
                    testTransport = graph.probeTransport,
                    credentials = deps.credentials,
                    knownHosts = deps.knownHosts,
                    trustedCas = deps.trustedCas,
                    keyGenerator = deps.keyGenerator,
                    certificateInspector = deps.certificateInspector,
                    secretFiles = deps.secretFiles,
                    tunnels = deps.tunnels,
                    snippets = deps.snippets,
                    runbooks = deps.runbooks,
                    runbookRunner = deps.runbookRunner,
                    runbookHistory = deps.runbookHistory,
                    sync = deps.sync,
                    teams = deps.teams,
                    ai = graph.ai,
                    updates = graph.updates,
                    onVaultUnlocked = graph.onVaultUnlocked,
                    onVaultReset = graph.onVaultReset,
                    windowChrome = windowChrome,
                )
              }
            }
          }
        }
    }
}
