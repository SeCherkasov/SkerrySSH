package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshJump
import app.skerry.shared.ssh.usesSshAuth
import app.skerry.shared.ssh.isVnc
import app.skerry.shared.ai.AiSettingsStore
import app.skerry.ui.ai.aiProviderFactory
import app.skerry.ui.AppDependencies
import app.skerry.ui.ai.AiAssistantController
import app.skerry.shared.terminal.VaultTerminalHistoryStore
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.JumpChainProblem
import app.skerry.ui.connection.JumpChainResolution
import app.skerry.ui.connection.JumpErrorDialog
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.connection.resolveJumpChain
import app.skerry.ui.connection.toSshAuth
import app.skerry.ui.connection.toTarget
import app.skerry.ui.connection.toVncAuth
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.nav.PlatformBackHandler
import app.skerry.ui.session.SessionsController
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.sync.SyncStatus
import app.skerry.ui.sync.SyncOnboardingScreen
import app.skerry.ui.design.NoticeDialog
import app.skerry.ui.generated.resources.term_ai_dismiss
import app.skerry.ui.generated.resources.term_player_invalid
import app.skerry.ui.generated.resources.term_player_title
import app.skerry.ui.terminal.CastPlayerOverlay
import app.skerry.ui.terminal.LocalTerminalAppearance
import app.skerry.ui.terminal.LocalTerminalTheme
import app.skerry.ui.terminal.TerminalAppearance
import app.skerry.ui.terminal.TerminalSessionPrefs
import app.skerry.ui.vault.ResetScope
import app.skerry.ui.vault.VaultGate
import app.skerry.ui.vault.tearDownForLock
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_route_team
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.app.FeatureFlags
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalConnectHost
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.LocalFeatures
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalKnownHosts
import app.skerry.ui.app.LocalOpenSftp
import app.skerry.ui.app.LocalSecurityLog
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.snippet.SnippetRunDialog
import app.skerry.ui.app.LocalTerminalHistory
import app.skerry.ui.app.LocalSshCertificateInspector
import app.skerry.ui.app.LocalSshKeyGenerator
import app.skerry.ui.app.LocalSync
import app.skerry.ui.app.LocalTeams
import app.skerry.ui.app.LocalTunnels
import app.skerry.ui.app.LocalUpdates
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.app.MobileBackAction
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.app.MobileTab
import app.skerry.ui.app.mobileBackAction
import app.skerry.ui.design.rememberMaterialSymbols
import app.skerry.ui.design.rememberMono
import app.skerry.ui.design.rememberUiFont
import app.skerry.ui.terminal.TerminalThemes
import app.skerry.ui.theme.Skerry
import app.skerry.ui.theme.ThemeMode
import app.skerry.ui.theme.systemInDarkTheme
import app.skerry.ui.theme.terminalThemeId

/**
 * Root of the mobile layout. Supplies fonts via [LocalFonts] and live backends via
 * [LocalHosts]/[LocalKnownHosts]/[LocalFeatures], holds [MobileDesignState], and assembles the
 * shell: current tab content (or a push screen) + bottom tab bar.
 *
 * If [AppDependencies.vault] is present, all content is gated behind the master password
 * ([VaultGate]) with mobile forms ([MobileCreateScreen]/[MobileUnlockScreen]). Without a vault
 * (preview path), only chrome with mock data is rendered.
 */
