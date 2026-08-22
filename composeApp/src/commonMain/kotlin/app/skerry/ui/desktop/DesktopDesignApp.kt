package app.skerry.ui.desktop

import app.skerry.ui.app.DesktopSettingsState
import app.skerry.ui.remote.RemoteDesktopController
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.key
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.vault.SecretFileReader
import app.skerry.shared.vault.SshCertificateInspector
import app.skerry.shared.vault.SshKeyGenerator
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.SecurityLog
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.shared.terminal.VaultTerminalHistoryStore
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.app.CustomGroup
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.known.TrustedCaController
import app.skerry.ui.session.SessionsController
import app.skerry.ui.runbook.RunbookManager
import app.skerry.ui.runbook.RunbookRunner
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.terminal.LocalTerminalAppearance
import app.skerry.ui.terminal.LocalTerminalHighlight
import app.skerry.ui.terminal.TerminalHighlight
import app.skerry.ui.terminal.LocalTerminalTheme
import app.skerry.ui.terminal.TerminalAppearance
import app.skerry.ui.theme.ThemeMode
import app.skerry.ui.theme.systemInDarkTheme
import app.skerry.ui.theme.terminalThemeId
import app.skerry.ui.terminal.TerminalThemes
import app.skerry.ui.terminal.TerminalSessionPrefs
import app.skerry.ui.tunnel.TunnelManager
import app.skerry.ui.vault.ResetScope
import app.skerry.ui.vault.VaultGate
import app.skerry.ui.vault.tearDownForLock
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.vault.DesktopCorruptedScreen
import app.skerry.ui.vault.DesktopCreateScreen
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.vault.DesktopResetScreen
import app.skerry.ui.vault.DesktopUnlockScreen
import app.skerry.ui.app.FeatureFlags
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.LocalFeatures
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalKnownHosts
import app.skerry.ui.app.LocalTrustedCas
import app.skerry.ui.app.LocalSecurityLog
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSftpPrefs
import app.skerry.shared.runbook.VaultRunbookRunStore
import app.skerry.ui.app.LocalRunbookHistory
import app.skerry.ui.app.LocalRunbookRunner
import app.skerry.ui.app.LocalRunbooks
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.LocalTerminalHistory
import app.skerry.ui.app.LocalSecretFileReader
import app.skerry.ui.app.LocalSshCertificateInspector
import app.skerry.ui.app.LocalSshKeyGenerator
import app.skerry.ui.app.LocalSync
import app.skerry.ui.app.LocalSessionShare
import app.skerry.ui.app.LocalSharedSessions
import app.skerry.ui.app.LocalTeams
import app.skerry.ui.app.LocalTestTransport
import app.skerry.ui.app.LocalTunnels
import app.skerry.ui.app.LocalUpdates
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.app.SftpPrefs
import app.skerry.ui.sync.SyncOnboardingScreen
import app.skerry.ui.design.rememberMaterialSymbols
import app.skerry.ui.design.rememberMono
import app.skerry.ui.design.rememberUiFont

/**
 * Root of the desktop app. Supplies fonts
 * via [LocalFonts], holds [DesktopDesignState] and assembles the layout: titlebar (44dp) →
 * rail (62dp) + viewport → statusbar (26dp). On top — lock / new-connection / settings overlays.
 *
 * The live layer is wired in via [vault]: if passed, the whole chrome is gated behind the master
 * password ([app.skerry.ui.vault.VaultGate]) on top of [app.skerry.ui.vault.VaultGateController] —
 * the styled create/unlock screens are drawn ([DesktopCreateScreen]/[DesktopUnlockScreen]),
 * and the "Unlocked" chip in the titlebar actually locks the vault. Without [vault] (the
 * screenshot/preview path) data stays mock-static ([DesktopMockData]) and locking is a stub
 * ([DesktopDesignState]).
 */
