package app.skerry.ui.mobile

import app.skerry.ui.remote.RemoteDesktopController
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import app.skerry.shared.ai.AiSettingsStore
import app.skerry.ui.ai.aiProviderFactory
import app.skerry.ui.AppDependencies
import app.skerry.ui.ai.AiAssistantController
import app.skerry.shared.terminal.VaultTerminalHistoryStore
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.session.SessionsController
import app.skerry.ui.sync.SyncStatus
import app.skerry.ui.sync.SyncOnboardingScreen
import app.skerry.ui.terminal.LocalTerminalAppearance
import app.skerry.ui.terminal.LocalTerminalHighlight
import app.skerry.ui.terminal.TerminalHighlight
import app.skerry.ui.terminal.LocalTerminalTheme
import app.skerry.ui.terminal.TerminalAppearance
import app.skerry.ui.terminal.TerminalSessionPrefs
import app.skerry.ui.vault.ResetScope
import app.skerry.ui.vault.VaultGate
import app.skerry.ui.vault.tearDownForLock
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.app.FeatureFlags
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalFeatures
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalKnownHosts
import app.skerry.ui.app.LocalTrustedCas
import app.skerry.ui.app.LocalTestTransport
import app.skerry.ui.app.LocalSecurityLog
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalRunbookHistory
import app.skerry.ui.app.LocalRunbookRunner
import app.skerry.ui.app.LocalRunbooks
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.LocalTerminalHistory
import app.skerry.ui.app.LocalSecretFileReader
import app.skerry.ui.app.LocalSshCertificateInspector
import app.skerry.ui.app.LocalSshKeyGenerator
import app.skerry.ui.app.LocalSync
import app.skerry.ui.app.LocalTeams
import app.skerry.ui.app.LocalTunnels
import app.skerry.ui.app.LocalUpdates
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.app.MobileDesignState
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
    // Keyboard-interactive prompts (2FA codes a server asks for mid-connect); null in preview.
    keyboardInteractive: app.skerry.ui.connection.KeyboardInteractivePromptController? = null,
    state: MobileDesignState = remember { MobileDesignState() },
    features: FeatureFlags = FeatureFlags(),
    sessions: SessionsController? = null,
    // Process-scoped coroutine scope for sessions (Android: survives Activity recreation, so
    // backgrounding the app — Activity may be recycled — keeps connections and the keep-alive
    // service alive; tap a notification to come back to the same live terminal). Null falls back
    // to the composition scope (desktop/preview/offscreen behavior).
    processScope: CoroutineScope? = null,
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
    val scope = processScope ?: rememberCoroutineScope()
    // Per-host terminal command history over the encrypted vault: autocomplete writes it, the
    // command palette reads every host's. Hoisted out of the sessions factory so both can see it.
    val termHistory = remember(deps.vault) { deps.vault?.let { VaultTerminalHistoryStore(it) } }
    val liveSessions = sessions ?: remember(deps.transport, scope, deps.vault, deps.teams) {
        deps.transport?.let { t ->
            var counter = 0
            SessionsController(
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
    // queue, so no race. Mobile has no panes of its own, but a tab's panes are pushed to as well.
    val cursorStyle = state.terminalCursorStyle
    LaunchedEffect(cursorStyle, liveSessions) {
        val manager = liveSessions ?: return@LaunchedEffect
        manager.allSessions.forEach { it.liveTerminal?.applyCursorStyle(cursorStyle.shape, cursorStyle.blink) }
    }
    // A scrollback change likewise applies to ALREADY open sessions live: shrinking trims old history,
    // growing keeps new lines around longer. New sessions pick up the value at connect via terminalPrefs.
    val scrollbackLines = TerminalSessionPrefs(scrollback = state.terminalScrollback).effectiveScrollback
    LaunchedEffect(scrollbackLines, liveSessions) {
        val manager = liveSessions ?: return@LaunchedEffect
        manager.allSessions.forEach { it.liveTerminal?.applyScrollback(scrollbackLines) }
    }
    // Desktop parity: the Teams session-report gate reads the live setting (see DesktopDesignApp).
    LaunchedEffect(deps.teams, state) { deps.teams?.reportSessionsEnabled = { state.reportTeamSessions } }
    // Toggling the OSC 52 clipboard-write gate applies to ALREADY open sessions live; new sessions
    // pick the value up at connect via terminalPrefs above.
    val allowClipboardWrite = state.allowServerClipboardWrite
    LaunchedEffect(allowClipboardWrite, liveSessions) {
        val manager = liveSessions ?: return@LaunchedEffect
        manager.allSessions.forEach { it.liveTerminal?.applyClipboardWriteEnabled(allowClipboardWrite) }
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
        app.skerry.ui.app.LocalKeyboardInteractive provides keyboardInteractive,
        LocalFonts provides fonts,
        // Terminal appearance from settings (More → Appearance): font + size read by TerminalScreen.
        LocalTerminalAppearance provides terminalAppearance,
        // Client-side syntax highlighting (More → Terminal), read by TerminalScreen's draw layer.
        LocalTerminalHighlight provides TerminalHighlight(
            commandLine = state.highlightCommandLine,
            output = state.highlightOutput,
        ),
        // Terminal color theme: the app theme's twin, or the separately-picked one (More → Appearance → cards).
        LocalTerminalTheme provides effectiveTerminalTheme,
        LocalHosts provides deps.hosts,
        LocalSessions provides liveSessions,
        LocalKnownHosts provides deps.knownHosts,
        LocalTrustedCas provides deps.trustedCas,
        // Read-only probe transport for form-side checks (container listing): never adds a host key.
        LocalTestTransport provides deps.probeTransport,
        LocalFeatures provides features,
        // AI assistant (BYOK): More→AI settings tab, per-host policies, terminal AI bar.
        LocalAi provides ai,
        // Update notice: More → About push screen (toggle + release link).
        LocalUpdates provides updates,
        // SSH key inspector/generator + certificate inspector — Vault tab: fingerprints, generation, cert parsing.
        LocalSshKeyGenerator provides deps.keyGenerator,
        LocalSshCertificateInspector provides deps.certificateInspector,
        LocalSecretFileReader provides deps.secretFiles,
        // Playback devices for an RDP profile that plays the session's sound on this device.
        app.skerry.ui.app.LocalAudioOutputs provides deps.audioOutputs,
        LocalTunnels provides deps.tunnels,
        // Saved snippets — Snippets tab (command library + run into the active terminal).
        LocalSnippets provides deps.snippets,
        LocalRunbooks provides deps.runbooks,
        LocalRunbookRunner provides deps.runbookRunner,
        LocalRunbookHistory provides deps.runbookHistory,
        LocalTerminalHistory provides termHistory,
        // Vault + biometrics — for the More screen's "unlock with biometrics" toggle (enable/reconfigure).
        LocalVault provides deps.vault,
        LocalVaultBiometrics provides deps.biometrics,
        LocalSecurityLog provides deps.securityLog,
        // Self-hosted sync coordinator — More → "Sync" push screen.
        LocalSync provides deps.sync,
        // Teams — More → "Team" push screen (sharing hosts/snippets between accounts).
        LocalTeams provides deps.teams,
        app.skerry.ui.app.LocalSessionShare provides deps.sessionShare,
        app.skerry.ui.app.LocalSharedSessions provides deps.sharedSessions,
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
                    onBeforeLock = { tearDownForLock(deps.tunnels, liveSessions, deps.sync, deps.snippets, deps.runbookRunner, keyboardInteractive) },
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
