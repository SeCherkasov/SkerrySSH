package app.skerry.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshJump
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.vault.SshCertificateInspector
import app.skerry.shared.vault.SshKeyGenerator
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.SecurityLog
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.shared.terminal.TerminalHistoryStore
import app.skerry.shared.terminal.VaultTerminalHistoryStore
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.connection.JumpChainProblem
import app.skerry.ui.connection.JumpChainResolution
import app.skerry.ui.connection.JumpErrorDialog
import app.skerry.ui.connection.jumpRouteLabel
import app.skerry.ui.connection.resolveJumpChain
import app.skerry.ui.forward.humanRate
import app.skerry.shared.ssh.isVnc
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.connection.toTarget
import app.skerry.ui.connection.toVncAuth
import app.skerry.ui.app.GroupDialog
import app.skerry.ui.host.GroupDialog as GroupEditDialog
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.session.BroadcastPanel
import app.skerry.ui.session.SessionView
import app.skerry.ui.session.broadcastTargets
import app.skerry.ui.session.SessionsController
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.snippet.SnippetShortcut
import androidx.compose.runtime.collectAsState
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.sync.SyncIndicatorLevel
import app.skerry.ui.sync.syncIndicatorLocalized
import app.skerry.ui.terminal.CommandPalette
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.CastPlayerOverlay
import app.skerry.ui.terminal.recordingOutcomeMessage
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LETTER_SPACING
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LINE_HEIGHT
import app.skerry.ui.terminal.DEFAULT_TERMINAL_SCROLLBACK
import app.skerry.ui.terminal.LocalTerminalAppearance
import app.skerry.ui.terminal.LocalTerminalTheme
import app.skerry.ui.terminal.TerminalAppearance
import app.skerry.ui.terminal.TerminalCursorStyle
import app.skerry.ui.terminal.TerminalTheme
import app.skerry.ui.terminal.TerminalThemes
import app.skerry.ui.terminal.TerminalSessionPrefs
import app.skerry.ui.i18n.UiLanguage
import app.skerry.ui.terminal.TerminalFont
import app.skerry.ui.tunnel.TunnelManager
import app.skerry.ui.vault.AutoLockDuration
import app.skerry.ui.vault.ResetScope
import app.skerry.ui.vault.VaultGate
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_this_session
import app.skerry.ui.generated.resources.term_ai_dismiss
import app.skerry.ui.generated.resources.term_player_invalid
import app.skerry.ui.generated.resources.term_player_title
import app.skerry.ui.generated.resources.term_record_start
import app.skerry.ui.generated.resources.shell_disconnect_title
import app.skerry.ui.generated.resources.shell_disconnect_message
import app.skerry.ui.generated.resources.shell_disconnect
import app.skerry.ui.generated.resources.shell_close_split_title
import app.skerry.ui.generated.resources.shell_close_split_message
import app.skerry.ui.generated.resources.shell_close_panel
import app.skerry.ui.generated.resources.shell_unlocked
import app.skerry.ui.generated.resources.shell_settings
import app.skerry.ui.generated.resources.shell_status_connected
import app.skerry.ui.generated.resources.shell_status_disconnected
import app.skerry.ui.generated.resources.shell_status_encoding
import app.skerry.ui.generated.resources.shtail_new_tab
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.BrandMark
import app.skerry.ui.design.ConfirmActionDialog
import app.skerry.ui.design.D
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.vault.DesktopCorruptedScreen
import app.skerry.ui.vault.DesktopCreateScreen
import app.skerry.ui.host.DesktopDeleteHostDialog
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.connection.DesktopPasswordDialog
import app.skerry.ui.vault.DesktopResetScreen
import app.skerry.ui.vault.DesktopUnlockScreen
import app.skerry.ui.app.DesktopView
import app.skerry.ui.design.Dot
import app.skerry.ui.app.FeatureFlags
import app.skerry.ui.design.HLine
import app.skerry.ui.design.NoticeDialog
import app.skerry.ui.design.IconBtn
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalConnectHost
import app.skerry.ui.app.LocalConnectSplit
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.LocalFeatures
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalKnownHosts
import app.skerry.ui.app.LocalRunSnippetOnHost
import app.skerry.ui.app.LocalSecurityLog
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSftpPrefs
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.LocalTerminalHistory
import app.skerry.ui.app.LocalSshCertificateInspector
import app.skerry.ui.app.LocalSshKeyGenerator
import app.skerry.ui.app.LocalSync
import app.skerry.ui.app.LocalTeams
import app.skerry.ui.app.LocalTestTransport
import app.skerry.ui.app.LocalTunnels
import app.skerry.ui.app.LocalUpdates
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.vault.LockScreen
import app.skerry.ui.host.NewConnectionModal
import app.skerry.ui.sync.PairingShowDialog
import app.skerry.ui.app.PendingClose
import app.skerry.ui.settings.SettingsPanel
import app.skerry.ui.app.SftpPrefs
import app.skerry.ui.design.Sym
import app.skerry.ui.sync.SyncOnboardingScreen
import app.skerry.ui.sync.SyncSetupDialog
import app.skerry.ui.session.TabDragState
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine
import app.skerry.ui.app.asSessionView
import app.skerry.ui.app.isAppLevel
import app.skerry.ui.i18n.label
import app.skerry.ui.design.rememberMaterialSymbols
import app.skerry.ui.design.rememberMono
import app.skerry.ui.design.rememberSpaceGrotesk
import app.skerry.ui.session.sessionDotColor
import app.skerry.ui.session.tabBoundsAnchor
import app.skerry.ui.app.asDesktopView
import app.skerry.ui.session.draggableTab

/**
 * Root of the desktop app. Supplies fonts
 * via [LocalFonts], holds [DesktopDesignState] and assembles the layout: titlebar (44dp) →
 * rail (62dp) + viewport → statusbar (26dp). On top — lock / new-connection / settings overlays.
 *
 * The live layer is wired in via [vault]: if passed, the whole chrome is gated behind the master
 * password ([app.skerry.ui.vault.VaultGate]) on top of [app.skerry.ui.vault.VaultGateController] —
 * the create/unlock screens are drawn in the mockup style ([DesktopCreateScreen]/[DesktopUnlockScreen]),
 * and the "Unlocked" chip in the titlebar actually locks the vault. Without [vault] (the
 * screenshot/preview path) data stays mock-static ([DesktopMockData]) and locking is a stub
 * ([DesktopDesignState]).
 */
