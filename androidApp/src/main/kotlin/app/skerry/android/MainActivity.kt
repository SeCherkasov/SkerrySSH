package app.skerry.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.skerry.shared.ai.local.IsolatedLlmRuntime
import app.skerry.shared.ai.local.LocalModelStore
import app.skerry.shared.ai.local.ServiceLlmHostLauncher
import app.skerry.shared.ai.local.ModelDownloader
import app.skerry.shared.host.VaultHostStore
import app.skerry.shared.ssh.FileHostKeyMismatchStore
import app.skerry.shared.ssh.VaultKnownHostsStore
import app.skerry.shared.ssh.ReadOnlyHostKeyVerifier
import app.skerry.shared.ssh.UnknownHost
import app.skerry.shared.ssh.RoutingTransport
import app.skerry.shared.ssh.SshjTransport
import app.skerry.shared.ssh.KeyFileResolver
import app.skerry.shared.vault.OkioSecretFileReader
import app.skerry.ui.vault.AndroidSecretFileReader
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.KeyboardInteractivePromptController
import app.skerry.shared.ssh.HostCertificateVerifier
import app.skerry.shared.ssh.SshjCaKeyParser
import app.skerry.shared.ssh.TofuHostKeyVerifier
import app.skerry.shared.ssh.VaultTrustedCaStore
import app.skerry.shared.snippet.VaultSnippetStore
import app.skerry.shared.sync.FileSyncStateStore
import app.skerry.shared.sync.KtorSyncClient
import app.skerry.shared.tunnel.VaultTunnelStore
import app.skerry.shared.vault.AndroidBiometricKeyStore
import app.skerry.shared.vault.BouncyCastleSshKeyGenerator
import app.skerry.shared.vault.FileBioArtifactStore
import app.skerry.shared.vault.FileBiometricSupportStore
import app.skerry.shared.vault.CredentialStore
import app.skerry.shared.vault.FileCredentialUsageLog
import app.skerry.shared.vault.FileSecurityLog
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.SshjCertificateInspector
import app.skerry.shared.vault.TrashStore
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.ui.AppDependencies
import app.skerry.ui.ai.LocalAiDeps
import app.skerry.ui.mobile.MobileDesignApp
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.secure.WindowBridge
import app.skerry.ui.sftp.SafBridge
import app.skerry.ui.vault.AndroidLockContext
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.keepalive.SessionKeepAlive
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.known.TrustedCaController
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.DEFAULT_TERMINAL_SCROLLBACK
import app.skerry.ui.i18n.AppLocaleProvider
import app.skerry.ui.i18n.UiLanguage
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_RANGE
import app.skerry.ui.terminal.TERMINAL_SCROLLBACK_OPTIONS
import app.skerry.ui.terminal.TerminalCursorStyle
import app.skerry.ui.terminal.TerminalFont
import app.skerry.ui.terminal.TerminalTheme
import app.skerry.ui.terminal.TerminalThemes
import app.skerry.ui.theme.SkerryTheme
import app.skerry.ui.theme.ThemeMode
import app.skerry.ui.theme.isDark
import app.skerry.ui.tunnel.TunnelManager
import app.skerry.ui.tunnel.resolveTunnelHost
import app.skerry.ui.vault.AutoLockDuration
import app.skerry.ui.vault.ResetScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import java.lang.ref.WeakReference
import java.time.Instant
import java.util.UUID

/**
 * Android entry point. [FragmentActivity] (not `ComponentActivity`) is required by
 * `androidx.biometric.BiometricPrompt`. Builds the dependency graph: local encrypted vault in the
 * private `filesDir`, cross-platform crypto (ionspin), okio-backed store.
 */
class MainActivity : FragmentActivity() {

    companion object {
        // Process-scoped keep-alive graph: sessions + their coroutine scope survive Activity
        // recreation (background recycle, task swipe). The foreground service keeps the process
        // alive; this keeps the connections alive inside it, so returning to the app (or tapping a
        // per-session notification) shows the same live terminal instead of a dropped session.
        // Built lazily from the first dependency graph; null until the first onCreate.
        @Volatile
        private var keepAliveScope: CoroutineScope? = null
        @Volatile
        private var keepAliveSessions: app.skerry.ui.session.SessionsController? = null
        // Terminal prefs read at connect time. Refreshed by the UI on every composition so a
        // settings change applies to NEW sessions even when they're opened from the process-scoped
        // controller (which outlives any composition). Never captures the Activity.
        @Volatile
        var currentTerminalPrefs: () -> app.skerry.ui.terminal.TerminalSessionPrefs =
            { app.skerry.ui.terminal.TerminalSessionPrefs() }
        // Session id routed from a per-session notification tap; the UI activates that tab once.
        // Compose state (not @Volatile) so the LaunchedEffect key reacts to the tap.
        var pendingSessionId by mutableStateOf<String?>(null)

        fun keepAliveScope(): CoroutineScope =
            keepAliveScope
                ?: CoroutineScope(SupervisorJob() + Dispatchers.Default).also { keepAliveScope = it }
    }

    // Tunnel manager scope, tied to Activity lifetime. Cancelled in onDestroy so a recreate (rotation)
    // doesn't leave the old polling scope orphaned; active tunnels are dropped in that case.
    private var tunnelScope: CoroutineScope? = null

    // Prompt controller for keyboard-interactive challenges, created with the dependency graph and
    // read back when the UI is composed.
    private var keyboardInteractive: KeyboardInteractivePromptController? = null

    // External cleanup on irrecoverable vault reset. The vault itself is already wiped and locked by
    // the controller, so this only clears data outside the vault (host profiles, known_hosts, tunnels).
    // Set in [buildDependencies]; passed to [MobileDesignApp].
    private var onVaultReset: (ResetScope) -> Unit = {}