@Composable
fun DesktopDesignApp(
    // Keyboard-interactive prompts (2FA codes a server asks for mid-connect). null on the mock path:
    // nothing is connecting, so nothing can ask.
    keyboardInteractive: app.skerry.ui.connection.KeyboardInteractivePromptController? = null,
    hostTrust: app.skerry.ui.trust.HostTrustPromptController? = null,
    /**
     * Persisted user preferences (Settings). The platform builds this with its own read/write
     * callbacks; the default (no-op callbacks, stock values) is the mock/preview path.
     */
    settings: DesktopSettingsState = DesktopSettingsState(),
    // Collapsed host groups — also persisted externally (desktop main): starting set + write callback.
    initialCollapsedGroups: Set<String> = emptySet(),
    onCollapsedGroupsChange: (Set<String>) -> Unit = {},
    // Recent connections (RECENT section) — also persisted externally (desktop main): starting order + write callback.
    initialRecentHostIds: List<String> = emptyList(),
    onRecentHostIdsChange: (List<String>) -> Unit = {},
    // User-defined (empty) host groups — also persisted externally (desktop main): starting list + write callback.
    initialCustomGroups: List<CustomGroup> = emptyList(),
    onCustomGroupsChange: (List<CustomGroup>) -> Unit = {},
    // Show hidden files in SFTP (Ctrl+H) — persisted externally (desktop main): starting value + write callback.
    initialSftpShowHidden: Boolean = true,
    onSftpShowHiddenChange: (Boolean) -> Unit = {},
    // SFTP listing columns (the header's Columns popup) — persisted the same way.
    initialSftpShowModified: Boolean = true,
    onSftpShowModifiedChange: (Boolean) -> Unit = {},
    initialSftpShowPermissions: Boolean = false,
    onSftpShowPermissionsChange: (Boolean) -> Unit = {},
    state: DesktopDesignState = remember {
        DesktopDesignState(
            settings = settings,
            initialCollapsedGroups = initialCollapsedGroups,
            onCollapsedGroupsChange = onCollapsedGroupsChange,
            initialRecentHostIds = initialRecentHostIds,
            onRecentHostIdsChange = onRecentHostIdsChange,
            initialCustomGroups = initialCustomGroups,
            onCustomGroupsChange = onCustomGroupsChange,
        )
    },
    vault: Vault? = null,
    biometrics: VaultBiometrics? = null,
    // Local security event log (Settings → Security). `null` — mock/preview: the section draws an empty
    // log and a neutral password caption.
    securityLog: SecurityLog? = null,
    hosts: HostManagerController? = null,
    transport: SshTransport? = null,
    // VNC (RFB) transport for remote-desktop tabs — separate from the SSH-shaped [transport] because
    // VNC is a framebuffer protocol (see VncTransport). `null` in mock/preview (no VNC tabs).
    vncTransport: app.skerry.shared.vnc.VncTransport? = null,
    rdpTransport: app.skerry.shared.rdp.RdpTransport? = null,
    // Playback devices offered by the RDP form when the profile asks for the session's sound.
    // `null` in mock/preview: the form keeps the toggle and plays through the system default.
    audioOutputs: app.skerry.shared.audio.AudioOutputs? = null,
    // Transport for the one-off "Test connection": separate from [transport] (live sessions), because a
    // probe must not add the host key to known_hosts (read-only verifier). `null` — use [transport]
    // (offscreen render/preview, where there's no enroll side effect). See main.kt.
    testTransport: SshTransport? = null,
    credentials: CredentialManagerController? = null,
    sessions: SessionsController? = null,
    knownHosts: KnownHostsController? = null,
    trustedCas: TrustedCaController? = null,
    keyGenerator: SshKeyGenerator? = null,
    certificateInspector: SshCertificateInspector? = null,
    secretFiles: SecretFileReader? = null,
    tunnels: TunnelManager? = null,
    snippets: SnippetManager? = null,
    // Runbook library + the one in-flight run. `null` on the mock path (no vault behind them).
    runbooks: RunbookManager? = null,
    runbookRunner: RunbookRunner? = null,
    runbookHistory: VaultRunbookRunStore? = null,
    // Self-hosted sync coordinator. `null` — sync not connected on the platform / mock path: the Sync
    // settings section draws a static mock, the onboarding modal isn't shown.
    sync: SyncCoordinator? = null,
    // Teams coordinator (cross-account sharing over sync). `null` — Teams screen in mock mode.
    teams: app.skerry.ui.teams.TeamsCoordinator? = null,
    // Session sharing over the sync relay: the host side (one share at a time) and the directory of
    // what the account's teams are sharing right now. Both null on the mock path / without sync.
    sessionShare: app.skerry.ui.share.SessionShareController? = null,
    sharedSessions: app.skerry.ui.share.SharedSessionsController? = null,
    // AI assistant controller (BYOK, external OpenAI-compatible provider). `null` — AI not connected: the
    // "AI" settings tab draws a static mock. Supplied behind the vault gate (the key is stored in the vault).
    ai: app.skerry.ui.ai.AiAssistantController? = null,
    // Update notice (GitHub Releases check + the About toggle). `null` — mock/preview: no notice,
    // the About section hides the toggle.
    updates: app.skerry.ui.update.UpdateNoticeController? = null,
    features: FeatureFlags = FeatureFlags(),
    // Called once after vault unlock, before list reload — reloads managers from decrypted records
    // and restores the sync session (supplied by desktop `main`). No-op in mock/preview.
    onVaultUnlocked: () -> Unit = {},
    // Empty host folders sync in the vault layout record: at startup the vault is locked, so after
    // unlock (and [onVaultUnlocked]) reread them into state from here. No-op in mock/preview.
    customGroupsProvider: () -> List<CustomGroup> = { emptyList() },
    // External cleanup on vault reset (hosts/known_hosts/settings per [ResetScope]). Called after the
    // vault file is erased; the real implementation is supplied by desktop `main`. No-op in mock/preview.
    onVaultReset: (ResetScope) -> Unit = {},
    // Custom chrome of the undecorated desktop window (drag + minimize/maximize/close). `null` —
    // a decorated window (offscreen render/preview): no window buttons are drawn.
    windowChrome: WindowChrome? = null,
) {
    val fonts = DesignFonts(
        ui = rememberUiFont(),
        mono = rememberMono(),
        symbols = rememberMaterialSymbols(),
    )
    // SFTP show-hidden setting: kept in state so Ctrl+H updates the UI instantly, and written out
    // (persisted) on every change. remember is required (like terminalAppearance below):
    // LocalSftpPrefs is staticCompositionLocalOf, and a new instance on every recomposition would
    // force a full rebuild of the consumer subtree. The persist callback goes through
    // rememberUpdatedState so the lambda inside remember always calls the fresh onSftpShowHiddenChange.
    var sftpShowHidden by remember { mutableStateOf(initialSftpShowHidden) }
    var sftpShowModified by remember { mutableStateOf(initialSftpShowModified) }
    var sftpShowPermissions by remember { mutableStateOf(initialSftpShowPermissions) }
    val sftpShowHiddenWriter = rememberUpdatedState(onSftpShowHiddenChange)
    val sftpShowModifiedWriter = rememberUpdatedState(onSftpShowModifiedChange)
    val sftpShowPermissionsWriter = rememberUpdatedState(onSftpShowPermissionsChange)
    val sftpPrefs = remember(sftpShowHidden, sftpShowModified, sftpShowPermissions) {
        SftpPrefs(
            showHidden = sftpShowHidden,
            setShowHidden = { value ->
                sftpShowHidden = value
                sftpShowHiddenWriter.value(value)
            },
            showModified = sftpShowModified,
            setShowModified = { value ->
                sftpShowModified = value
                sftpShowModifiedWriter.value(value)
            },
            showPermissions = sftpShowPermissions,
            setShowPermissions = { value ->
                sftpShowPermissions = value
                sftpShowPermissionsWriter.value(value)
            },
        )
    }
    // Session manager: either supplied from outside (offscreen render with a fake transport), or
    // built from the live transport — one shell per tab, like in [app.skerry.ui.mobile.MobileApp].
    // We close our own graph on dispose; an externally-owned one belongs to the caller and is left alone.
    val scope = rememberCoroutineScope()
    // Per-host terminal command history persistence (autocomplete + the command palette) on top of
    // the encrypted vault. Hoisted out of the sessions factory: the palette reads it directly.
    val termHistory = remember(vault) { vault?.let { VaultTerminalHistoryStore(it) } }
    val liveSessions = sessions ?: remember(transport, vncTransport, rdpTransport, scope, vault, teams) {
        transport?.let { t ->
            var counter = 0
            SessionsController(
                newId = { "sess-${counter++}" },
                vncControllerFactory = vncTransport?.let { { app.skerry.ui.remote.RemoteDesktopController(scope) } },
                openVncSession = vncTransport?.let { vt ->
                    { target, auth -> app.skerry.shared.vnc.VncRemoteDesktop(vt.connect(target, auth)) }
                },
                openRdpSession = rdpTransport?.let { rt ->
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
                                    keyboardLayout = request.keyboardLayout,
                                    graphicsPipeline = request.graphicsPipeline,
                                    remoteFx = request.remoteFx,
                                    h264 = request.h264,
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
                // Session half of the Teams activity feed. Both privacy rules (the setting, and
                // "never a host of our own") live in the coordinator, not here.
                onHostSessionOpened = { hostId -> teams?.reportSessionOpened(hostId) },
                controllerFactory = {
                    ConnectionController(
                        t, scope, history = termHistory,
                        // Read terminal settings at connect time — new sessions pick up the current
                        // scrollback/cursor choice, already-open ones keep their emulator's.
                        terminalPrefs = {
                            TerminalSessionPrefs(
                                state.settings.terminalScrollback,
                                state.settings.terminalCursorStyle,
                                clipboardWriteEnabled = state.settings.allowServerClipboardWrite,
                            )
                        },
                    )
                },
            )
        }
    }
    // Ownership is fixed as a snapshot at composition time: an externally-supplied session manager
    // belongs to the caller (don't tear it down), a locally built one is closed on dispose.
    val ownsSessions = sessions == null
    DisposableEffect(liveSessions) {
        onDispose { if (ownsSessions) liveSessions?.disconnectAll() }
    }
    // A cursor-style change in settings applies to ALREADY open sessions live (new ones pick it up at
    // connect via terminalPrefs). Pushed into every pane of every tab; the command goes
    // through the emulator's queue, so no race. Detached/empty tabs are simply skipped.
    val cursorStyle = state.settings.terminalCursorStyle
    LaunchedEffect(cursorStyle, liveSessions) {
        val manager = liveSessions ?: return@LaunchedEffect
        manager.allSessions.forEach { it.liveTerminal?.applyCursorStyle(cursorStyle.shape, cursorStyle.blink) }
    }
    // A scrollback-buffer change in settings likewise applies to ALREADY open sessions live: shrinking
    // trims the extra old history, growing keeps new lines around longer. New sessions pick up the
    // value at connect via terminalPrefs.
    val scrollbackLines = TerminalSessionPrefs(scrollback = state.settings.terminalScrollback).effectiveScrollback
    LaunchedEffect(scrollbackLines, liveSessions) {
        val manager = liveSessions ?: return@LaunchedEffect
        manager.allSessions.forEach { it.liveTerminal?.applyScrollback(scrollbackLines) }
    }
    // The Teams session-report gate reads the live setting: it lives in this screen's state, while
    // the coordinator is built at startup, so it is handed a getter rather than a value.
    LaunchedEffect(teams, state) { teams?.reportSessionsEnabled = { state.settings.reportTeamSessions } }
    // Toggling the OSC 52 clipboard-write gate applies to ALREADY open sessions live: turning it off
    // stops honoring server clipboard writes immediately, turning it on lets them through. New
    // sessions pick up the value at connect via terminalPrefs.
    val allowClipboardWrite = state.settings.allowServerClipboardWrite
    LaunchedEffect(allowClipboardWrite, liveSessions) {
        val manager = liveSessions ?: return@LaunchedEffect
        manager.allSessions.forEach { it.liveTerminal?.applyClipboardWriteEnabled(allowClipboardWrite) }
    }
    // Memoized: LocalTerminalAppearance is staticCompositionLocalOf (reference comparison), and
    // DesktopDesignApp recomposes on tab/session switches and vault events. Without remember a new
    // instance on every recomposition would force a full rebuild of the consumer subtree (the whole
    // terminal Canvas), even when font/size hadn't changed.
    val terminalAppearance = remember(state.settings.terminalFont, state.settings.terminalFontSize, state.settings.terminalLineHeight, state.settings.terminalLetterSpacing) {
        TerminalAppearance(state.settings.terminalFont, state.settings.terminalFontSize, state.settings.terminalLineHeight, state.settings.terminalLetterSpacing)
    }
    // The terminal AI's reply language = UI language: the provider reads the applied locale tag
    // ([app.skerry.ui.i18n.LocalAppLocale]) and is reset on language change (SideEffect reruns when
    // the tag changes), so INFO/ASK go out in the current language without recreating the controller.
    val aiLocaleTag = app.skerry.ui.i18n.LocalAppLocale.current
    androidx.compose.runtime.SideEffect {
        ai?.uiLanguageProvider = { app.skerry.ui.i18n.aiResponseLanguageName(aiLocaleTag) }
    }
    // Unified theming: unless the user opted into a separate terminal theme, the terminal follows
    // the app theme's twin ([ThemeMode.terminalThemeId]); SYSTEM tracks the OS side live.
    val termSystemDark = systemInDarkTheme(enabled = !state.settings.customTerminalTheme && state.settings.themeMode == ThemeMode.SYSTEM)
    val effectiveTerminalTheme =
        if (state.settings.customTerminalTheme) state.settings.terminalTheme
        else TerminalThemes.fromId(state.settings.themeMode.terminalThemeId(termSystemDark))
    CompositionLocalProvider(
        app.skerry.ui.app.LocalKeyboardInteractive provides keyboardInteractive,
        app.skerry.ui.app.LocalHostTrust provides hostTrust,
        LocalFonts provides fonts,
        LocalHosts provides hosts,
        LocalSessions provides liveSessions,
        LocalKnownHosts provides knownHosts,
        LocalTrustedCas provides trustedCas,
        LocalSshKeyGenerator provides keyGenerator,
        LocalSshCertificateInspector provides certificateInspector,
        LocalSecretFileReader provides secretFiles,
        app.skerry.ui.app.LocalAudioOutputs provides audioOutputs,
        LocalCredentials provides credentials,
        LocalTestTransport provides (testTransport ?: transport),
        LocalTunnels provides tunnels,
        LocalSnippets provides snippets,
        LocalRunbooks provides runbooks,
        LocalRunbookRunner provides runbookRunner,
        LocalRunbookHistory provides runbookHistory,
        LocalTerminalHistory provides termHistory,
        LocalFeatures provides features,
        LocalSftpPrefs provides sftpPrefs,
        // Terminal appearance from settings: font + size, read by [app.skerry.ui.terminal.TerminalScreen].
        LocalTerminalAppearance provides terminalAppearance,
        // Client-side syntax highlighting (Settings → Terminal), read by TerminalScreen's draw layer.
        LocalTerminalHighlight provides TerminalHighlight(
            commandLine = state.settings.highlightCommandLine,
            output = state.settings.highlightOutput,
        ),
        // Terminal color theme: the app theme's twin, or the separately-picked one (Appearance → cards).
        LocalTerminalTheme provides effectiveTerminalTheme,
        // The open vault + biometrics behind the gate — needed for re-authentication before copying
        // a password from the keychain (desktop has no biometrics, so the path reduces to the master password).
        LocalVault provides vault,
        LocalVaultBiometrics provides biometrics,
        LocalSecurityLog provides securityLog,
        LocalSync provides sync,
        LocalTeams provides teams,
        LocalSessionShare provides sessionShare,
        LocalSharedSessions provides sharedSessions,
        LocalAi provides ai,
        LocalUpdates provides updates,
    ) {
        if (vault != null) {
            VaultGate(
                vault = vault,
                biometrics = biometrics,
                securityLog = securityLog,
                // Idle auto-lock threshold from settings: changing it in the UI recomposes VaultGate
                // and restarts the idle timer; Never (idleMs == null) turns it off.
                autoLockIdleMs = state.settings.autoLock.idleMs,
                // Unattended work the user started defers the idle lock — see [IdleLockPolicy]. Read
                // on every tick of the idle timer, so both getters stay O(open sessions).
                workInFlight = { liveSessions?.writeInFlight == true || runbookRunner?.stepInFlight == true },
                // Runs on EVERY lock, including the two automatic ones that bypass the lock action.
                // liveSessions, not the `sessions` parameter: the desktop entry point passes none and
                // lets this composable build one, so the parameter is null in the shipped app and the
                // reconnect credentials survived every automatic lock.
                onBeforeLock = {
                    tearDownForLock(
                        tunnels, liveSessions, sync, snippets, runbookRunner, keyboardInteractive, hostTrust,
                        // The modal's own flag lives here, above the gate, so it outlives the lock — the
                        // question inside it does not.
                        closeSyncSetup = state::closeSyncSetup,
                    )
                },
                onReset = onVaultReset,
                // onPairingComplete != null (sync is present) — the create screen offers "I have a code":
                // the coordinator creates the vault under the chosen password itself and accepts the account key.
                // Gate screens are full-window (no titlebar), so each is wrapped in
                // [LockWindowChrome]: the undecorated window stays movable and closable while locked.
                createForm = { error, onCreate, onPairingComplete ->
                    LockWindowChrome(windowChrome) { DesktopCreateScreen(error, onCreate, sync, onPairingComplete) }
                },
                unlockForm = { error, canBio, onUnlock, onBio, onForgot ->
                    LockWindowChrome(windowChrome) { DesktopUnlockScreen(error, canBio, onUnlock, onBio, onForgot) }
                },
                corruptedForm = { onReset -> LockWindowChrome(windowChrome) { DesktopCorruptedScreen(onReset) } },
                resetForm = { onConfirm, onCancel -> LockWindowChrome(windowChrome) { DesktopResetScreen(onConfirm, onCancel) } },
                // Sync onboarding step (parity with mobile): connect sync and pull data right after
                // creating the vault. Only if sync was wired into the graph.
                offerSyncForm = sync?.let { s -> { onDone -> LockWindowChrome(windowChrome) { SyncOnboardingScreen(s, onDone) } } },
            ) { onLock -> DesktopChrome(state, onLock, liveSessions, credentials, onVaultUnlocked, customGroupsProvider, windowChrome) }
        } else {
            DesktopChrome(state, onLock = null, sessions = liveSessions, credentials = credentials, onVaultUnlocked = onVaultUnlocked, customGroupsProvider = customGroupsProvider, windowChrome = windowChrome)
        }
    }
}