@Composable
fun DesktopDesignApp(
    // Info panel visibility is persisted externally (desktop main): starting value + write callback.
    initialInfoPanel: Boolean = true,
    onInfoPanelChange: (Boolean) -> Unit = {},
    // Collapsed host groups — also persisted externally (desktop main): starting set + write callback.
    initialCollapsedGroups: Set<String> = emptySet(),
    onCollapsedGroupsChange: (Set<String>) -> Unit = {},
    // Recent connections (RECENT section) — also persisted externally (desktop main): starting order + write callback.
    initialRecentHostIds: List<String> = emptyList(),
    onRecentHostIdsChange: (List<String>) -> Unit = {},
    // User-defined (empty) host groups — also persisted externally (desktop main): starting list + write callback.
    initialCustomGroups: List<String> = emptyList(),
    onCustomGroupsChange: (List<String>) -> Unit = {},
    // Show hidden files in SFTP (Ctrl+H) — persisted externally (desktop main): starting value + write callback.
    initialSftpShowHidden: Boolean = true,
    onSftpShowHiddenChange: (Boolean) -> Unit = {},
    // Terminal font and its size (Appearance → Font / Font size) — persisted externally (desktop main).
    initialTerminalFont: TerminalFont = TerminalFont.DEFAULT,
    onTerminalFontChange: (TerminalFont) -> Unit = {},
    initialTerminalFontSize: Int = DEFAULT_TERMINAL_FONT_SIZE,
    onTerminalFontSizeChange: (Int) -> Unit = {},
    // Terminal line height and letter spacing (Appearance) — persisted externally (desktop main).
    initialTerminalLineHeight: Float = DEFAULT_TERMINAL_LINE_HEIGHT,
    onTerminalLineHeightChange: (Float) -> Unit = {},
    initialTerminalLetterSpacing: Float = DEFAULT_TERMINAL_LETTER_SPACING,
    onTerminalLetterSpacingChange: (Float) -> Unit = {},
    // UI language (Appearance → Language) — persisted externally (desktop main): starting value + write callback.
    initialUiLanguage: UiLanguage = UiLanguage.DEFAULT,
    onUiLanguageChange: (UiLanguage) -> Unit = {},
    // Terminal settings (Settings → Terminal): scrollback, cursor style, show OSC title on tabs —
    // persisted externally (desktop main): starting values + write callbacks.
    initialTerminalScrollback: Int = DEFAULT_TERMINAL_SCROLLBACK,
    onTerminalScrollbackChange: (Int) -> Unit = {},
    initialTerminalCursorStyle: TerminalCursorStyle = TerminalCursorStyle.DEFAULT,
    onTerminalCursorStyleChange: (TerminalCursorStyle) -> Unit = {},
    initialShowTerminalTitleOnTabs: Boolean = false,
    onShowTerminalTitleOnTabsChange: (Boolean) -> Unit = {},
    // OSC 52 server clipboard-write gate (Settings → Terminal) — persisted externally (desktop main).
    initialAllowServerClipboardWrite: Boolean = false,
    onAllowServerClipboardWriteChange: (Boolean) -> Unit = {},
    // Terminal color theme (Appearance → theme cards) — persisted externally (desktop main).
    initialTerminalTheme: TerminalTheme = TerminalThemes.DEFAULT,
    onTerminalThemeChange: (TerminalTheme) -> Unit = {},
    // Idle auto-lock threshold (Settings → Security) — persisted externally (desktop main).
    initialAutoLock: AutoLockDuration = AutoLockDuration.DEFAULT,
    onAutoLockChange: (AutoLockDuration) -> Unit = {},
    // Visibility and size of the RECENT section (Settings → Appearance → Interface) — persisted externally (desktop main).
    initialShowRecent: Boolean = true,
    onShowRecentChange: (Boolean) -> Unit = {},
    initialRecentLimit: Int = DesktopDesignState.MAX_RECENT_HOSTS,
    onRecentLimitChange: (Int) -> Unit = {},
    state: DesktopDesignState = remember {
        DesktopDesignState(
            initialInfoPanel, onInfoPanelChange, initialCollapsedGroups, onCollapsedGroupsChange,
            initialRecentHostIds, onRecentHostIdsChange, initialCustomGroups, onCustomGroupsChange,
            initialTerminalFont, onTerminalFontChange, initialTerminalFontSize, onTerminalFontSizeChange,
            initialTerminalLineHeight, onTerminalLineHeightChange, initialTerminalLetterSpacing, onTerminalLetterSpacingChange,
            initialUiLanguage, onUiLanguageChange,
            initialTerminalScrollback, onTerminalScrollbackChange,
            initialTerminalCursorStyle, onTerminalCursorStyleChange,
            initialShowTerminalTitleOnTabs, onShowTerminalTitleOnTabsChange,
            initialAllowServerClipboardWrite, onAllowServerClipboardWriteChange,
            initialTerminalTheme, onTerminalThemeChange,
            initialAutoLock, onAutoLockChange,
            initialShowRecent, onShowRecentChange,
            initialRecentLimit, onRecentLimitChange,
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
    // Transport for the one-off "Test connection": separate from [transport] (live sessions), because a
    // probe must not add the host key to known_hosts (read-only verifier). `null` — use [transport]
    // (offscreen render/preview, where there's no enroll side effect). See main.kt.
    testTransport: SshTransport? = null,
    credentials: CredentialManagerController? = null,
    sessions: SessionsController? = null,
    knownHosts: KnownHostsController? = null,
    keyGenerator: SshKeyGenerator? = null,
    certificateInspector: SshCertificateInspector? = null,
    tunnels: TunnelManager? = null,
    snippets: SnippetManager? = null,
    // Self-hosted sync coordinator. `null` — sync not connected on the platform / mock path: the Sync
    // settings section draws a static mock, the onboarding modal isn't shown.
    sync: SyncCoordinator? = null,
    // Teams coordinator (cross-account sharing over sync). `null` — Teams screen in mock mode.
    teams: app.skerry.ui.teams.TeamsCoordinator? = null,
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
    customGroupsProvider: () -> List<String> = { emptyList() },
    // External cleanup on vault reset (hosts/known_hosts/settings per [ResetScope]). Called after the
    // vault file is erased; the real implementation is supplied by desktop `main`. No-op in mock/preview.
    onVaultReset: (ResetScope) -> Unit = {},
    // Custom chrome of the undecorated desktop window (drag + minimize/maximize/close). `null` —
    // a decorated window (offscreen render/preview): no window buttons are drawn.
    windowChrome: WindowChrome? = null,
) {
    val fonts = DesignFonts(
        ui = rememberSpaceGrotesk(),
        mono = rememberMono(),
        symbols = rememberMaterialSymbols(),
    )
    // SFTP show-hidden setting: kept in state so Ctrl+H updates the UI instantly, and written out
    // (persisted) on every change. remember is required (like terminalAppearance below):
    // LocalSftpPrefs is staticCompositionLocalOf, and a new instance on every recomposition would
    // force a full rebuild of the consumer subtree. The persist callback goes through
    // rememberUpdatedState so the lambda inside remember always calls the fresh onSftpShowHiddenChange.
    var sftpShowHidden by remember { mutableStateOf(initialSftpShowHidden) }
    val sftpShowHiddenWriter = rememberUpdatedState(onSftpShowHiddenChange)
    val sftpPrefs = remember(sftpShowHidden) {
        SftpPrefs(sftpShowHidden) { value ->
            sftpShowHidden = value
            sftpShowHiddenWriter.value(value)
        }
    }
    // Session manager: either supplied from outside (offscreen render with a fake transport), or
    // built from the live transport — one shell per tab, like in [app.skerry.ui.mobile.MobileApp].
    // We close our own graph on dispose; an externally-owned one belongs to the caller and is left alone.
    val scope = rememberCoroutineScope()
    // Per-host terminal command history persistence (autocomplete + the command palette) on top of
    // the encrypted vault. Hoisted out of the sessions factory: the palette reads it directly.
    val termHistory = remember(vault) { vault?.let { VaultTerminalHistoryStore(it) } }
    val liveSessions = sessions ?: remember(transport, vncTransport, scope, vault) {
        transport?.let { t ->
            var counter = 0
            SessionsController(
                newId = { "sess-${counter++}" },
                vncControllerFactory = vncTransport?.let { vt -> { app.skerry.ui.vnc.VncSessionController(vt, scope) } },
                controllerFactory = {
                    ConnectionController(
                        t, scope, history = termHistory,
                        // Read terminal settings at connect time — new sessions pick up the current
                        // scrollback/cursor choice, already-open ones keep their emulator's.
                        terminalPrefs = {
                            TerminalSessionPrefs(
                                state.terminalScrollback,
                                state.terminalCursorStyle,
                                clipboardWriteEnabled = state.allowServerClipboardWrite,
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
    // connect via terminalPrefs). Pushed into each tab's terminal and its split pane; the command goes
    // through the emulator's queue, so no race. Detached/empty tabs are simply skipped.
    val cursorStyle = state.terminalCursorStyle
    LaunchedEffect(cursorStyle, liveSessions) {
        val manager = liveSessions ?: return@LaunchedEffect
        manager.sessions.forEach { s ->
            s.liveTerminal?.applyCursorStyle(cursorStyle.shape, cursorStyle.blink)
            s.splitSession?.liveTerminal?.applyCursorStyle(cursorStyle.shape, cursorStyle.blink)
        }
    }
    // A scrollback-buffer change in settings likewise applies to ALREADY open sessions live: shrinking
    // trims the extra old history, growing keeps new lines around longer. New sessions pick up the
    // value at connect via terminalPrefs.
    val scrollbackLines = TerminalSessionPrefs(scrollback = state.terminalScrollback).effectiveScrollback
    LaunchedEffect(scrollbackLines, liveSessions) {
        val manager = liveSessions ?: return@LaunchedEffect
        manager.sessions.forEach { s ->
            s.liveTerminal?.applyScrollback(scrollbackLines)
            s.splitSession?.liveTerminal?.applyScrollback(scrollbackLines)
        }
    }
    // Toggling the OSC 52 clipboard-write gate applies to ALREADY open sessions live: turning it off
    // stops honoring server clipboard writes immediately, turning it on lets them through. New
    // sessions pick up the value at connect via terminalPrefs.
    val allowClipboardWrite = state.allowServerClipboardWrite
    LaunchedEffect(allowClipboardWrite, liveSessions) {
        val manager = liveSessions ?: return@LaunchedEffect
        manager.sessions.forEach { s ->
            s.liveTerminal?.applyClipboardWriteEnabled(allowClipboardWrite)
            s.splitSession?.liveTerminal?.applyClipboardWriteEnabled(allowClipboardWrite)
        }
    }
    // Memoized: LocalTerminalAppearance is staticCompositionLocalOf (reference comparison), and
    // DesktopDesignApp recomposes on tab/session switches and vault events. Without remember a new
    // instance on every recomposition would force a full rebuild of the consumer subtree (the whole
    // terminal Canvas), even when font/size hadn't changed.
    val terminalAppearance = remember(state.terminalFont, state.terminalFontSize, state.terminalLineHeight, state.terminalLetterSpacing) {
        TerminalAppearance(state.terminalFont, state.terminalFontSize, state.terminalLineHeight, state.terminalLetterSpacing)
    }
    // The terminal AI's reply language = UI language: the provider reads the applied locale tag
    // ([app.skerry.ui.i18n.LocalAppLocale]) and is reset on language change (SideEffect reruns when
    // the tag changes), so INFO/ASK go out in the current language without recreating the controller.
    val aiLocaleTag = app.skerry.ui.i18n.LocalAppLocale.current
    androidx.compose.runtime.SideEffect {
        ai?.uiLanguageProvider = { app.skerry.ui.i18n.aiResponseLanguageName(aiLocaleTag) }
    }
    CompositionLocalProvider(
        LocalFonts provides fonts,
        LocalHosts provides hosts,
        LocalSessions provides liveSessions,
        LocalKnownHosts provides knownHosts,
        LocalSshKeyGenerator provides keyGenerator,
        LocalSshCertificateInspector provides certificateInspector,
        LocalCredentials provides credentials,
        LocalTestTransport provides (testTransport ?: transport),
        LocalTunnels provides tunnels,
        LocalSnippets provides snippets,
        LocalTerminalHistory provides termHistory,
        LocalFeatures provides features,
        LocalSftpPrefs provides sftpPrefs,
        // Terminal appearance from settings: font + size, read by [app.skerry.ui.terminal.TerminalScreen].
        LocalTerminalAppearance provides terminalAppearance,
        // Terminal color theme (Appearance → cards): background/text/ANSI/cursor — same rendering.
        LocalTerminalTheme provides state.terminalTheme,
        // The open vault + biometrics behind the gate — needed for re-authentication before copying
        // a password from the keychain (desktop has no biometrics, so the path reduces to the master password).
        LocalVault provides vault,
        LocalVaultBiometrics provides biometrics,
        LocalSecurityLog provides securityLog,
        LocalSync provides sync,
        LocalTeams provides teams,
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
                autoLockIdleMs = state.autoLock.idleMs,
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

/**
 * The main chrome (titlebar → rail+viewport → statusbar) and overlays. [onLock] != null —
 * the live path behind the gate: the "Unlocked" chip locks the vault. null — the mock path:
 * locking is drawn by the stub [LockScreen] driven by [DesktopDesignState.locked].
 */
@Composable
private fun DesktopChrome(
    state: DesktopDesignState,
    onLock: (() -> Unit)?,
    sessions: SessionsController?,
    credentials: CredentialManagerController?,
    onVaultUnlocked: () -> Unit,
    customGroupsProvider: () -> List<String>,
    windowChrome: WindowChrome? = null,
) {
    val termHistory = LocalTerminalHistory.current
    // Keychain secrets live in the open vault — behind the master-password gate we first fire
    // [onVaultUnlocked], then reload (secrets + synced empty folders).
    LaunchedEffect(credentials) {
        onVaultUnlocked()
        credentials?.reload()
        state.loadCustomGroups(customGroupsProvider())
    }

    // A host with no bound secret → ask for a password before connecting. One shared state for all
    // three paths ([PendingAuth]): new tab / split (the target tab is fixed at the moment the host is
    // chosen, not at submit — otherwise switching tabs while typing the password would open the split
    // in the wrong place) / snippet's "Run on host" (also remembers the command).
    var pendingAuth by remember { mutableStateOf<PendingAuth?>(null) }
    // VNC "ask every time": a host with no stored password prompts before opening the framebuffer tab.
    var pendingVncHost by remember { mutableStateOf<Host?>(null) }

    // ProxyJump chain resolution failed for the clicked host — connecting would either dial
    // forever or silently go direct, so a notice is shown instead ([JumpErrorDialog]).
    var jumpProblem by remember { mutableStateOf<JumpChainProblem?>(null) }
    val hostManager = LocalHosts.current

    // Single connect dispatcher with resolved auth already in hand: where the session goes is decided
    // by [PendingAuth]'s type. The ProxyJump chain is resolved here — right before the session
    // opens — so a password prompt in between can't act on a stale chain.
    fun openResolved(target: PendingAuth, auth: SshAuth) {
        val jump = when (
            val chain = resolveJumpChain(target.host, { id -> hostManager?.find(id) }, { id -> credentials?.find(id) })
        ) {
            is JumpChainResolution.Unavailable -> { jumpProblem = chain.problem; return }
            is JumpChainResolution.Resolved -> chain.jump
        }
        when (target) {
            is PendingAuth.NewTab -> openHostSession(sessions, state, target.host, auth, jump)
            is PendingAuth.Split -> openSplitSession(sessions, state, target.parentId, target.host, auth, jump)
            is PendingAuth.Snippet ->
                openHostSession(sessions, state, target.host, auth, jump) { it.send(target.command + "\n") }
        }
    }

    // Shared step for all three paths: resolve auth ([resolveHostAuth]) → connect right away, or ask
    // for a password while remembering the target.
    fun connectOrAsk(target: PendingAuth) {
        when (val resolution = resolveHostAuth(target.host, credentials)) {
            is HostAuthResolution.Resolved -> openResolved(target, resolution.auth)
            HostAuthResolution.NeedsPassword -> pendingAuth = target
        }
    }

    // Stable connect lambdas: without remember they'd be recreated on every recomposition and,
    // flowing into a staticCompositionLocalOf, would invalidate all consumers of [LocalConnectHost] etc.
    val connectHost = remember(sessions, credentials, hostManager, state) {
        { host: Host ->
            if (host.connectionType.isVnc) {
                // VNC opens a framebuffer tab (not a terminal). A stored password is used directly;
                // "ask every time" (no bound secret) prompts for one first. No ProxyJump/host-key path.
                val cred = credentials?.find(host.credentialId)
                if (cred != null) {
                    sessions?.openVnc(
                        host.id, host.label, host.connectionSubtitle(), host.toTarget(), cred.toVncAuth(),
                        remoteResize = host.vncResizeToWindow,
                        onRemoteResizeChanged = { on -> hostManager?.setVncResizeToWindow(host.id, on) },
                    )
                } else {
                    pendingVncHost = host
                }
                Unit
            } else {
                connectOrAsk(PendingAuth.NewTab(host))
            }
        }
    }

    // Snippet's "Run on host": open a session to the host and run the command once connected.
    val runSnippetOnHost = remember(sessions, credentials, hostManager, state) {
        { host: Host, command: String -> connectOrAsk(PendingAuth.Snippet(host, command)) }
    }

    // Same resolution, but into the active tab's split pane (a new independent secondary session).
    val connectSplitHost = remember(sessions, credentials, hostManager, state) {
        { host: Host -> connectOrAsk(PendingAuth.Split(host, sessions?.activeId)) }
    }

    // Locking the vault must tear down all active tunnels: their connections hold the decrypted
    // secret, and they can't keep hanging around after locking (zero-knowledge). closeAll is
    // idempotent; the mock path (onLock==null) has nothing to wrap.
    val tunnels = LocalTunnels.current
    // Locking tears down active tunnels (their connections hold the decrypted secret — zero-knowledge),
    // but TAB SESSIONS (including split) are deliberately meant to SURVIVE a lock: terminals stay put
    // after unlocking. Any pending password-prompt dialogs are dismissed (don't leave password entry
    // sitting under the lock screen). Stabilized like onRootKey below: the lambda flows into TitleBar
    // and lockAction, and without remember a new instance on every recomposition would force them to
    // recompute for nothing.
    val onLockWithTunnels: (() -> Unit)? = if (onLock == null) null else remember(onLock, tunnels, sessions, state) {
        {
            pendingAuth = null
            // Also dismiss any pending disconnect/close confirmation — after unlock an action needs a
            // fresh user intent (like pendingAuth), not to "resurface" on its own.
            state.dismissClose()
            tunnels?.closeAll()
            // Sessions survive a lock (the open socket stays alive), but auto-reconnect after a lock
            // would re-authenticate with a stale secret against a locked vault — forbid it by clearing
            // the saved credentials on every session and its split panes (zero-knowledge).
            sessions?.sessions?.forEach { s ->
                s.controller.clearReconnectCredentials()
                s.splitSession?.controller?.clearReconnectCredentials()
            }
            onLock()
        }
    }

    CompositionLocalProvider(
        LocalConnectHost provides connectHost,
        LocalConnectSplit provides connectSplitHost,
        LocalRunSnippetOnHost provides runSnippetOnHost,
        LocalCredentials provides credentials,
    ) {
        // Global snippet hotkey: preview events flow from the root down to focus, so the root Box
        // intercepts the chord before the terminal does. If a saved shortcut matches and there's a
        // connected session, run the command in its terminal and consume the event. GATE: only fires
        // when a live session is on screen (no app overlay/modal/settings) — otherwise a chord typed
        // into the snippet editor's fields (Command/ShortcutField) or New connection would go to the
        // terminal as a command.
        val snippets = LocalSnippets.current
        // Live lock (tears down tunnels/credentials) on the live path; state.lock is mock/preview.
        // Via rememberUpdatedState so onRootKey doesn't depend on the lock lambda itself changing.
        val lockAction = rememberUpdatedState(onLockWithTunnels ?: state::lock)
        // Global shell hotkeys (⌘/Ctrl+Shift — New conn/Split/SFTP/AI-bar/Lock, Ctrl+Tab — adjacent
        // tab, Alt+digit — tab by number) are checked BEFORE the snippet hotkey. Same gate: only on a
        // live session screen (no overlay/modal/settings), so a chord from editor fields doesn't leak
        // into the terminal/navigation. SelectTab/Next out of range returns false and falls through to
        // snippet matching (Alt+7 with 4 tabs can still be a snippet).
        val onRootKey = remember(snippets, sessions, state) {
            { event: KeyEvent ->
                if (event.type != KeyEventType.KeyDown) false
                else if (
                    state.appOverlay != null || state.modalOpen || state.settingsOpen ||
                    state.commandPaletteOpen || state.broadcastOpen || state.castRecording != null
                ) false
                else {
                    val shortcut = matchDesktopShortcut(
                        event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.isMetaPressed, event.key,
                    )
                    if (shortcut != null && runDesktopShortcut(shortcut, state, sessions, lockAction.value)) true
                    else runSnippetHotkey(event, snippets, sessions)
                }
            }
        }
        Box(Modifier.fillMaxSize().background(D.bg).onPreviewKeyEvent(onRootKey)) {
            Column(Modifier.fillMaxSize()) {
                TitleBar(state, onLockWithTunnels, windowChrome)
                HLine()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    IconRail(state)
                    VLine(D.line)
                    Box(Modifier.weight(1f).fillMaxHeight()) { Viewport(state) }
                }
                HLine()
                StatusBar()
            }
            // The player owns the whole work area while it is open; Esc (via ModalScrim) closes it.
            state.castRecording?.let { cast -> CastPlayerOverlay(cast, onDismiss = state::closeCast) }
            if (state.castInvalid) {
                NoticeDialog(
                    title = stringResource(Res.string.term_player_title),
                    message = stringResource(Res.string.term_player_invalid),
                    buttonLabel = stringResource(Res.string.term_ai_dismiss),
                    onDismiss = state::dismissCastError,
                )
            }
            state.recordingNotice?.let { outcome ->
                NoticeDialog(
                    title = stringResource(Res.string.term_record_start),
                    message = recordingOutcomeMessage(outcome),
                    buttonLabel = stringResource(Res.string.term_ai_dismiss),
                    onDismiss = state::dismissRecordingNotice,
                )
            }
            if (state.broadcastOpen) {
                BroadcastPanel(
                    controller = state.broadcast,
                    targets = broadcastTargets(sessions),
                    onDismiss = state::closeBroadcast,
                )
            }
            if (state.commandPaletteOpen) {
                val liveTerminal = (sessions?.active?.controller?.uiState as? ConnectionUiState.Connected)?.terminal
                CommandPalette(
                    history = termHistory,
                    currentKey = sessions?.active?.controller?.historyKey,
                    onPick = { command ->
                        liveTerminal?.applyHistoryCommand(command)
                        state.closeCommandPalette()
                    },
                    onDismiss = state::closeCommandPalette,
                )
            }
            if (state.modalOpen) NewConnectionModal(state, editHost = state.editingHost)
            if (state.settingsOpen) SettingsPanel(state)
            // Sync onboarding modal over settings: appears via "Set up sync", closes itself on a
            // successful connect. Only when the coordinator is supplied (the mock path with no backend has none).
            LocalSync.current?.let { if (state.syncSetupOpen) SyncSetupDialog(it, onDismiss = state::closeSyncSetup) }
            // "Link a device" dialog: shows a QR/code for quick pairing of a new device.
            LocalSync.current?.let { if (state.pairingOpen) PairingShowDialog(it, onDismiss = state::closePairing) }
            if (onLock == null && state.locked) LockScreen(state)
            // A single password-prompt dialog for all three connect paths; after submit the target
            // ([PendingAuth]) is dispatched through the same openResolved as the bound-secret path.
            pendingAuth?.let { pending ->
                DesktopPasswordDialog(
                    host = pending.host,
                    onDismiss = { pendingAuth = null },
                    onConnect = { pw ->
                        pendingAuth = null
                        openResolved(pending, SshAuth.Password(pw))
                    },
                )
            }
            // VNC password prompt ("ask every time"): an empty entry means the server needs no password
            // (security type None); a non-empty one is the VNC-Auth password.
            pendingVncHost?.let { host ->
                DesktopPasswordDialog(
                    host = host,
                    onDismiss = { pendingVncHost = null },
                    onConnect = { pw ->
                        pendingVncHost = null
                        val auth = if (pw.isEmpty()) app.skerry.shared.vnc.VncAuth.None else app.skerry.shared.vnc.VncAuth.Password(pw)
                        sessions?.openVnc(
                            host.id, host.label, host.connectionSubtitle(), host.toTarget(), auth,
                            remoteResize = host.vncResizeToWindow,
                            onRemoteResizeChanged = { on -> hostManager?.setVncResizeToWindow(host.id, on) },
                        )
                    },
                )
            }
            // Broken ProxyJump chain for the clicked host: explain instead of connecting (never
            // silently direct). Set by openResolved for all three connect paths.
            jumpProblem?.let { problem ->
                JumpErrorDialog(problem, onDismiss = { jumpProblem = null })
            }
            // Delete-host-profile confirmation (invoked from the sidebar's context menu). The keychain
            // secret itself stays in the vault (reusable, managed from the Vault tab).
            val hosts = LocalHosts.current
            state.pendingDeleteHost?.let { host ->
                DesktopDeleteHostDialog(
                    host = host,
                    onDismiss = state::dismissDeleteHost,
                    onConfirm = { hosts?.delete(host.id); state.dismissDeleteHost() },
                )
            }
            // Create/edit a host group ("+folder" button and the pencil in a folder header). Rename
            // both rewrites Host.group through the controller and updates the side-channel of
            // empty/collapsed groups in state; delete ungroups the hosts (profiles are untouched).
            when (val gd = state.groupDialog) {
                GroupDialog.Create -> GroupEditDialog(
                    initialName = "",
                    onDismiss = state::dismissGroupDialog,
                    onSave = { name -> state.addCustomGroup(name); state.dismissGroupDialog() },
                    onDelete = null,
                )
                is GroupDialog.Rename -> GroupEditDialog(
                    initialName = gd.name,
                    onDismiss = state::dismissGroupDialog,
                    onSave = { name ->
                        hosts?.renameGroup(gd.name, name)
                        state.renameGroupName(gd.name, name)
                        state.dismissGroupDialog()
                    },
                    onDelete = {
                        hosts?.deleteGroup(gd.name)
                        state.removeCustomGroup(gd.name)
                        state.dismissGroupDialog()
                    },
                )
                null -> {}
            }
            // Confirm disconnecting a session (power) / closing a split pane — destructive, no auto-reconnect.
            when (val pc = state.pendingClose) {
                is PendingClose.Session -> {
                    val name = sessions?.sessions?.firstOrNull { it.id == pc.id }?.displayTitle ?: stringResource(Res.string.shell_this_session)
                    ConfirmActionDialog(
                        title = stringResource(Res.string.shell_disconnect_title, name),
                        message = stringResource(Res.string.shell_disconnect_message),
                        confirmLabel = stringResource(Res.string.shell_disconnect),
                        onConfirm = { sessions?.close(pc.id); state.dismissClose() },
                        onDismiss = state::dismissClose,
                    )
                }
                is PendingClose.Split -> {
                    ConfirmActionDialog(
                        title = stringResource(Res.string.shell_close_split_title),
                        message = stringResource(Res.string.shell_close_split_message),
                        confirmLabel = stringResource(Res.string.shell_close_panel),
                        onConfirm = { sessions?.closeSplit(pc.parentId); state.dismissClose() },
                        onDismiss = state::dismissClose,
                    )
                }
                null -> {}
            }
        }
    }
}

/**
 * A pending connect waiting on a password (SSH host with no bound secret) — and at the same time
 * the delivery address for the resolved auth: a new tab, a specific tab's split pane, or running a
 * snippet command on the host.
 */
private sealed interface PendingAuth {
    val host: Host

    /** Connect as a new tab (or into the active empty one). */
    data class NewTab(override val host: Host) : PendingAuth

    /** Connect into the split pane of tab [parentId] (fixed at the moment the host was chosen). */
    data class Split(override val host: Host, val parentId: String?) : PendingAuth

    /** Open a session to the host and run [command] once connected. */
    data class Snippet(override val host: Host, val command: String) : PendingAuth
}

/**
 * Global snippet hotkey: on KeyDown, serializes the chord ([SnippetShortcut]), looks up a snippet with
 * that hotkey and, if there's a connected session, runs its command in that session's terminal.
 * Returns `true` (event consumed) only on an actual run — otherwise the key falls through (to the
 * terminal, etc.).
 */
private fun runSnippetHotkey(event: KeyEvent, manager: SnippetManager?, sessions: SessionsController?): Boolean {
    if (event.type != KeyEventType.KeyDown || manager == null) return false
    val combo = SnippetShortcut.format(
        event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.isMetaPressed, event.key,
    ) ?: return false
    val entry = manager.forShortcut(combo) ?: return false
    val terminal = (sessions?.active?.controller?.uiState as? ConnectionUiState.Connected)?.terminal ?: return false
    manager.run(entry.id) { terminal.sendUserInput(it) }
    return true
}

/**
 * Run a global shell hotkey ([matchDesktopShortcut]). Returns `true` if the action was applied
 * (consume the event), `false` if there's no target (e.g. Alt+digit past the tab count): the caller
 * then lets the key fall through (including to the snippet hotkey). Live mode addresses tabs via
 * [SessionsController]; mock/preview (no live sessions) uses the demo tabs in [DesktopDesignState].
 */
private fun runDesktopShortcut(
    shortcut: DesktopShortcut,
    state: DesktopDesignState,
    sessions: SessionsController?,
    onLock: () -> Unit,
): Boolean {
    when (shortcut) {
        is DesktopShortcut.SelectTab -> return selectTabByIndex(shortcut.index, state, sessions)
        DesktopShortcut.NextTab -> return cycleTab(+1, state, sessions)
        DesktopShortcut.PrevTab -> return cycleTab(-1, state, sessions)
        DesktopShortcut.NewConnection -> state.openModal()
        DesktopShortcut.SplitTerminal -> if (sessions != null) sessions.toggleSplit() else state.toggleSplit()
        DesktopShortcut.OpenSftp -> if (sessions != null) {
            state.clearOverlay(); sessions.setActiveView(SessionView.Sftp)
        } else {
            state.showView(DesktopView.Sftp)
        }
        DesktopShortcut.Lock -> onLock()
        DesktopShortcut.Broadcast -> state.openBroadcast()
        // Only over a live terminal: the palette inserts into it, so with nothing to insert into the
        // key falls through (to the snippet hotkey) instead of opening a dead-end overlay.
        DesktopShortcut.CommandPalette -> {
            if (sessions?.active?.controller?.uiState !is ConnectionUiState.Connected) return false
            state.openCommandPalette()
        }
        DesktopShortcut.FocusAiBar -> state.requestAiBarFocus()
    }
    return true
}

/** Select a tab by 0-based index; `false` if no such tab exists (the key falls through). */
internal fun selectTabByIndex(index: Int, state: DesktopDesignState, sessions: SessionsController?): Boolean {
    if (sessions != null) {
        val target = sessions.sessions.getOrNull(index) ?: return false
        sessions.activate(target.id)
        return true
    }
    if (index !in state.tabs.indices) return false
    state.setTab(index)
    return true
}

/** Cyclically shift the active tab by [delta] (wrapping); `false` if there are no tabs. */
internal fun cycleTab(delta: Int, state: DesktopDesignState, sessions: SessionsController?): Boolean {
    if (sessions != null) {
        val list = sessions.sessions
        if (list.isEmpty()) return false
        val current = list.indexOfFirst { it.id == sessions.activeId }.coerceAtLeast(0)
        val next = ((current + delta) % list.size + list.size) % list.size
        sessions.activate(list[next].id)
        return true
    }
    val count = state.tabs.size
    if (count == 0) return false
    val next = ((state.activeTab + delta) % count + count) % count
    state.setTab(next)
    return true
}

/**
 * Connect to [host] with [auth]: if an empty ("+") tab is active — connect into it, otherwise a new
 * tab ([SessionsController.connect]). Then switch to the terminal (clearing the app overlay).
 * [jump] is the host's resolved ProxyJump chain (`null` — direct).
 */
private fun openHostSession(
    sessions: SessionsController?,
    state: DesktopDesignState,
    host: Host,
    auth: SshAuth,
    jump: SshJump? = null,
    onConnected: ((app.skerry.ui.terminal.TerminalScreenState) -> Unit)? = null,
) {
    // Record the host in the sidebar's RECENT section (newest first, survives restart).
    state.recordRecentHost(host.id)
    sessions?.connect(
        hostId = host.id,
        title = host.label,
        subtitle = host.connectionSubtitle(),
        target = host.toTarget(jump),
        auth = auth,
        onConnected = onConnected,
    )
    // Live mode: the sub-view is held by the tab itself — just clear the overlay to show its terminal.
    // Mock/preview (no sessions): fall back to Terminal via showView.
    if (sessions != null) state.clearOverlay() else state.showView(DesktopView.Terminal)
}

/**
 * Connect [host] with [auth] into the active tab's split pane (a new independent secondary session).
 * No-op with no active tab. See [SessionsController.connectSplit].
 */
private fun openSplitSession(sessions: SessionsController?, state: DesktopDesignState, parentId: String?, host: Host, auth: SshAuth, jump: SshJump? = null) {
    if (sessions == null || parentId == null) return
    // Connecting into the secondary pane is also a real connect to the host — record it in RECENT too.
    state.recordRecentHost(host.id)
    sessions.connectSplit(
        parentId = parentId,
        hostId = host.id,
        title = host.label,
        subtitle = host.connectionSubtitle(),
        target = host.toTarget(jump),
        auth = auth,
    )
}

@Composable
private fun TitleBar(state: DesktopDesignState, onLock: (() -> Unit)?, windowChrome: WindowChrome? = null) {
    // With custom chrome, the titlebar doubles as the window-drag area (the OS titlebar is gone).
    if (windowChrome != null) windowChrome.dragArea { TitleBarRow(state, onLock, windowChrome) }
    else TitleBarRow(state, onLock, windowChrome = null)
}

@Composable
private fun TitleBarRow(state: DesktopDesignState, onLock: (() -> Unit)?, windowChrome: WindowChrome?) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Brush.verticalGradient(listOf(D.titleTop, D.titleBottom)))
            .padding(start = 14.dp, end = if (windowChrome != null) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            // Consume presses so the titlebar's double-click-to-maximize (and window drag) treats
            // the brand mark like a button, not empty titlebar space — clicking the logo must not
            // toggle maximize.
            Modifier.pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            BrandMark(size = 28.dp)
            Txt("Skerry", color = D.text, size = 14.5.sp, weight = FontWeight.Bold, letterSpacing = (-0.2).sp)
        }
        Row(
            Modifier.weight(1f).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // Live tabs from the session manager (behind the vault gate); otherwise mock tabs.
            val sessions = LocalSessions.current
            // The localized label for a new blank tab is resolved here (composable side): stringResource
            // isn't available in SessionsController, so the label is passed into openBlank.
            val newTabTitle = stringResource(Res.string.shtail_new_tab)
            if (sessions != null) {
                // Tab drag-reorder state: dragging chips to swap places.
                val tabDrag = remember { TabDragState() }
                // rememberUpdatedState: pointerInput is only recreated by the tabId key, so the ids()
                // lambda must read the fresh list via .value, otherwise onDragEnd would use a stale
                // order (same as done for host drag).
                val tabIds = rememberUpdatedState(sessions.sessions.map { it.id })
                sessions.sessions.forEachIndexed { index, s ->
                    // Insert line before the chip the dragged tab is currently hovering over.
                    if (tabDrag.insertLineIndex == index) TabInsertLine()
                    // On a split, the chip shows the focused pane: the name changes when focus
                    // switches between the main and split panes.
                    val focused = if (s.splitOpen && s.focusedSplit) s.splitSession ?: s else s
                    SessionTabChip(
                        name = focused.tabTitle(state.showTerminalTitleOnTabs),
                        dot = sessionDotColor(focused.controller.uiState),
                        split = s.splitOpen,
                        active = s.id == sessions.activeId,
                        onClick = { sessions.activate(s.id) },
                        onClose = { tabDrag.clearBounds(s.id); sessions.close(s.id) },
                        dragging = tabDrag.draggingTabId == s.id,
                        modifier = Modifier
                            .tabBoundsAnchor(tabDrag, s.id)
                            .draggableTab(tabDrag, s.id, ids = { tabIds.value }) { from, to -> sessions.moveTab(from, to) },
                    )
                }
                // Insert line at the very end of the row (moving a tab to the tail).
                if (tabDrag.insertLineIndex == sessions.sessions.size) TabInsertLine()
            } else {
                state.tabs.forEachIndexed { i, tab ->
                    SessionTabChip(tab.name, tab.dot, active = i == state.activeTab, onClick = { state.setTab(i) }, onClose = { state.closeTab(i) })
                }
            }
            // "+" creates a BLANK tab with no session (live mode) and switches to its terminal
            // placeholder; the first connect from the sidebar fills it in ([SessionsController.connect]).
            // In mock/preview (no live sessions), keep the old behavior — open the modal.
            IconBtn(
                "add",
                onClick = {
                    if (sessions != null) {
                        // A new blank tab starts on the Terminal sub-view (Session.view's default);
                        // clear the overlay to show its terminal placeholder.
                        sessions.openBlank(newTabTitle)
                        state.clearOverlay()
                    } else {
                        state.openModal()
                    }
                },
                box = 26,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(D.cyan08)
                    .border(1.dp, D.cyan20, RoundedCornerShape(6.dp))
                    .clickable(onClick = onLock ?: state::lock)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Sym("lock_open", size = 14.sp, color = D.cyan)
                Txt(stringResource(Res.string.shell_unlocked), color = D.cyan, size = 11.sp, weight = FontWeight.Medium)
            }
            if (windowChrome != null) WindowButtons(windowChrome, Modifier.padding(start = 8.dp))
        }
    }
}

/**
 * A session tab as a segmented pill with editor-style selection: active — a thin cyan strip on the top
 * edge + cyan background with bright text; hovered inactive — a slightly lighter background; resting — a
 * muted translucent pill with dim text. A connection status dot on the left. The close cross shows only
 * on the active or hovered tab; others reserve the space with an empty box so text doesn't jump when the
 * cross appears.
 */
@Composable
private fun SessionTabChip(
    name: String,
    dot: Color,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    split: Boolean = false,
    dragging: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    // Shared interactionSource: clickable emits hover events that collectIsHoveredAsState reads.
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val showClose = active || hovered
    Row(
        modifier
            // Dim a dragged chip (alpha) so it reads as "lifted" out of the row.
            .alpha(if (dragging) 0.5f else 1f)
            .height(28.dp)
            .clip(shape)
            .background(
                when {
                    active -> D.cyan10
                    hovered -> Color(0x1FFFFFFF)
                    else -> D.card
                },
            )
            .border(1.dp, if (active) D.cyan20 else D.line, shape)
            // Accent strip on the active tab's top edge (editor tab style). drawBehind renders over the
            // background/border but under content; doesn't inflate the chip's width.
            .then(
                if (active) {
                    Modifier.drawBehind { drawRect(D.cyan, size = Size(size.width, 2.dp.toPx())) }
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = 11.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(dot)
        // Split marker: the tab holds two panes.
        if (split) Sym("splitscreen_right", size = 13.sp, color = if (active) D.cyan else D.faint)
        Txt(
            name,
            color = if (active) D.text else D.dim,
            size = 12.sp,
            weight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp),
        )
        if (showClose) {
            IconBtn("close", onClick = onClose, box = 16, icon = 14.sp, tint = if (active) D.dim else D.faint)
        } else {
            Box(Modifier.width(16.dp))
        }
    }
}

/** Vertical insertion-position indicator during tab drag-reorder (cyan accent). */
@Composable
private fun TabInsertLine() {
    Box(Modifier.width(2.dp).height(22.dp).clip(RoundedCornerShape(1.dp)).background(D.cyan))
}

@Composable
private fun IconRail(state: DesktopDesignState) {
    val sessions = LocalSessions.current
    // Current session-level item to highlight: the active tab's subview (live mode) or the mock fallback
    // [state.view]. Under an open app overlay, session items aren't highlighted.
    val currentSessionView = sessions?.active?.view?.asDesktopView() ?: state.view
    Column(
        Modifier
            .width(52.dp)
            .fillMaxHeight()
            .background(D.railBg)
            .padding(horizontal = 7.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // Collapse/expand toggle for the terminal's hosts sidebar: lives on the rail (not in the
        // sidebar header) and only while the terminal view is on screen. Deliberately shorter than
        // the 38dp view buttons — an auxiliary control, not a view. VNC maps to the same rail item
        // but drives its own slide-over drawer (closed by default, overlays the framebuffer).
        if (state.appOverlay == null && currentSessionView == DesktopView.Terminal) {
            if (sessions?.active?.view == SessionView.Vnc) {
                SidebarToggle(hidden = !state.vncSidebar, onToggle = state::toggleVncSidebar)
            } else {
                SidebarToggle(hidden = state.sidebarHidden, onToggle = state::toggleSidebar)
            }
        }
        RAIL.forEach { item ->
            val active = if (state.appOverlay != null) item.view == state.appOverlay
            else item.view == currentSessionView
            RailButton(
                icon = item.icon,
                label = stringResource(item.label),
                active = active,
                onClick = {
                    // App-level (Vault/Known/Teams/Snippets) → overlay. Session-level: in live mode edits
                    // only the active tab's subview (source of truth) + clears the overlay, without
                    // touching the mock fallback state.view; with no sessions — the mock path via showView.
                    when {
                        item.view.isAppLevel -> state.showView(item.view)
                        sessions != null -> { state.clearOverlay(); sessions.setActiveView(item.view.asSessionView()) }
                        else -> state.showView(item.view)
                    }
                },
            )
        }
        Spacer(Modifier.weight(1f))
        RailButton(icon = "settings", label = stringResource(Res.string.shell_settings), active = false, onClick = state::openSettings)
    }
}

/** Short rail button that hides/restores the left hosts panel (chevron flips with state). */
@Composable
private fun SidebarToggle(hidden: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .hoverable(interaction)
            .background(if (hovered) D.cyan.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Sym(if (hidden) "chevron_right" else "chevron_left", size = 17.sp, color = if (hovered) D.dim else D.faint)
    }
}

@Composable
private fun RailButton(icon: String, label: String, active: Boolean, onClick: () -> Unit) {
    val fg = if (active) D.cyanBright else D.faint
    // Icons without labels: the item name goes to a hover tooltip (desktop) so the narrow column doesn't
    // wrap long words.
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(Modifier.fillMaxWidth().hoverable(interaction)) {
        if (active) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(vertical = 9.dp)
                    .width(2.dp)
                    .height(20.dp)
                    .background(D.cyan, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)),
            )
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (active) D.cyan10 else if (hovered) D.cyan.copy(alpha = 0.06f) else Color.Transparent)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Sym(icon, size = 21.sp, color = fg)
        }
        // Tooltip to the right of the rail — only while the cursor is over the button.
        if (hovered) {
            val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
            val position = remember(gap) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset = IntOffset(
                        x = anchorBounds.right + gap,
                        y = anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2,
                    )
                }
            }
            Popup(
                popupPositionProvider = position,
                properties = PopupProperties(focusable = false),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(D.railBg)
                        .border(1.dp, D.cyan.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Txt(label, color = D.textBright, size = 11.sp, weight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun StatusBar() {
    val mono = LocalFonts.current.mono
    // In live mode the left status and throughput reflect the active session.
    val sessions = LocalSessions.current
    val active = sessions?.active
    val connected = active?.controller?.uiState is ConnectionUiState.Connected
    val live = sessions != null
    val statusText = if (!live || connected) stringResource(Res.string.shell_status_connected) else stringResource(Res.string.shell_status_disconnected)
    val statusColor = if (!live || connected) D.moss else D.faint
    // Channel throughput poller for the active session (when connected). The remember is unconditional —
    // keys (session + connected flag) recreate it on session/connection change; openThroughput is
    // idempotent (cached in ConnectionController).
    val throughput = remember(active, connected) {
        if (connected) active.controller.openThroughput() else null
    }
    val upRate = throughput?.upRate
    val downRate = throughput?.downRate
    // RTT of the active session's keep-alive poller (same approach as throughput); null with the
    // profile's keep-alive off (no pings, no RTT), before the first sample, or on failure.
    val ping = remember(active, connected) {
        if (connected) active.controller.openPing() else null
    }
    val rttMs = ping?.rttMs
    // Grid size — live cols×rows of the active terminal; off-connection the mock label remains.
    val gridLabel = (sessions?.active?.controller?.uiState as? ConnectionUiState.Connected)
        ?.terminal?.let { "${it.cols} × ${it.rows}" } ?: "80 × 24"
    // ProxyJump route of the active session's profile ("outer → inner", entry hop first) — the
    // at-a-glance "this session rides through a bastion" marker. Hidden for direct connections
    // and in mock mode, so the prototype's bar is unchanged.
    val statusHosts = LocalHosts.current
    val jumpRoute = if (live) {
        active?.hostId?.let { id -> statusHosts?.find(id) }?.let { h -> jumpRouteLabel(h) { statusHosts?.find(it) } }
    } else null
    Row(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(D.ink)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusItem("circle", statusText, color = statusColor, iconSize = 11.sp, mono = mono)
            // Jump route right next to the connection status, cyan so it reads at a glance.
            if (jumpRoute != null) StatusItem("alt_route", jumpRoute, color = D.cyan, mono = mono)
            // Live RTT ping of the active session (before the first sample — "—"); mock mode — template label.
            StatusItem("network_ping", if (live) (rttMs?.let { "$it ms" } ?: "—") else "42 ms", mono = mono)
            // Live channel throughput (before connect — "—"); mock mode (offscreen) — template labels.
            StatusItem("arrow_upward", if (live) (upRate?.let { humanRate(it) } ?: "—") else "1.2 KB/s", mono = mono)
            StatusItem("arrow_downward", if (live) (downRate?.let { humanRate(it) } ?: "—") else "8.4 KB/s", mono = mono)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Update notice (undismissed newer release): click opens the GitHub release page.
            app.skerry.ui.update.UpdateStatusItem()
            // Server version — live ident of the active session (before connect / if the transport is silent — "—").
            StatusItem("memory", if (live) (sessions.active?.controller?.serverVersion ?: "—") else "SSH-2.0-OpenSSH_8.9p1", mono = mono)
            Txt(stringResource(Res.string.shell_status_encoding), color = D.faint, size = 10.5.sp, font = mono)
            Txt(gridLabel, color = D.faint, size = 10.5.sp, font = mono)
            // The sync indicator follows session status (see syncIndicator): green only with an active
            // session + reachable server; linked-but-not-connected → amber, etc. Hidden when sync isn't
            // configured / not yet pinged. Rendered as a bare glyph (no label) to match the mobile header,
            // pinned to the far right after all status texts.
            val syncC = LocalSync.current
            val ind = syncC?.let { syncIndicatorLocalized(it.status.collectAsState().value, it.serverReachable.collectAsState().value) }
            if (ind != null) {
                Sym(
                    ind.icon,
                    size = 13.sp,
                    color = when (ind.level) {
                        SyncIndicatorLevel.OK -> D.moss
                        SyncIndicatorLevel.WARN -> D.amber
                        SyncIndicatorLevel.ERROR -> D.sunset
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusItem(
    icon: String,
    text: String,
    color: Color = D.faint,
    iconSize: TextUnit = 13.sp,
    mono: FontFamily,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Sym(icon, size = iconSize, color = color)
        Txt(text, color = color, size = 10.5.sp, font = mono)
    }
}