@Composable
fun MobileDesignApp(
    deps: AppDependencies = AppDependencies(),
    state: MobileDesignState = remember { MobileDesignState() },
    features: FeatureFlags = FeatureFlags(),
    sessions: SessionsController? = null,
    // AI controller supplied externally (offscreen render of the AI screen with a fake provider);
    // null builds it from deps.vault below, as usual.
    aiOverride: AiAssistantController? = null,
    // Update-notice controller override for offscreen renders (like [aiOverride]); null builds one
    // from the vault when present.
    updatesOverride: app.skerry.ui.update.UpdateNoticeController? = null,
    // Hook on vault unlock (parity with desktop `main`/`DesktopDesignApp`): reload managers from
    // decrypted records, restore the sync session. No-op in preview/offscreen.
    onVaultUnlocked: () -> Unit = {},
    // External cleanup on an irreversible vault reset (hosts/known_hosts/settings by [ResetScope]).
    // Parity seam with desktop: the Android entry point wires up real cleanup (like `onVaultReset`
    // in desktop `main`) once the mobile vault graph is wired. No-op in preview/offscreen.
    onVaultReset: (ResetScope) -> Unit = {},
) {
    val fonts = DesignFonts(
        ui = rememberUiFont(),
        mono = rememberMono(),
        symbols = rememberMaterialSymbols(),
    )
    // Session manager: supplied externally (offscreen render with a fake transport) or built from
    // the live transport — one shell per session.
    // Dispose our own graph; an externally supplied one is the caller's, leave it alone.
    val scope = rememberCoroutineScope()
    // Per-host terminal command history over the encrypted vault: autocomplete writes it, the
    // command palette reads every host's. Hoisted out of the sessions factory so both can see it.
    val termHistory = remember(deps.vault) { deps.vault?.let { VaultTerminalHistoryStore(it) } }
    val liveSessions = sessions ?: remember(deps.transport, scope, deps.vault) {
        deps.transport?.let { t ->
            var counter = 0
            SessionsController(
                newId = { "sess-${counter++}" },
                vncControllerFactory = deps.vncTransport?.let { vt -> { app.skerry.ui.vnc.VncSessionController(vt, scope) } },
                controllerFactory = {
                    ConnectionController(
                        t, scope, history = termHistory,
                        // Terminal settings are read at connect time — new sessions pick up the current
                        // scrollback/cursor/clipboard choice, already-open ones are updated live below.
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
    val ownsSessions = sessions == null
    DisposableEffect(liveSessions) { onDispose { if (ownsSessions) liveSessions?.disconnectAll() } }
    // A cursor-style change applies to ALREADY open sessions live (new ones pick it up at connect via
    // terminalPrefs). Pushed into each session's terminal; the command goes through the emulator's
    // queue, so no race. Mobile has no split pane, but push into it too for parity/safety.
    val cursorStyle = state.terminalCursorStyle
    LaunchedEffect(cursorStyle, liveSessions) {
        val manager = liveSessions ?: return@LaunchedEffect
        manager.sessions.forEach { s ->
            s.liveTerminal?.applyCursorStyle(cursorStyle.shape, cursorStyle.blink)
            s.splitSession?.liveTerminal?.applyCursorStyle(cursorStyle.shape, cursorStyle.blink)
        }
    }
    // A scrollback change likewise applies to ALREADY open sessions live: shrinking trims old history,
    // growing keeps new lines around longer. New sessions pick up the value at connect via terminalPrefs.
    val scrollbackLines = TerminalSessionPrefs(scrollback = state.terminalScrollback).effectiveScrollback
    LaunchedEffect(scrollbackLines, liveSessions) {
        val manager = liveSessions ?: return@LaunchedEffect
        manager.sessions.forEach { s ->
            s.liveTerminal?.applyScrollback(scrollbackLines)
            s.splitSession?.liveTerminal?.applyScrollback(scrollbackLines)
        }
    }
    // Toggling the OSC 52 clipboard-write gate applies to ALREADY open sessions live; new sessions
    // pick the value up at connect via terminalPrefs above.
    val allowClipboardWrite = state.allowServerClipboardWrite
    LaunchedEffect(allowClipboardWrite, liveSessions) {
        val manager = liveSessions ?: return@LaunchedEffect
        manager.sessions.forEach { s ->
            s.liveTerminal?.applyClipboardWriteEnabled(allowClipboardWrite)
            s.splitSession?.liveTerminal?.applyClipboardWriteEnabled(allowClipboardWrite)
        }
    }
    // Memoized: LocalTerminalAppearance is staticCompositionLocalOf (reference comparison); without
    // remember a new instance on every recomposition would force a rebuild of the terminal subtree.
    val terminalAppearance = remember(state.terminalFont, state.terminalFontSize, state.terminalLineHeight, state.terminalLetterSpacing) {
        TerminalAppearance(state.terminalFont, state.terminalFontSize, state.terminalLineHeight, state.terminalLetterSpacing)
    }
    // AI assistant, parity with desktop `main`: settings (provider/BYOK/local model) are a SETTINGS
    // record in the vault; requests go to the cloud or a local runtime per the router's choice
    // (aiProviderFactory + localAi from the platform graph). Built when a vault is present (null in
    // preview → AI surfaces show a mock). Vault is locked at startup (settings=default); refreshed on unlock.
    val builtAi = remember(deps.vault, scope) {
        deps.vault?.let { v ->
            val store = AiSettingsStore(v)
            AiAssistantController(
                initialSettings = store.load(),
                persist = store::save,
                providerFactory = aiProviderFactory(deps.localAi),
                scope = scope,
                reload = store::load,
                localInstalled = { m -> deps.localAi?.installed(m) ?: false },
                models = deps.localAi?.modelsController(scope),
            )
        }
    }
    val ai = aiOverride ?: builtAi
    // Update notice, parity with desktop `main`: the toggle is a synced SETTINGS record in the
    // vault; the daily GitHub Releases check starts only after unlock (refresh() in MobileChrome).
    val builtUpdates = remember(deps.vault, scope) {
        deps.vault?.let { app.skerry.ui.update.updateNoticeController(it, scope) }
    }
    // If the keys ever change, remember drops the old controller silently — stop its check loop
    // instead of leaving it running on the still-alive scope.
    DisposableEffect(builtUpdates) { onDispose { builtUpdates?.stop() } }
    val updates = updatesOverride ?: builtUpdates
    // AI settings live as a SETTINGS record in the (synced) vault. The controller must be reloaded
    // when sync pulls records from the server, otherwise a BYOK key configured on another device
    // won't show up in the mobile UI without a re-login. Vault unlock is handled SEPARATELY, in
    // [MobileChrome] (it composes only behind the gate and re-enters composition on every unlock):
    // hanging refresh off [deps.credentials] won't work — on Android that controller is created
    // once and never changes, so the effect would fire exactly once at locked startup and reset to defaults.
    val syncStatus = deps.sync?.status?.collectAsState()?.value
    LaunchedEffect(syncStatus) {
        if (syncStatus is SyncStatus.Online && syncStatus.lastPulled > 0) {
            ai?.refresh()
            // The update-check toggle is also a synced SETTINGS record; refresh() only reconciles
            // the loop, it does not re-run the check on every pull.
            updates?.refresh()
        }
    }
    // Terminal AI response language follows the UI language (see DesktopDesignApp): the provider
    // reads the applied locale tag and resets when the language changes.
    val aiLocaleTag = app.skerry.ui.i18n.LocalAppLocale.current
    androidx.compose.runtime.SideEffect {
        ai?.uiLanguageProvider = { app.skerry.ui.i18n.aiResponseLanguageName(aiLocaleTag) }
    }
    // Unified theming: unless the user opted into a separate terminal theme, the terminal follows
    // the app theme's twin ([ThemeMode.terminalThemeId]); SYSTEM tracks the OS side live.
    val termSystemDark = systemInDarkTheme(enabled = !state.customTerminalTheme && state.themeMode == ThemeMode.SYSTEM)
    val effectiveTerminalTheme =
        if (state.customTerminalTheme) state.terminalTheme
        else TerminalThemes.fromId(state.themeMode.terminalThemeId(termSystemDark))
    CompositionLocalProvider(
        LocalFonts provides fonts,
        // Terminal appearance from settings (More → Appearance): font + size read by TerminalScreen.
        LocalTerminalAppearance provides terminalAppearance,
        // Terminal color theme: the app theme's twin, or the separately-picked one (More → Appearance → cards).
        LocalTerminalTheme provides effectiveTerminalTheme,
        LocalHosts provides deps.hosts,
        LocalSessions provides liveSessions,
        LocalKnownHosts provides deps.knownHosts,
        LocalFeatures provides features,
        // AI assistant (BYOK): More→AI settings tab, per-host policies, terminal AI bar.
        LocalAi provides ai,
        // Update notice: More → About push screen (toggle + release link).
        LocalUpdates provides updates,
        // SSH key inspector/generator + certificate inspector — Vault tab: fingerprints, generation, cert parsing.
        LocalSshKeyGenerator provides deps.keyGenerator,
        LocalSshCertificateInspector provides deps.certificateInspector,
        LocalTunnels provides deps.tunnels,
        // Saved snippets — Snippets tab (command library + run into the active terminal).
        LocalSnippets provides deps.snippets,
        LocalTerminalHistory provides termHistory,
        // Vault + biometrics — for the More screen's "unlock with biometrics" toggle (enable/reconfigure).
        LocalVault provides deps.vault,
        LocalVaultBiometrics provides deps.biometrics,
        LocalSecurityLog provides deps.securityLog,
        // Self-hosted sync coordinator — More → "Sync" push screen.
        LocalSync provides deps.sync,
        // Teams — More → "Team" push screen (sharing hosts/snippets between accounts).
        LocalTeams provides deps.teams,
    ) {
        Box(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
            val vault = deps.vault
            if (vault != null) {
                VaultGate(
                    vault = vault,
                    biometrics = deps.biometrics,
                    securityLog = deps.securityLog,
                    // Auto-lock threshold from settings: changing it recomposes VaultGate and restarts
                    // the idle timer; Never (idleMs == null) disables it.
                    autoLockIdleMs = state.autoLock.idleMs,
                    // Runs on EVERY lock, including the two automatic ones that bypass the lock
                    // action — Android had no teardown at all before (only onVaultReset did).
                    onBeforeLock = { tearDownForLock(deps.tunnels, liveSessions, deps.sync, deps.snippets) },
                    onReset = onVaultReset,
                    // onPairingComplete != null (sync present) — the create screen offers "I have a code":
                    // the coordinator creates the vault under the chosen password and accepts the account key.
                    createForm = { error, onCreate, onPairingComplete ->
                        MobileCreateScreen(error, onCreate, deps.sync, onPairingComplete)
                    },
                    unlockForm = { error, canBio, onUnlock, onBio, onForgot ->
                        MobileUnlockScreen(error, canBio, onUnlock, onBio, onForgot)
                    },
                    corruptedForm = { onReset -> MobileCorruptedScreen(onReset) },
                    resetForm = { onConfirm, onCancel -> MobileResetScreen(onConfirm, onCancel) },
                    // Sync onboarding step (BEFORE biometrics) — only if sync is wired into the graph.
                    // Connecting here accepts the account's dataKey, so biometrics wraps the final key.
                    offerSyncForm = deps.sync?.let { s -> { onDone -> SyncOnboardingScreen(s, onDone) } },
                    offerBiometricForm = { inFlight, onEnable, onSkip -> MobileBiometricOfferScreen(inFlight, onEnable, onSkip) },
                ) { onLock -> MobileChrome(state, onLock, liveSessions, deps.credentials, onVaultUnlocked, ai, updates) }
            } else {
                MobileChrome(state, onLock = null, sessions = liveSessions, credentials = deps.credentials, onVaultUnlocked = onVaultUnlocked, ai = ai, updates = updates)
            }
        }
    }
}

/**
 * Mobile layout shell: content (push screen or root tab) + bottom tab bar, visible only on root
 * screens ([MobileDesignState.showTabs]). [onLock] != null is the live path behind the gate
 * ("Lock Skerry" in More actually locks the vault).
 */
@Composable
private fun MobileChrome(
    state: MobileDesignState,
    onLock: (() -> Unit)?,
    sessions: SessionsController?,
    credentials: CredentialManagerController?,
    onVaultUnlocked: () -> Unit,
    ai: AiAssistantController?,
    updates: app.skerry.ui.update.UpdateNoticeController?,
) {
    // Keychain secrets live in the open vault — behind the master password gate, first fire
    // [onVaultUnlocked], then reload. [MobileChrome] composes only behind the gate and
    // re-enters composition on every unlock, so also reload AI settings here from the now-open vault
    // (BYOK key is a SETTINGS record; at locked startup the controller saw only the default). Edits
    // synced from another device are caught by a separate effect in MobileDesignApp.
    LaunchedEffect(credentials) {
        onVaultUnlocked()
        credentials?.reload()
        ai?.refresh()
        // Reload the update-check toggle from the now-open vault and start the daily check loop
        // (no network happens before this point).
        updates?.refresh()
    }

    // Host with no bound secret → ask for a password via a sheet before connecting. Along with the
    // host, remember the destination (terminal/files) so entering the password navigates there.
    var pending by remember { mutableStateOf<PendingConnect?>(null) }
    // VNC "ask every time": a host with no stored password prompts before opening the framebuffer screen.
    var pendingVnc by remember { mutableStateOf<Host?>(null) }

    // ProxyJump chain resolution failed for the tapped host — show a notice instead of connecting
    // (never silently direct). Desktop parity ([JumpErrorDialog]).
    var jumpProblem by remember { mutableStateOf<JumpChainProblem?>(null) }
    val hostManager = LocalHosts.current

    // Stable connect lambda (without remember it would be recreated and invalidate consumers of
    // [LocalConnectHost]/[LocalOpenSftp]). Reuse the host's live session; a dead/missing one is
    // reopened ([mobileConnectAction]): one session at a time on the phone, no accumulating sockets.
    // [dest] is where to go after connecting: Connect → terminal, SFTP → Files tab (same path,
    // including the password prompt, diverging only in the final navigation [navigateAfterConnect]).
    val connect = remember(sessions, credentials, hostManager, state) {
        { host: Host, dest: MobileConnectDest ->
            if (host.connectionType.isVnc) {
                // VNC opens a framebuffer screen (not a terminal). A stored password is used directly;
                // "ask every time" (no bound secret) prompts for one first.
                val cred = credentials?.find(host.credentialId)
                if (cred != null) {
                    sessions?.openVnc(
                        host.id, host.label, host.connectionSubtitle(), host.toTarget(), cred.toVncAuth(),
                        remoteResize = host.vncResizeToWindow,
                        onRemoteResizeChanged = { on -> hostManager?.setVncResizeToWindow(host.id, on) },
                    )
                    if (sessions != null) state.push(MobileRoute.Vnc)
                } else {
                    pendingVnc = host
                }
            } else {
                val existing = sessions?.sessions?.lastOrNull { it.hostId == host.id }
                when (mobileConnectAction(existing?.controller?.uiState)) {
                    MobileConnectAction.Resume -> {
                        existing?.let { sessions.activate(it.id) }
                        navigateAfterConnect(state, dest)
                    }
                    MobileConnectAction.OpenFresh -> {
                        existing?.let { sessions.close(it.id) }
                        // ProxyJump chain first — resolved before the password prompt so a broken
                        // chain surfaces immediately, not after the user typed a password.
                        when (val chain = resolveJumpChain(host, { id -> hostManager?.find(id) }, { id -> credentials?.find(id) })) {
                            is JumpChainResolution.Unavailable -> jumpProblem = chain.problem
                            is JumpChainResolution.Resolved -> {
                                // Single-level resolve: host → keychain secret by credentialId → SshAuth; no binding → password.
                                val credential = credentials?.find(host.credentialId)
                                when {
                                    // Telnet/Serial have no auth — connect immediately, no password
                                    // prompt. SSH and Mosh resolve a credential or ask for a password.
                                    !host.connectionType.usesSshAuth ->
                                        openMobileSession(sessions, state, host, SshAuth.Password(""), chain.jump, dest)
                                    credential != null ->
                                        openMobileSession(sessions, state, host, credential.toSshAuth(), chain.jump, dest)
                                    else -> pending = PendingConnect(host, dest, chain.jump)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // Derived stable lambdas for the two entry points: Connect (→ terminal) and SFTP (→ Files push screen).
    val connectHost = remember(connect) { { host: Host -> connect(host, MobileConnectDest.Terminal) } }
    val openSftp = remember(connect) { { host: Host -> connect(host, MobileConnectDest.Files) } }

    CompositionLocalProvider(
        LocalConnectHost provides connectHost,
        LocalOpenSftp provides openSftp,
        // Keychain of the open vault — needed by the "New connection" sheet to pick/create a secret (desktop parity).
        LocalCredentials provides credentials,
    ) {
        // System back/gesture drives the app's own stack instead of closing the Activity: close a
        // push screen (→ underlying tab), then leave a non-Hosts tab for Hosts. On the root Hosts
        // screen with no overlays, back is not intercepted — the system closes the app as usual. Open
        // sheets/dialogs consume their own back via their OWN BackHandler (they compose deeper/later
        // → intercept first per the dispatcher's LIFO), so while an overlay is open the navigation
        // intercept is kept disabled to avoid firing afterward. Registered before the content, making
        // it the lowest-priority handler in the back stack.
        val overlayOpen = pending != null || state.sheetNewConn || state.renamingGroup != null || state.modalOpen
        val backAction = if (overlayOpen) null else mobileBackAction(state.route, state.tab)
        PlatformBackHandler(enabled = backAction != null) {
            when (backAction) {
                MobileBackAction.PopRoute -> state.pop()
                MobileBackAction.GoHome -> state.select(MobileTab.Hosts)
                null -> {}
            }
        }
        // Read the keyboard inset BEFORE the root safeDrawing consumes it (inside the Box,
        // WindowInsets.ime is already 0). Needed to hide the bottom tab bar while typing: safeDrawing
        // lifts all content above the keyboard, and the tab bar (BottomCenter) would otherwise float
        // as a bar right above it.
        val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        // Session screens run full-bleed: they hide the system bars and use the whole display (a phone
        // has no pixels to spare for chrome, and in landscape the status bar sat on top of the remote
        // picture). Their own floating chrome keeps clear of the insets, and they handle the keyboard
        // inset themselves — see ImmersiveScreen / hiddenSystemBarsPadding.
        val fullBleed = state.route == MobileRoute.Vnc || state.route == MobileRoute.Terminal
        Box(Modifier.fillMaxSize().then(if (fullBleed) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))) {
            val route = state.route
            Box(Modifier.fillMaxSize()) {
                if (route != null) {
                    MobileRoutePane(state, route)
                } else {
                    MobileTabPane(state, onLock)
                }
            }
            if (state.showTabs && !keyboardVisible) {
                MobileTabBar(state, Modifier.align(Alignment.BottomCenter))
            }
            if (state.sheetNewConn) {
                MobileNewConnectionSheet(state)
            }
            // Confirmation for a snippet with ${{…}} variables — every launch path (terminal
            // palette, Snippets tab) parks such a run in SnippetManager.pendingRun. Desktop parity.
            LocalSnippets.current?.let { SnippetRunDialog(it) }
            // Recording player: an overlay over whatever screen is up, so a recording can be watched
            // from More without an open session (desktop toolbar parity).
            state.castRecording?.let { cast -> CastPlayerOverlay(cast, onDismiss = state::closeCast) }
            if (state.castInvalid) {
                NoticeDialog(
                    title = stringResource(Res.string.term_player_title),
                    message = stringResource(Res.string.term_player_invalid),
                    buttonLabel = stringResource(Res.string.term_ai_dismiss),
                    onDismiss = state::dismissCastError,
                )
            }
            // Pencil icon on a folder header → Rename/Delete group dialog. The controller edits
            // profiles (renameGroup/deleteGroup), the store syncs collapsed state. Desktop GroupDialog parity.
            state.renamingGroup?.let { groupName ->
                val hosts = LocalHosts.current
                MobileGroupRenameDialog(
                    initialName = groupName,
                    onDismiss = state::dismissRenameGroup,
                    onSave = { newName ->
                        hosts?.renameGroup(groupName, newName)
                        state.onGroupRenamed(groupName, newName)
                        state.dismissRenameGroup()
                    },
                    onDelete = {
                        hosts?.deleteGroup(groupName)
                        state.onGroupDeleted(groupName)
                        state.dismissRenameGroup()
                    },
                )
            }
            pending?.let { (host, dest, jump) ->
                MobilePasswordSheet(
                    host = host,
                    onDismiss = { pending = null },
                    onConnect = { pw -> pending = null; openMobileSession(sessions, state, host, SshAuth.Password(pw), jump, dest) },
                )
            }
            // VNC password prompt ("ask every time"): empty = server needs no password (None), else VNC-Auth.
            pendingVnc?.let { host ->
                MobilePasswordSheet(
                    host = host,
                    onDismiss = { pendingVnc = null },
                    onConnect = { pw ->
                        pendingVnc = null
                        val auth = if (pw.isEmpty()) app.skerry.shared.vnc.VncAuth.None else app.skerry.shared.vnc.VncAuth.Password(pw)
                        sessions?.openVnc(
                            host.id, host.label, host.connectionSubtitle(), host.toTarget(), auth,
                            remoteResize = host.vncResizeToWindow,
                            onRemoteResizeChanged = { on -> hostManager?.setVncResizeToWindow(host.id, on) },
                        )
                        if (sessions != null) state.push(MobileRoute.Vnc)
                    },
                )
            }
            // Broken ProxyJump chain for the tapped host: explain instead of connecting.
            jumpProblem?.let { problem ->
                JumpErrorDialog(problem, onDismiss = { jumpProblem = null })
            }
        }
    }
}

/**
 * Host waiting for a password, with the destination after connecting (terminal/files) and the
 * ProxyJump chain already resolved at tap time (a broken one never reaches the prompt).
 */
private data class PendingConnect(val host: Host, val dest: MobileConnectDest, val jump: SshJump? = null)

/** Open a session to [host] with [auth] (via the resolved [jump] chain, `null` — direct) and navigate to [dest]. */
private fun openMobileSession(
    sessions: SessionsController?,
    state: MobileDesignState,
    host: Host,
    auth: SshAuth,
    jump: SshJump?,
    dest: MobileConnectDest,
) {
    sessions?.open(
        hostId = host.id,
        title = host.label,
        subtitle = host.connectionSubtitle(),
        target = host.toTarget(jump),
        auth = auth,
    )
    navigateAfterConnect(state, dest)
}

// Content: root tabs and push screens.

/**
 * Root screen for the current tab. [onLock] is threaded into the More hub ("Lock Skerry").
 */
@Composable
private fun MobileTabPane(state: MobileDesignState, onLock: (() -> Unit)?) {
    when (state.tab) {
        MobileTab.Hosts -> MobileHostsScreen(state)
        MobileTab.Snippets -> MobileSnippetsScreen(state)
        MobileTab.Vault -> MobileVaultScreen(state)
        MobileTab.More -> MobileMoreScreen(state, onLock)
    }
}

/**
 * Full-screen push screen. [MobileRoute.HostDetail] opens [MobileHostDetailScreen]; the rest are
 * back arrow + title ([MobileRoutePlaceholder]), body not implemented.
 */
@Composable
private fun MobileRoutePane(state: MobileDesignState, route: MobileRoute) {
    when (route) {
        MobileRoute.HostDetail -> MobileHostDetailScreen(state)
        MobileRoute.Terminal -> MobileTerminalScreen(state)
        MobileRoute.Vnc -> MobileVncScreen(state)
        MobileRoute.Files -> MobileFilesScreen(onBack = state::pop)
        MobileRoute.Ports -> MobilePortsScreen(state)
        MobileRoute.Known -> MobileKnownScreen(state)
        MobileRoute.Team -> MobileTeamsScreen(state)
        MobileRoute.Appearance -> MobileAppearanceScreen(state)
        MobileRoute.Sync -> MobileSyncScreen(state)
        MobileRoute.Ai -> MobileAiScreen(state)
        MobileRoute.Security -> MobileSecurityScreen(state)
        MobileRoute.About -> MobileAboutScreen(state)
    }
}

/** Push screen placeholder: back arrow + title. */
@Composable
private fun MobileRoutePlaceholder(state: MobileDesignState, title: String) {
    Column(Modifier.fillMaxSize()) {
        MobilePushHeader(title, onBack = state::pop)
    }
}
