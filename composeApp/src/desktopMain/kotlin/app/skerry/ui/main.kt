package app.skerry.ui

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
import app.skerry.ui.desktop.SkerryWindowFrame
import app.skerry.ui.desktop.optimalWindowSize
import app.skerry.ui.desktop.rememberSkerryWindowChrome
import java.awt.GraphicsEnvironment
import app.skerry.shared.host.VaultHostStore
import app.skerry.shared.io.PrivateConfig
import app.skerry.shared.ssh.FileHostKeyMismatchStore
import app.skerry.shared.ssh.VaultKnownHostsStore
import app.skerry.shared.ssh.ProbeHostKeyVerifier
import app.skerry.shared.ssh.RoutingTransport
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.ssh.SshjTransport
import app.skerry.shared.ssh.TofuHostKeyVerifier
import app.skerry.shared.vault.BouncyCastleSshKeyGenerator
import app.skerry.shared.vault.FileSecurityLog
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.CredentialStore
import app.skerry.shared.vault.WorkspaceLayoutStore
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.SshjCertificateInspector
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.shared.snippet.VaultSnippetStore
import app.skerry.shared.sync.FileSyncStateStore
import app.skerry.shared.sync.KtorSyncClient
import app.skerry.shared.tunnel.VaultTunnelStore
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
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.i18n.AppLocaleProvider
import app.skerry.ui.i18n.UiLanguage
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.KnownHostsController
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
 * not accessible to other local users regardless of their permissions.
 */