    // One-time secret migration on unlock. Field because it references the dependency graph; set in
    // [buildDependencies], invoked from [MobileDesignApp].
    private var onVaultUnlocked: () -> Unit = {}

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeSessionTap(intent)
    }

    override fun onDestroy() {
        tunnelScope?.cancel()
        tunnelScope = null
        super.onDestroy()
    }

    /** A per-session notification tap carries the session id — remember it for the UI to activate. */
    private fun routeSessionTap(intent: Intent?) {
        val id = intent?.getStringExtra(SessionKeepAliveService.EXTRA_SESSION_ID)
        id?.let { pendingSessionId = it }
    }

    // Launcher registered once (ActivityResult API requires registration before STARTED); the actual
    // request is deferred to the first session open via [requestNotificationPermissionIfNeeded].
    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private var notificationPermissionAsked = false

    /** Android 13+ runtime permission for the keep-alive notification; asked once, lazily. */
    private fun requestNotificationPermissionIfNeeded() {
        if (notificationPermissionAsked) return
        notificationPermissionAsked = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Window handed to WindowBridge so the shared UI can toggle FLAG_SECURE on screens with
        // secrets (vault, master password entry); see SecureScreen. Weak reference, no Activity leak.
        WindowBridge.install(window)

        // Session keep-alive: while any SSH session is open, run the foreground service so
        // backgrounding the app doesn't freeze the connection (Android only; desktop never sets
        // this). Notification permission (Android 13+) is requested lazily, on the FIRST session
        // open, so it never interrupts app startup — the service still runs without it, the user
        // simply doesn't see the "session active" notification until they grant it.
        SessionKeepAlive.bridge = AndroidSessionKeepAlive(
            applicationContext,
            onFirstSession = { requestNotificationPermissionIfNeeded() },
        )

        // Context for keyguard checks: auto-lock on background should trigger only when the device is
        // actually locked, not when a system picker is open (see deviceMandatesAutoLock).
        AndroidLockContext.appContext = applicationContext

        // Context for USB-OTG serial: the static SerialSystem reads it from here (enumerate + permission).
        app.skerry.shared.serial.SerialUsbBridge.install(applicationContext)

        // SFTP SAF pickers: launchers are registered in onCreate (ActivityResult API requires
        // registration before STARTED) and handed to SafBridge as launch lambdas so the shared UI code
        // stays Activity-independent. octet-stream for every created document — SAF keeps the caller's
        // extension only when it maps back to the requested MIME, and neither `.pem` nor `.cast` maps
        // to text/plain, so a text launcher would file an exported key as `id_ed25519.pem.txt`.
        // "*/*" for any upload.
        val createDocument = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri -> SafBridge.onCreateResult(uri) }
        val openDocument = registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri -> SafBridge.onOpenResult(uri) }
        SafBridge.install(
            applicationContext,
            launchCreate = { name -> createDocument.launch(name) },
            launchOpen = { openDocument.launch(arrayOf("*/*")) },
        )

        // libsodium (ionspin) needs async init before the first VaultCrypto call; done blocking at
        // startup so the dependency graph is ready when built.
        runBlocking { initializeVaultCrypto() }

        val deps = buildDependencies()
        // Process-scoped keep-alive: build the sessions graph once (lives across Activity
        // recreation while the foreground service keeps the process alive). Recreated only on
        // process death; notification taps route into it via pendingSessionId below.
        if (keepAliveSessions == null && deps.transport != null) {
            keepAliveSessions = buildKeepAliveSessions(deps)
        }
        routeSessionTap(intent)
        // Layout state with persisted collapsed host groups: the set of names survives restart.
        // Created once here and held by composition.
        val dir = filesDir
        setContent {
            // UI language lives at the root: a locale provider above MobileDesignApp reacts to
            // settings changes and recomposes the tree. onUiLanguageChange (from MobileDesignState)
            // updates this state and persists it; MobileDesignState keeps a copy for its dropdown.
            val currentUiLanguage = remember { mutableStateOf(readUiLanguage(dir)) }
            val designState = remember {
                MobileDesignState(
                    initialCollapsedGroups = readCollapsedGroups(dir),
                    onCollapsedGroupsChange = { writeCollapsedGroups(dir, it) },
                    initialTerminalFont = readTerminalFont(dir),
                    onTerminalFontChange = { writeTerminalFont(dir, it) },
                    initialTerminalFontSize = readTerminalFontSize(dir),
                    onTerminalFontSizeChange = { writeTerminalFontSize(dir, it) },
                    initialAllowServerClipboardWrite = readClipboardWrite(dir),
                    onAllowServerClipboardWriteChange = { writeClipboardWrite(dir, it) },
                    initialReportTeamSessions = readReportTeamSessions(dir),
                    onReportTeamSessionsChange = { writeReportTeamSessions(dir, it) },
                    initialOpenFilePathsInSftp = readOpenFilePaths(dir),
                    onOpenFilePathsInSftpChange = { writeOpenFilePaths(dir, it) },
                    initialHighlightCommandLine = readHighlightInput(dir),
                    onHighlightCommandLineChange = { writeHighlightInput(dir, it) },
                    initialHighlightOutput = readHighlightOutput(dir),
                    onHighlightOutputChange = { writeHighlightOutput(dir, it) },
                    initialConfirmProductionWarnings = readProdWarnings(dir),
                    onConfirmProductionWarningsChange = { writeProdWarnings(dir, it) },
                    initialHideSessionSystemBars = readHideSystemBars(dir),
                    onHideSessionSystemBarsChange = { writeHideSystemBars(dir, it) },
                    initialUiLanguage = currentUiLanguage.value,
                    onUiLanguageChange = { currentUiLanguage.value = it; writeUiLanguage(dir, it) },
                    initialAutoLock = readAutoLock(dir),
                    onAutoLockChange = { writeAutoLock(dir, it) },
                    initialTerminalScrollback = readTerminalScrollback(dir),
                    onTerminalScrollbackChange = { writeTerminalScrollback(dir, it) },
                    initialTerminalCursorStyle = readTerminalCursorStyle(dir),
                    onTerminalCursorStyleChange = { writeTerminalCursorStyle(dir, it) },
                    initialTerminalTheme = readTerminalTheme(dir),
                    onTerminalThemeChange = { writeTerminalTheme(dir, it) },
                    initialCustomTerminalTheme = readCustomTerminalTheme(dir),
                    onCustomTerminalThemeChange = { writeCustomTerminalTheme(dir, it) },
                    initialThemeMode = readThemeMode(dir),
                    onThemeModeChange = { writeThemeMode(dir, it) },
                )
            }
            // System-bar icon contrast follows the APP theme, not the OS: edge-to-edge draws the
            // app's background behind the bars, so a light app theme needs dark icons even when
            // the OS itself is in dark mode (enableEdgeToEdge alone keys off the OS uiMode).
            val appDark = designState.themeMode.isDark(isSystemInDarkTheme())
            LaunchedEffect(appDark) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !appDark
                    isAppearanceLightNavigationBars = !appDark
                }
            }
            AppLocaleProvider(currentUiLanguage.value) {
                // App theme at the root: reads designState.themeMode, so a change from the theme picker
                // recomposes the whole tree with the new palette (mirrors the desktop wiring in main.kt).
                SkerryTheme(mode = designState.themeMode) {
                    // Terminal prefs are read at connect time; keep the process-scoped controller's
                    // factory pointed at the live settings (it outlives this composition).
                    LaunchedEffect(designState) {
                        currentTerminalPrefs = {
                            app.skerry.ui.terminal.TerminalSessionPrefs(
                                designState.terminalScrollback,
                                designState.terminalCursorStyle,
                                clipboardWriteEnabled = designState.allowServerClipboardWrite,
                            )
                        }
                    }
                    // A per-session notification tap: activate the tapped terminal tab AND navigate
                    // to its screen. activate() alone switches the internal active tab but leaves the
                    // user on whatever screen was up (Hosts) — the Sessions list does both steps, so a
                    // notification tap must too. Cleared unconditionally — a stale id (session already
                    // closed) must not wedge the state.
                    LaunchedEffect(pendingSessionId) {
                        val target = pendingSessionId
                        if (target != null) {
                            val sessions = keepAliveSessions
                            if (sessions != null) {
                                val tab = sessions.tabs.firstOrNull { it.id == target }
                                if (tab != null) {
                                    sessions.activate(target)
                                    designState.push(if (tab.isVnc) MobileRoute.Vnc else MobileRoute.Terminal)
                                }
                            }
                            pendingSessionId = null
                        }
                    }
                    MobileDesignApp(
                        deps,
                        keyboardInteractive = keyboardInteractive,
                        state = designState,
                        sessions = keepAliveSessions,
                        processScope = keepAliveScope(),
                        onVaultReset = onVaultReset,
                        // Secret migration + reload + sync session restore.
                        onVaultUnlocked = onVaultUnlocked,
                    )
                }
            }
        }
    }

    /**
     * Collapsed host groups, persisted across restarts: group names in `collapsed_groups`, one per
     * line. Missing/unreadable → empty (all groups expanded). Write is best-effort.
     */
    private fun readCollapsedGroups(dir: File): Set<String> = runCatching {
        File(dir, "collapsed_groups").readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }.getOrDefault(emptySet())

    private fun writeCollapsedGroups(dir: File, groups: Set<String>) {
        // Names containing newlines can't be stored one-per-line; excluded to avoid splitting the file.
        // Snapshot taken synchronously; write happens off the UI thread.
        val snapshot = groups.filterNot { it.contains('\n') || it.contains('\r') }.joinToString("\n")
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "collapsed_groups").writeText(snapshot) }
        }
    }

    /**
     * Terminal font (More → Appearance → Font), persisted across restarts as a stable id
     * ([TerminalFont.id]) in `terminal_font`. Missing/unreadable/unknown → [TerminalFont.DEFAULT].
     * Write is best-effort, off the UI thread.
     */
    private fun readTerminalFont(dir: File): TerminalFont = runCatching {
        TerminalFont.fromId(File(dir, "terminal_font").readText().trim())
    }.getOrDefault(TerminalFont.DEFAULT)

    private fun writeTerminalFont(dir: File, font: TerminalFont) {
        val id = font.id
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "terminal_font").writeText(id) }
        }
    }

    /**
     * Auto-lock idle threshold (More → Security): stable [AutoLockDuration.id] in `auto_lock`.
     * Missing/unreadable/unknown → default (5 minutes). Write is best-effort, off the UI thread.
     */
    private fun readAutoLock(dir: File): AutoLockDuration = runCatching {
        AutoLockDuration.fromId(File(dir, "auto_lock").readText().trim())
    }.getOrDefault(AutoLockDuration.DEFAULT)

    private fun writeAutoLock(dir: File, duration: AutoLockDuration) {
        val id = duration.id
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "auto_lock").writeText(id) }
        }
    }

    /**
     * OSC 52 server clipboard-write gate (More → Appearance → Terminal): "true"/"false" in
     * `terminal_clipboard_write`. Missing/unreadable → false (off by default). Best-effort, off the UI thread.
     */
    private fun readClipboardWrite(dir: File): Boolean = runCatching {
        File(dir, "terminal_clipboard_write").readText().trim().toBoolean()
    }.getOrDefault(false)

    private fun writeClipboardWrite(dir: File, enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "terminal_clipboard_write").writeText(enabled.toString()) }
        }
    }

    /** Reporting sessions on team-shared hosts: `teams_report_sessions`, default on. */
    private fun readReportTeamSessions(dir: File): Boolean = runCatching {
        File(dir, "teams_report_sessions").readText().trim().toBoolean()
    }.getOrDefault(true)

    private fun writeReportTeamSessions(dir: File, enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "teams_report_sessions").writeText(enabled.toString()) }
        }
    }

    /** Clickable file paths in terminal output: `terminal_open_paths`, default on. */
    private fun readOpenFilePaths(dir: File): Boolean = runCatching {
        File(dir, "terminal_open_paths").readText().trim().toBoolean()
    }.getOrDefault(true)

    private fun writeOpenFilePaths(dir: File, enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "terminal_open_paths").writeText(enabled.toString()) }
        }
    }

    /** Command-line syntax highlighting: `terminal_highlight_input`, default on. */
    private fun readHighlightInput(dir: File): Boolean = runCatching {
        File(dir, "terminal_highlight_input").readText().trim().toBoolean()
    }.getOrDefault(true)

    private fun writeHighlightInput(dir: File, enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "terminal_highlight_input").writeText(enabled.toString()) }
        }
    }

    /** Log-level highlighting in output: `terminal_highlight_output`, default off. */
    private fun readHighlightOutput(dir: File): Boolean = runCatching {
        File(dir, "terminal_highlight_output").readText().trim().toBoolean()
    }.getOrDefault(false)

    private fun writeHighlightOutput(dir: File, enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "terminal_highlight_output").writeText(enabled.toString()) }
        }
    }

    /** Production guard threshold: `terminal_prod_warnings`, default off (Danger only). */
    private fun readProdWarnings(dir: File): Boolean = runCatching {
        File(dir, "terminal_prod_warnings").readText().trim().toBoolean()
    }.getOrDefault(false)

    private fun writeProdWarnings(dir: File, enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "terminal_prod_warnings").writeText(enabled.toString()) }
        }
    }

    /**
     * Hiding the phone's system bars inside a session (More → Appearance → Interface):
     * `hide_system_bars`, default off — the bars belong to the phone.
     */
    private fun readHideSystemBars(dir: File): Boolean = runCatching {
        File(dir, "hide_system_bars").readText().trim().toBoolean()
    }.getOrDefault(false)

    private fun writeHideSystemBars(dir: File, enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "hide_system_bars").writeText(enabled.toString()) }
        }
    }

    /** Separately-picked terminal theme flag (unified theming): `custom_terminal_theme`, default off. */
    private fun readCustomTerminalTheme(dir: File): Boolean = runCatching {
        File(dir, "custom_terminal_theme").readText().trim().toBoolean()
    }.getOrDefault(false)

    private fun writeCustomTerminalTheme(dir: File, enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "custom_terminal_theme").writeText(enabled.toString()) }
        }
    }

    /**
     * Terminal font size, px (More → Appearance → Font size): a number in `terminal_font_size`.
     * Missing/unreadable/outside [TERMINAL_FONT_SIZE_RANGE] → [DEFAULT_TERMINAL_FONT_SIZE].
     */
    private fun readTerminalFontSize(dir: File): Int {
        val px = runCatching { File(dir, "terminal_font_size").readText().trim().toInt() }
            .getOrDefault(DEFAULT_TERMINAL_FONT_SIZE)
        return if (px in TERMINAL_FONT_SIZE_RANGE) px else DEFAULT_TERMINAL_FONT_SIZE
    }

    private fun writeTerminalFontSize(dir: File, px: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "terminal_font_size").writeText(px.toString()) }
        }
    }

    /**
     * Scrollback depth for new sessions (More → Appearance → Terminal): a number in
     * `terminal_scrollback`. Missing/unreadable/outside [TERMINAL_SCROLLBACK_OPTIONS] →
     * [DEFAULT_TERMINAL_SCROLLBACK]. Write is best-effort, off the UI thread.
     */
    private fun readTerminalScrollback(dir: File): Int {
        val lines = runCatching { File(dir, "terminal_scrollback").readText().trim().toInt() }
            .getOrDefault(DEFAULT_TERMINAL_SCROLLBACK)
        return if (lines in TERMINAL_SCROLLBACK_OPTIONS) lines else DEFAULT_TERMINAL_SCROLLBACK
    }

    private fun writeTerminalScrollback(dir: File, lines: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "terminal_scrollback").writeText(lines.toString()) }
        }
    }

    /**
     * Default cursor style for new sessions (More → Appearance → Terminal): stable
     * [TerminalCursorStyle.id] in `terminal_cursor_style`. Missing/unreadable/unknown →
     * [TerminalCursorStyle.DEFAULT]. Write is best-effort, off the UI thread.
     */
    private fun readTerminalCursorStyle(dir: File): TerminalCursorStyle = runCatching {
        TerminalCursorStyle.fromId(File(dir, "terminal_cursor_style").readText().trim())
    }.getOrDefault(TerminalCursorStyle.DEFAULT)

    private fun writeTerminalCursorStyle(dir: File, style: TerminalCursorStyle) {
        val id = style.id
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "terminal_cursor_style").writeText(id) }
        }
    }

    /**
     * UI language (More → Appearance → Language): stable id ([UiLanguage.id]) in `ui_language`.
     * Missing/unreadable/unknown → [UiLanguage.DEFAULT] (System, follows OS locale). Write is
     * best-effort, off the UI thread.
     */
    private fun readUiLanguage(dir: File): UiLanguage = runCatching {
        UiLanguage.fromId(File(dir, "ui_language").readText().trim())
    }.getOrDefault(UiLanguage.DEFAULT)

    private fun writeUiLanguage(dir: File, language: UiLanguage) {
        val id = language.id
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "ui_language").writeText(id) }
        }
    }

    /**
     * Terminal color theme (More → Appearance → cards): stable [TerminalTheme.id] in `terminal_theme`.
     * Missing/unreadable/unknown → [TerminalThemes.DEFAULT]. Write is best-effort, off the UI thread.
     */
    private fun readTerminalTheme(dir: File): TerminalTheme = runCatching {
        TerminalThemes.fromId(File(dir, "terminal_theme").readText().trim())
    }.getOrDefault(TerminalThemes.DEFAULT)

    private fun writeTerminalTheme(dir: File, theme: TerminalTheme) {
        val id = theme.id
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "terminal_theme").writeText(id) }
        }
    }

    /**
     * App theme (More → Appearance → theme): stable [ThemeMode.id] in `app_theme`.
     * Missing/unreadable/unknown → [ThemeMode.DEFAULT] (night-sea dark). Best-effort, off the UI thread.
     */
    private fun readThemeMode(dir: File): ThemeMode = runCatching {
        ThemeMode.fromId(File(dir, "app_theme").readText().trim())
    }.getOrDefault(ThemeMode.DEFAULT)

    private fun writeThemeMode(dir: File, mode: ThemeMode) {
        val id = mode.id
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { File(dir, "app_theme").writeText(id) }
        }
    }

    /**
     * Builds the process-scoped sessions controller (keep-alive): it survives Activity recreation
     * while the foreground service keeps the process alive, so backgrounding the app does not drop
     * connections. Mirror of the MobileDesignApp internal factory, except the connection scope is
     * process-scoped and terminal prefs come from [currentTerminalPrefs] (live settings, refreshed
     * by the UI on every composition).
     */
    private fun buildKeepAliveSessions(deps: AppDependencies): app.skerry.ui.session.SessionsController {
        val scope = keepAliveScope()
        val t = deps.transport!!
        var counter = 0
        return app.skerry.ui.session.SessionsController(
            newId = { "sess-${counter++}" },
            vncControllerFactory = deps.vncTransport?.let { { app.skerry.ui.remote.RemoteDesktopController(scope) } },
            openVncSession = deps.vncTransport?.let { vt ->
                { target, auth -> app.skerry.shared.vnc.VncRemoteDesktop(vt.connect(target, auth)) }
            },
            openRdpSession = deps.rdpTransport?.let { rt ->
                { request ->
                    app.skerry.shared.rdp.RdpRemoteDesktop(
                        rt.connect(
                            app.skerry.shared.rdp.RdpTarget(
                                host = request.host,
                                port = request.port,
                                desktopWidth = request.width,
                                desktopHeight = request.height,
                                clientName = request.clientName,
                                loadBalanceInfo = request.loadBalanceInfo,
                                audioOutput = request.audioOutput,
                                audioDeviceId = request.audioDeviceId,
                                clipboard = request.clipboard,
                                imageQuality = request.imageQuality,
                            ),
                            app.skerry.shared.rdp.RdpCredentials(
                                username = request.user,
                                password = request.password,
                                domain = request.domain,
                            ),
                        ),
                    )
                }
            },
            // Desktop parity: the session half of the Teams activity feed (the coordinator holds
            // the privacy gates).
            onHostSessionOpened = { hostId -> deps.teams?.reportSessionOpened(hostId) },
            controllerFactory = {
                ConnectionController(
                    t, scope,
                    history = deps.vault?.let { app.skerry.shared.terminal.VaultTerminalHistoryStore(it) },
                    // Terminal settings are read at connect time — the process-scoped factory reads
                    // the live settings via the static provider instead of capturing a composition.
                    terminalPrefs = { currentTerminalPrefs() },
                )
            },
        )
    }

    private fun buildDependencies(): AppDependencies {
        val dir = filesDir
        val crypto = IonspinVaultCrypto()
        val vault = FileVault(
            dir.resolve("vault.json").absolutePath.toPath(),
            crypto,
            deviceId(dir),
            FileSystem.SYSTEM,
        ) { System.currentTimeMillis().toString() }
        // Local (non-synced) security event log: master password changes, biometrics, unlocks. Written
        // by the controller behind the gate; read by the More → Security section. ISO instant clock so
        // securityMoment parses correctly.
        val securityLog = FileSecurityLog(
            dir.resolve("security_events.json").absolutePath.toPath(),
            FileSystem.SYSTEM,
            // File(...).toPath() (API 26) instead of Path.of (needs API 34) — compatible with minSdk 26.
            harden = { app.skerry.shared.io.PrivateConfig.harden(java.io.File(it.toString()).toPath()) },
        ) { Instant.now().toString() }
        // Trash for the personal vault: deletions made through these stores keep a restorable
        // snapshot (More -> Trash). Passed explicitly — the stores default to deleting outright so a
        // team vault never grows a trash of its own.
        val trash = TrashStore(vault)
        // Local (non-synced) usage trail the Vault sheet reports: added / last used / copies. Ids and
        // timestamps only, hardened like the security log above.
        val credentialUsage = FileCredentialUsageLog(
            dir.resolve("credential_usage.json").absolutePath.toPath(),
            FileSystem.SYSTEM,
            harden = { app.skerry.shared.io.PrivateConfig.harden(java.io.File(it.toString()).toPath()) },
        ) { Instant.now().toString() }
        val credentials = CredentialManagerController(CredentialStore(vault, trash), credentialUsage) { UUID.randomUUID().toString() }
        // SSH transport (sshj, shared JVM source set). TOFU: a host's first key is remembered in the
        // vault (RecordType.KNOWN_HOST, synced across devices); a key change is rejected and logged to
        // the local (non-synced) known_hosts_mismatches so the known-hosts manager can warn and let the
        // user accept or reject the new key.
        val knownHostsStore = VaultKnownHostsStore(vault)
        val mismatchStore = FileHostKeyMismatchStore(dir.resolve("known_hosts_mismatches").toPath())
        // Live session transport: routes by connection type (SSH/Telnet/Serial). SSH carries the
        // TOFU verifier/known-hosts; Telnet/Serial are stateless (serial unsupported on Android).
        // Keyboard-interactive challenges (2FA codes) reach the UI through this controller; shared by
        // every transport that authenticates, so a tunnel or container probe prompts like a session.
        val keyboardInteractive = KeyboardInteractivePromptController().also { this.keyboardInteractive = it }
        // File-backed credentials (key/certificate kept outside the vault) are read at connect time:
        // `content://` refs go through the Storage Access Framework, plain paths through okio. Shared
        // by every transport that authenticates, and carrying the inspector so an expired certificate
        // is refused here, with its date, rather than as "server rejected the credentials".
        val certificateInspector = SshjCertificateInspector()
        val secretFiles = AndroidSecretFileReader(
            applicationContext,
            OkioSecretFileReader(FileSystem.SYSTEM, homeDir = null),
        )
        val keyFileResolver = KeyFileResolver(files = secretFiles, inspector = certificateInspector)
        // Certificate authorities trusted to vouch for host keys (@cert-authority); see the desktop
        // graph for the wrapping rule.
        val trustedCaStore = VaultTrustedCaStore(vault)
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
        val knownHosts = KnownHostsController(knownHostsStore, mismatchStore) { Instant.now().toString() }
        val trustedCas = TrustedCaController(
            trustedCaStore,
            SshjCaKeyParser(),
            newId = { UUID.randomUUID().toString() },
            now = { Instant.now().toString() },
        )
        // Host profiles are HOST records in the vault; tree order lives in a layout record. The vault
        // is locked at startup (list is empty); the controller reloads on unlock via reload().
        val hostStore = VaultHostStore(vault, trash = trash)
        val hosts = HostManagerController(hostStore) { UUID.randomUUID().toString() }
        // Biometrics: key in AndroidKeyStore, prompt hosted by this Activity. Weak reference so the
        // store doesn't hold the Activity and returns null instead of a destroyed instance after recreate.
        val activityRef = WeakReference(this)
        val biometrics = VaultBiometrics(
            vault = vault,
            keyStore = AndroidBiometricKeyStore(applicationContext) { activityRef.get() },
            artifacts = FileBioArtifactStore(dir.resolve("vault.bio").absolutePath.toPath(), FileSystem.SYSTEM),
            deviceId = deviceId(dir),
            // Verdict for devices whose keystore never authorizes an auth-bound key (#23): persisted so
            // the settings row explains itself instead of offering a setup that cannot work.
            support = FileBiometricSupportStore(
                dir.resolve("vault.bio.unsupported").absolutePath.toPath(),
                FileSystem.SYSTEM,
                deviceId(dir),
            ),
        )
        // Read-only verifier, so neither of the two transports below can establish trust in a host
        // key — only a real session (TOFU) does. They differ on a host with no entry, because the
        // difference is whether anyone is waiting for the answer: the connection form is driven by
        // the user and names a host that is usually not saved yet, while activating a tunnel opens a
        // forward with no terminal and no prompt and must not settle a host's identity on its own.
        val probeTransport = SshjTransport(
            HostCertificateVerifier(
                trustedCaStore,
                ReadOnlyHostKeyVerifier(knownHostsStore, UnknownHost.Accept),
            ) { Instant.now().epochSecond },
            keyFiles = keyFileResolver,
            keyboardInteractiveResponder = keyboardInteractive.responder,
        )
        val tunnelTransport = SshjTransport(
            HostCertificateVerifier(
                trustedCaStore,
                ReadOnlyHostKeyVerifier(knownHostsStore, UnknownHost.Refuse),
            ) { Instant.now().epochSecond },
            keyFiles = keyFileResolver,
            keyboardInteractiveResponder = keyboardInteractive.responder,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { tunnelScope = it }
        val tunnels = TunnelManager(
            store = VaultTunnelStore(vault, trash),
            transport = tunnelTransport,
            // useForConnect (desktop parity): a tunnel authenticates with the secret on every start.
            resolve = { hostId -> resolveTunnelHost(hostId, findHost = hosts::find, findCredential = credentials::useForConnect) },
            scope = scope,
            scanTransport = probeTransport,
        ) { UUID.randomUUID().toString() }
        // Saved command snippets: SNIPPET records in the vault (commands may contain inline
        // credentials, hence shared encryption and E2E sync). Run into the terminal.
        val snippets = SnippetManager(VaultSnippetStore(vault, trash)) { UUID.randomUUID().toString() }
        // Runbooks: RUNBOOK records in the same vault. The runner is app-scoped so a procedure
        // survives leaving the terminal screen; the vault gate ends any run on lock.
        val runbooks = app.skerry.ui.runbook.RunbookManager(
            app.skerry.shared.runbook.VaultRunbookStore(vault, trash),
        ) { UUID.randomUUID().toString() }
        // History of past runs: RUNBOOK_RUN records next to the runbooks themselves, capped per
        // runbook. Outcomes and timings only — never a command line or its output.
        val runbookHistory = app.skerry.shared.runbook.VaultRunbookRunStore(vault)
        val runbookRunner = app.skerry.ui.runbook.RunbookRunner(
            scope = scope,
            newId = { UUID.randomUUID().toString() },
            onFinished = runbookHistory::record,
        )
        // Self-hosted sync: coordinator ties together the network client (Ktor+SRP), crypto, and vault.
        // Server binding is persisted in sync.json (non-secret: URL/accountId/deviceId); tokens and
        // password are not stored (re-auth via master password). deviceId is stable across records.
        // Sync cursor persists in sync-cursor.json (incremental pull after restart).
        // The teams coordinator is created after sync (it needs the session), but onSynced must call
        // it: the team key arrives via a TEAM record over the regular account sync. Late binding via var.
        var teamsForSync: app.skerry.ui.teams.TeamsCoordinator? = null
        val sync = SyncCoordinator(
            clientFactory = { url -> KtorSyncClient(url) },
            crypto = crypto,
            vault = vault,
            configStore = AndroidSyncConfigStore(File(dir, "sync.json")),
            // Reactivation rebuilds this device still owes — beside the link, not on it: a disconnect or a
            // connect to another server must not take a debt the reconcile never paid (issue #170).
            debtStore = AndroidReconcileDebtStore(File(dir, "sync-reconcile")),
            // Persistent delta-sync cursor: survives restart, otherwise every start would be a full re-pull since 0.
            syncState = FileSyncStateStore(File(dir, "sync-cursor.json").toPath()),
            deviceIdProvider = { deviceId(dir) },
            deviceName = android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Skerry Android",
            // On sign-in the account's dataKey changes; biometrics wrapped under the old key would
            // unlock to the wrong key (synced records would fail to decrypt). Disable it here; the user
            // re-enables it under the new key. Return whether biometrics was enabled, so the coordinator
            // can prompt the UI to re-register it.
            onDataKeyAdopted = {
                val wasEnabled = biometrics.isEnabled()
                biometrics.disable()
                wasEnabled
            },
            // Sync pulled records directly into the vault; reload managers on the main thread so
            // synced data appears without requiring a re-visit.
            onSynced = {
                lifecycleScope.launch(Dispatchers.Main) {
                    hosts.reload(); snippets.reload(); runbooks.reload(); tunnels.reload(); knownHosts.refresh()
                    // Keychain secrets are CREDENTIAL records too: without this a key pulled by live
                    // sync shows up only after the next lock/unlock cycle re-enters MobileChrome.
                    credentials.reload()
                }
                teamsForSync?.onAccountSynced()
            },
        )
        // Teams (zero-knowledge record sharing between accounts): coordinator on top of the same sync
        // session, per-team vaults in filesDir/teams (dataKey = teamKey from the account vault's TEAM
        // record), team-sync cursors in their own file.
        val teams = app.skerry.ui.teams.TeamsCoordinator(
            live = { sync.currentTeamLink() },
            vault = vault,
            crypto = crypto,
            teamVaults = app.skerry.shared.team.TeamVaults(
                dir = dir.resolve("teams").absolutePath.toPath(),
                crypto = IonspinVaultCrypto(),
                deviceId = deviceId(dir),
                fileSystem = FileSystem.SYSTEM,
                // File(...).toPath() (API 26) instead of Path.of (API 34) — compatible with minSdk 26.
                harden = { app.skerry.shared.io.PrivateConfig.harden(java.io.File(it.toString()).toPath()) },
                now = { Instant.now().toString() },
            ),
            teamState = FileSyncStateStore(File(dir, "team-cursor.json").toPath()),
            newId = { UUID.randomUUID().toString() },
            onTeamsChanged = {
                lifecycleScope.launch(Dispatchers.Main) {
                    hosts.reload(); snippets.reload(); runbooks.reload(); tunnels.reload()
                }
            },
        )
        teamsForSync = teams
        // Session sharing (relay on the sync server): the host side and the directory of the teams'
        // live sessions. Desktop parity — same controllers over the same sync session and team keys.
        val sessionShare = app.skerry.ui.share.SessionShareController(
            liveLink = { sync.currentShareLink() },
            teamKey = { teamId -> teams.teamKey(teamId) },
            crypto = IonspinVaultCrypto(),
            newShareId = { UUID.randomUUID().toString().lowercase() },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        val sharedSessions = app.skerry.ui.share.SharedSessionsController(
            liveLink = { sync.currentShareLink() },
            teams = { teams.teams.value.filter { it.hasKey }.map { t -> t.id to t.name } },
            teamKey = { teamId -> teams.teamKey(teamId) },
            crypto = IonspinVaultCrypto(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        teams.onSharesChanged = { sharedSessions.refresh() }
        // A missing team key (dropped by an older client's delta sync) is only fixed by a full re-pull.
        teams.onKeyMissing = { sync.recoverFullPull() }
        sync.onTeamSignal = teams::onSignal
        // Manager reload and sync resume on unlock (the coordinator manages its own scope): the live
        // sync paused by the lock comes back, a cold start restores the keep-connected session instead.
        onVaultUnlocked = {
            hosts.reload()
            snippets.reload()
            runbooks.reload()
            tunnels.reload()
            // Tunnels flagged for autostart come up here and only here: the other reload sites run
            // on every synced change, and raising there would fight the user's own toggles.
            tunnels.startAutostart()
            knownHosts.refresh()
            // Trash retention is applied on unlock too (desktop parity): waiting for the user to
            // open the Trash screen would keep an expired secret in the vault indefinitely.
            trash.purgeExpired()
            sync.resumeAfterUnlock()
        }
        // Clears data outside the vault on reset. The vault file is already wiped and locked, so
        // credentials aren't touched here (secrets reload when a new vault is created). Host
        // profiles/snippets/tunnels are vault records, already wiped by Vault.reset(); this only
        // clears non-vault data and reflects the now-empty vault in the managers.
        onVaultReset = { resetScope ->
            tunnels.closeAll()
            // The process-scoped keep-alive sessions belong to the wiped vault: tear them down and
            // drop the graph so a fresh one is built after the reset (sessions carry credentials
            // that are now meaningless).
            keepAliveSessions?.disconnectAll()
            keepAliveSessions = null
            // Team keys lived in the wiped vault; team vaults can no longer be opened, so lock their in-memory trace.
            teams.lock()
            // Reset wiped the dataKey, so the biometric artifact (`vault.bio`) and the sealed sync
            // refresh token are wrapped under a dead key. Disable biometrics and disconnect sync so the
            // fingerprint doesn't point at a nonexistent key and settings don't show "Linked" with no
            // way to sign in.
            biometrics.disable()
            sync.disconnect()
            // The security event log belongs to the wiped vault; always clear it on reset.
            securityLog.clear()
            // The usage trail names secrets the reset erased — it goes with them.
            credentialUsage.clear()
            // Hosts/groups are wiped with the vault on any reset; clear their local UI trace
            // (collapsed state) too, or stale group names would remain visible.
            writeCollapsedGroups(dir, emptySet())
            // Factory reset: additionally clears trusted keys (non-vault) and terminal settings.
            if (resetScope == ResetScope.Everything) {
                knownHosts.mismatches.toList().forEach { knownHosts.reject(it) }
                knownHosts.entries.toList().forEach { knownHosts.forget(it) }
                writeTerminalFont(dir, TerminalFont.DEFAULT)
                writeTerminalFontSize(dir, DEFAULT_TERMINAL_FONT_SIZE)
                writeTerminalScrollback(dir, DEFAULT_TERMINAL_SCROLLBACK)
                writeTerminalCursorStyle(dir, TerminalCursorStyle.DEFAULT)
                writeTerminalTheme(dir, TerminalThemes.DEFAULT)
                writeThemeMode(dir, ThemeMode.DEFAULT)
                writeClipboardWrite(dir, false)
                writeReportTeamSessions(dir, true)
                writeProdWarnings(dir, false)
                writeHideSystemBars(dir, false)
                writeUiLanguage(dir, UiLanguage.DEFAULT)
                writeAutoLock(dir, AutoLockDuration.DEFAULT)
            }
            hosts.reload()
            snippets.reload()
            runbooks.reload()
            tunnels.reload()
            // The vault is locked after reset, so this clears the in-memory secret list (all() is
            // empty on a locked vault) — desktop parity; rereads on the next vault create + unlock.
            credentials.reload()
        }
        // Local AI: GGUF models in the private filesDir/models (allowBackup=false so gigabyte-sized
        // weights don't break cloud backup); inference via Llamatik/llama.cpp arm64 in the ":llm"
        // process (LlmHostService) so a native abort doesn't take the app down. ctx 2048 keeps the
        // KV cache within a few hundred MiB on phone-class RAM.
        val localModelStore = LocalModelStore(FileSystem.SYSTEM, dir.resolve("models").absolutePath.toPath())
        val localAi = LocalAiDeps(
            store = localModelStore,
            downloader = ModelDownloader(FileSystem.SYSTEM, localModelStore),
            runtime = IsolatedLlmRuntime(ServiceLlmHostLauncher(this, contextLength = 2048)),
        )
        return AppDependencies(
            transport = transport,
            probeTransport = probeTransport,
            vncTransport = app.skerry.shared.vnc.VncTcpTransport(),
            rdpTransport = app.skerry.shared.rdp.RdpTcpTransport(
                app.skerry.shared.rdp.FileRdpCertificateStore(
                    // The app's private files directory is already 0700 to other UIDs, so the
                    // store needs no extra hardening here (unlike the desktop's config directory).
                    okio.Path.Companion.run { filesDir.resolve("rdp_known_certs").absolutePath.toPath() },
                ),
                // Playback for a profile that asks for the session's sound (MS-RDPEA).
                audioPlayers = app.skerry.shared.audio.AndroidAudioPlayers(applicationContext),
                // H.264 in the graphics pipeline, through the platform decoder.
                h264Decoders = app.skerry.shared.rdp.egfx.MediaCodecH264Decoders(),
            ),
            // Speaker, headset, Bluetooth — what the RDP profile form offers to play on.
            audioOutputs = app.skerry.shared.audio.AndroidAudioOutputs(applicationContext),
            hosts = hosts,
            vault = vault,
            credentials = credentials,
            knownHosts = knownHosts,
            trustedCas = trustedCas,
            // SSH key inspector/generator (BouncyCastle, shared JVM source set): fingerprints/generation in the Vault tab.
            keyGenerator = BouncyCastleSshKeyGenerator(),
            // SSH certificate inspector (sshj): Vault → Certificates parses *-cert.pub.
            certificateInspector = certificateInspector,
            secretFiles = secretFiles,
            biometrics = biometrics,
            tunnels = tunnels,
            snippets = snippets,
            runbooks = runbooks,
            runbookRunner = runbookRunner,
            runbookHistory = runbookHistory,
            sync = sync,
            teams = teams,
            sessionShare = sessionShare,
            sharedSessions = sharedSessions,
            securityLog = securityLog,
            localAi = localAi,
        )
    }

    /** Stable device identifier for vault records (provenance + sync LWW). */
    private fun deviceId(dir: File): String {
        val file = File(dir, "device_id")
        if (file.exists()) return file.readText().trim()
        val id = UUID.randomUUID().toString()
        file.writeText(id)
        return id
    }
}