private fun configDir(): Path {
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
    val securityLog: FileSecurityLog,
    val probeTransport: SshTransport,
    val vncTransport: app.skerry.shared.vnc.VncTransport,
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
    val mismatchStore = FileHostKeyMismatchStore(dir.resolve("known_hosts_mismatches"))
    // Live session transport: routes by connection type (SSH/Telnet/Serial). SSH carries the TOFU
    // verifier/known-hosts; Telnet/Serial are stateless (created internally with defaults).
    val transport = RoutingTransport(
        ssh = SshjTransport(
            TofuHostKeyVerifier(knownHostsStore, mismatchStore) { Instant.now().toString() },
        ),
    )
    // "Test connection" from the form uses a separate transport with a read-only verifier: it
    // accepts a matching trusted key, rejects a key change on a known host, and accepts a new host
    // WITHOUT writing to known-hosts. Only a real connection (TOFU above) establishes trust.
    val probeTransport = SshjTransport(ProbeHostKeyVerifier(knownHostsStore))
    val knownHosts = KnownHostsController(knownHostsStore, mismatchStore) { Instant.now().toString() }
    // Host manager: profiles are HOST records in the vault, tree order lives in the layout record
    // ([VaultHostStore]/[WorkspaceLayout]). The vault starts locked (empty list); the controller
    // reloads via reload() after unlock. id is a random UUID.
    val hostStore = VaultHostStore(vault)
    val hosts = HostManagerController(hostStore) { UUID.randomUUID().toString() }
    // Workspace layout in the vault: empty folders (and tree order) sync as a single record. Read
    // after unlock (vault starts locked) and written on change.
    val workspaceLayout = WorkspaceLayoutStore(vault)
    // Flat vault model: keychain secrets are CREDENTIAL records; a host references a secret
    // directly via credentialId.
    val credentials = CredentialManagerController(CredentialStore(vault)) { UUID.randomUUID().toString() }
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
        newTeamId = { UUID.randomUUID().toString() },
        onTeamsChanged = { reloadManagers() },
    )
    teamsForSync = teams
    // A missing team key (skipped by an older client's delta sync) is only fixed by a full re-pull.
    teams.onKeyMissing = { sync.recoverFullPull() }
    sync.onTeamSignal = teams::onSignal
    // SSH key generation in the Vault section: BouncyCastle over the sshj format (the same one the transport reads).
    val keyGenerator = BouncyCastleSshKeyGenerator()
    // Parses imported SSH certificates (Vault -> Certificates section) via sshj over the ssh-wire format.
    val certificateInspector = SshjCertificateInspector()
    // Global tunnels: saved forwards in tunnels.json. Activation uses a SEPARATE probe transport
    // (read-only verifier): only an already-trusted host can be enabled — a tunnel opens without a
    // terminal, so silent TOFU must not happen here. Host/secret resolution goes through the graph
    // (hosts + credentials in the unlocked vault). Scope lives for the app's lifetime.
    val tunnelTransport = SshjTransport(ProbeHostKeyVerifier(knownHostsStore))
    val tunnelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tunnels = TunnelManager(
        store = VaultTunnelStore(vault),
        transport = tunnelTransport,
        resolve = { hostId -> resolveTunnelHost(hostId, findHost = hosts::find, findCredential = credentials::find) },
        scope = tunnelScope,
    ) { UUID.randomUUID().toString() }
    // Saved snippets: the command library is SNIPPET records in the vault (commands may contain
    // inline credentials, so they share encryption and E2E sync). Run targets the active terminal.
    val snippets = SnippetManager(VaultSnippetStore(vault)) { UUID.randomUUID().toString() }
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
        tunnels.reload()
        knownHosts.refresh()
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
        // keep-connected: vault opened means a dataKey exists, so the sync session can be silently restored.
        sync.restoreSession()
    }
    // External cleanup on an irreversible vault reset (forgotten password / corrupted file). The
    // vault file is already erased by the controller (Vault.reset) and now locked, so
    // credentials.reload() is NOT called here (it requires an open vault); the secret list rereads
    // when a new vault is created.
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
            prefs.set("auto_lock", AutoLockDuration.DEFAULT.id)
        }
        hosts.reload()
        snippets.reload()
        tunnels.reload()
    }
    val deps = AppDependencies(transport = transport, hosts = hosts, vault = vault, credentials = credentials, knownHosts = knownHosts, keyGenerator = keyGenerator, certificateInspector = certificateInspector, tunnels = tunnels, snippets = snippets, sync = sync, teams = teams, localAi = localAi)
    return DesktopGraph(
        deps = deps,
        securityLog = securityLog,
        probeTransport = probeTransport,
        vncTransport = app.skerry.shared.vnc.VncTcpTransport(),
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
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val windowState = rememberWindowState(
            size = optimalWindowSize(DpSize(screen.width.dp, screen.height.dp)),
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
          SkerryWindowFrame(windowState) {
            // Live vault + hosts + sessions + known-hosts are wired up: chrome is behind the master
            // password gate, clicking a host opens a live SSH terminal in a tab (transport+identities
            // from `deps`), and the known-hosts manager runs over its own stores (knownHosts from `deps`).
            AppLocaleProvider(currentUiLanguage.value) {
              app.skerry.ui.theme.SkerryTheme {
                app.skerry.ui.desktop.DesktopDesignApp(
                    initialInfoPanel = prefs.bool("info_panel", true),
                    onInfoPanelChange = { prefs.set("info_panel", it) },
                    initialCollapsedGroups = prefs.lines("collapsed_groups").toSet(),
                    onCollapsedGroupsChange = { prefs.setLines("collapsed_groups", it.toList()) },
                    initialRecentHostIds = prefs.lines("recent_connections"),
                    onRecentHostIdsChange = { prefs.setLines("recent_connections", it) },
                    // Empty folders sync via the vault: starts empty (vault is locked), reads through
                    // customGroupsProvider after unlock, writes changes to the layout record.
                    initialCustomGroups = emptyList(),
                    onCustomGroupsChange = { groups -> workspaceLayout.write(workspaceLayout.read().copy(groups = groups)) },
                    customGroupsProvider = { workspaceLayout.read().groups },
                    initialSftpShowHidden = prefs.bool("sftp_show_hidden", true),
                    onSftpShowHiddenChange = { prefs.set("sftp_show_hidden", it) },
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
                    initialUiLanguage = currentUiLanguage.value,
                    onUiLanguageChange = { currentUiLanguage.value = it; prefs.set("ui_language", it.id) },
                    initialTerminalScrollback = readTerminalScrollback(prefs),
                    onTerminalScrollbackChange = { prefs.set("terminal_scrollback", it) },
                    initialTerminalCursorStyle = prefs.id("terminal_cursor_style", TerminalCursorStyle.DEFAULT, TerminalCursorStyle::fromId),
                    onTerminalCursorStyleChange = { prefs.set("terminal_cursor_style", it.id) },
                    initialShowTerminalTitleOnTabs = prefs.bool("terminal_show_title", false),
                    onShowTerminalTitleOnTabsChange = { prefs.set("terminal_show_title", it) },
                    initialAllowServerClipboardWrite = prefs.bool("terminal_clipboard_write", false),
                    onAllowServerClipboardWriteChange = { prefs.set("terminal_clipboard_write", it) },
                    initialAutoLock = prefs.id("auto_lock", AutoLockDuration.DEFAULT, AutoLockDuration::fromId),
                    onAutoLockChange = { prefs.set("auto_lock", it.id) },
                    initialShowRecent = prefs.bool("recent_show", true),
                    onShowRecentChange = { prefs.set("recent_show", it) },
                    initialRecentLimit = prefs.int("recent_limit", DesktopDesignState.MAX_RECENT_HOSTS),
                    onRecentLimitChange = { prefs.set("recent_limit", it) },
                    vault = deps.vault,
                    biometrics = deps.biometrics,
                    securityLog = graph.securityLog,
                    hosts = deps.hosts,
                    transport = deps.transport,
                    vncTransport = graph.vncTransport,
                    testTransport = graph.probeTransport,
                    credentials = deps.credentials,
                    knownHosts = deps.knownHosts,
                    keyGenerator = deps.keyGenerator,
                    certificateInspector = deps.certificateInspector,
                    tunnels = deps.tunnels,
                    snippets = deps.snippets,
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
