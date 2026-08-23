package app.skerry.ui.mobile

import app.skerry.ui.connection.toRdpPassword
import app.skerry.shared.ssh.isRdp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.usesSshAuth
import app.skerry.shared.ssh.isVnc
import app.skerry.ui.ai.AiAssistantController
import app.skerry.ui.connection.JumpChainProblem
import app.skerry.ui.connection.JumpChainResolution
import app.skerry.ui.connection.JumpErrorDialog
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.connection.resolveJumpChain
import app.skerry.ui.connection.toSshAuth
import app.skerry.ui.connection.toTarget
import app.skerry.ui.connection.toVncAuth
import app.skerry.ui.connection.connectableSecrets
import app.skerry.ui.host.rowLabel
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.nav.PlatformBackHandler
import app.skerry.ui.session.SessionsController
import app.skerry.ui.design.NoticeDialog
import app.skerry.ui.generated.resources.term_ai_dismiss
import app.skerry.ui.generated.resources.term_player_invalid
import app.skerry.ui.generated.resources.term_player_title
import app.skerry.ui.terminal.CastPlayerOverlay
import app.skerry.ui.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.app.LocalConnectHost
import app.skerry.ui.app.LocalShowTerminal
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalOpenSftp
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalRunbookRunner
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.runbook.RunbookRunPanel
import app.skerry.ui.runbook.RunbookPauseAnnouncer
import app.skerry.ui.runbook.RunbookStartDialog
import app.skerry.ui.runbook.runInActiveTab
import app.skerry.ui.snippet.SnippetRunDialog
import app.skerry.ui.app.MobileBackAction
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.app.MobileTab
import app.skerry.ui.app.mobileBackAction
import app.skerry.ui.app.mobileSessionFullBleed
import app.skerry.ui.app.mobileTabBarUnderRoute
import app.skerry.ui.theme.Skerry
import app.skerry.ui.host.ProdConnectDialog
import app.skerry.ui.host.ProdConnectRequest
import app.skerry.ui.host.ProdCommandGate
import app.skerry.ui.host.ProdGuardSync
import app.skerry.ui.host.isProdHost

/**
 * Mobile layout shell: content (push screen or root tab) + bottom tab bar, visible only on root
 * screens ([MobileDesignState.showTabs]). [onLock] != null is the live path behind the gate
 * ("Lock Skerry" in More actually locks the vault).
 */
@Composable
internal fun MobileChrome(
    state: MobileDesignState,
    onLock: (() -> Unit)?,
    sessions: SessionsController?,
    credentials: CredentialManagerController?,
    onVaultUnlocked: () -> Unit,
    ai: AiAssistantController?,
    updates: app.skerry.ui.update.UpdateNoticeController?,
) {
    // The stable holder, not `.containerSize`: a body read would recompose the whole chrome on
    // every container change (IME, rotation, split screen). The size is read when an RDP session
    // is dialled — the desktop is requested at that size instead of a hardcoded 1280×720 (F-06).
    val windowInfo = LocalWindowInfo.current
    // The size above is physical pixels; this is how large one of them is, and it travels with the
    // size so an RDP session comes up at this screen's DPI (see [RdpDisplayScale]).
    val displayScale = LocalDensity.current.density
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
    var pendingRdp by remember { mutableStateOf<Host?>(null) }
    // Production guard: a session about to open on a #prod host waits here for confirmation
    // (desktop parity — the gate wraps every connect path, terminal / files / VNC).
    var prodConnect by remember { mutableStateOf<ProdConnectRequest?>(null) }

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
            // Production guard: opening a session on a #prod host confirms first. Returning to a
            // session that is already live is NOT gated — the confirmation belongs to opening the
            // connection, and asking on every tab switch would train the user to tap through it.
            val live = sessions?.tabs?.lastOrNull { it.focusedPane.hostId == host.id }
            // Decided here, before the question is asked, and carried into [open] — re-reading the
            // session list on OK would connect against a state the user was never shown.
            val planned = mobileConnectAction(live?.focusedPane?.controller?.uiState)
            val liveId = live?.id
            val confirmProd = mobileProdConfirmNeeded(
                production = isProdHost(host),
                isVnc = host.connectionType.isVnc,
                action = planned,
            )
            val open = {
                if (host.connectionType.isRdp) {
                    // Same rule as the desktop: a stored password connects straight away; a profile
                    // with no user name at all (what an imported `.rdp` file usually is) has nothing
                    // to prompt for and opens its form; anything else asks. A team-shared profile
                    // arrives with its credential link stripped and has no other way in.
                    val password = credentials?.useForConnect(host.credentialId)?.toRdpPassword()
                    when {
                        password != null -> openMobileRdp(sessions, state, hostManager, host, password, windowInfo.containerSize, displayScale)
                        host.username.isBlank() -> state.openEditConn(host)
                        else -> pendingRdp = host
                    }
                    Unit
                } else if (host.connectionType.isVnc) {
                    // VNC opens a framebuffer screen (not a terminal). A stored password is used directly;
                    // "ask every time" (no bound secret) prompts for one first.
                    val cred = credentials?.useForConnect(host.credentialId)
                    if (cred != null) {
                        sessions?.openVnc(
                            host.id, host.rowLabel(), host.connectionSubtitle(), host.toTarget(), cred.toVncAuth(),
                            remoteResize = host.vncResizeToWindow,
                            onRemoteResizeChanged = { on -> hostManager?.setVncResizeToWindow(host.id, on) },
                            quality = host.vncQuality,
                            onQualityChanged = { q -> hostManager?.setVncQuality(host.id, q) },
                        )
                        if (sessions != null) state.push(MobileRoute.Vnc)
                    } else {
                        pendingVnc = host
                    }
                } else {
                    // The session the decision was made about, looked up by the id captured with it.
                    // Only its survival is re-checked ([mobileResolvedAction]) — a session that died
                    // behind the confirmation has nothing to resume onto.
                    val existing = liveId?.let { id -> sessions?.tab(id) }
                    when (mobileResolvedAction(planned, stillLive = existing != null)) {
                        MobileConnectAction.Resume -> {
                            existing?.let { sessions.activate(it.id) }
                            navigateAfterConnect(state, dest)
                        }
                        MobileConnectAction.OpenFresh -> {
                            existing?.let { sessions.close(it.id) }
                            // ProxyJump chain first — resolved before the password prompt so a broken
                            // chain surfaces immediately, not after the user typed a password.
                            // Jump hops go through useForConnect too — see the desktop shell.
                            when (val chain = resolveJumpChain(host, { id -> hostManager?.find(id) }, { id -> credentials?.useForConnect(id) })) {
                                is JumpChainResolution.Unavailable -> jumpProblem = chain.problem
                                is JumpChainResolution.Resolved -> {
                                    // Single-level resolve: host → keychain secret by credentialId → SshAuth; no binding → password.
                                    // useForConnect stamps "last used" on the secret, as the desktop path does.
                                    val credential = credentials?.useForConnect(host.credentialId)
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
            if (confirmProd) prodConnect = ProdConnectRequest(host, proceed = open) else open()
        }
    }
    // Derived stable lambdas for the two entry points: Connect (→ terminal) and SFTP (→ Files push screen).
    val connectHost = remember(connect) { { host: Host -> connect(host, MobileConnectDest.Terminal) } }
    val openSftp = remember(connect) { { host: Host -> connect(host, MobileConnectDest.Files) } }

    // Where a session opened from another screen lands (joining a share from Teams): the same
    // push-screen Connect navigates to.
    val showTerminal = remember(state) { { navigateAfterConnect(state, MobileConnectDest.Terminal) } }

    CompositionLocalProvider(
        LocalConnectHost provides connectHost,
        LocalOpenSftp provides openSftp,
        LocalShowTerminal provides showTerminal,
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
        val overlayOpen = pending != null || state.sheetNewConn || state.renamingGroup != null || state.modalOpen || state.sshImport != null || state.rdpImport != null
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
        // Session screens run full-bleed only when the user asked for it (More → Appearance →
        // Interface): then they hide the system bars and use the whole display, their own floating
        // chrome keeps clear of the insets, and they handle the keyboard inset themselves — see
        // ImmersiveScreen / hiddenSystemBarsPadding. With the setting off (the default) the bars
        // stay up and this screen stays inside the safe area, or terminal output would run under
        // the phone's clock.
        val fullBleed = mobileSessionFullBleed(state.hideSessionSystemBars, state.route)
        Box(Modifier.fillMaxSize().then(if (fullBleed) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))) {
            val route = state.route
            // The terminal keeps the bar ([mobileRouteKeepsTabBar]) and gets it as a sibling below
            // the screen, not floating over it: a translucent strip works over a scrolling list, but
            // over terminal output it would hide the last lines. Root tabs keep the overlay they
            // were laid out for (their content reserves the space at the end).
            val tabsUnderRoute = mobileTabBarUnderRoute(route, state.modalOpen, keyboardVisible)
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (route != null) {
                        MobileRoutePane(state, route)
                    } else {
                        MobileTabPane(state, onLock)
                    }
                }
                if (tabsUnderRoute) MobileTabBar(state)
            }
            if (state.showTabs && !keyboardVisible) {
                MobileTabBar(state, Modifier.align(Alignment.BottomCenter))
            }
            if (state.sheetNewConn) {
                MobileNewConnectionSheet(state)
            }
            state.sshImport?.let { MobileSshImportSheet(state, it) }
            state.rdpImport?.let { MobileRdpImportSheet(state, it) }
            // Confirmation for a snippet with ${{…}} variables — every launch path (terminal
            // palette, Snippets tab) parks such a run in SnippetManager.pendingRun. Desktop parity.
            LocalSnippets.current?.let { SnippetRunDialog(it) }
            // Runbooks: the live progress panel (bottom of the terminal screen, non-modal and
            // collapsible to its header — on a phone the full panel covers the live output it
            // exists to keep readable) and the start confirmation above it. Desktop parity.
            LocalRunbookRunner.current?.let { runner ->
                // Not gated on the terminal route: a run paused on a confirmation would otherwise
                // lose its only Run/Skip/Stop buttons the moment the user opened another screen,
                // and nothing would say a procedure is half-finished.
                val run = runner.runInActiveTab(LocalSessions.current)
                if (run != null) {
                    RunbookRunPanel(
                        runner,
                        run,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp),
                    )
                }
                RunbookStartDialog(runner)
                // Composed here — before any run exists — so the announcer observes the flip into a
                // pause instead of appearing together with it (see RunbookPauseAnnouncer).
                RunbookPauseAnnouncer(runner)
            }
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
            // A server asking for a second factor mid-connect: above the sheets, since the connection
            // is blocked on this answer and it can arrive over any of them.
            app.skerry.ui.connection.KeyboardInteractiveHost(
                app.skerry.ui.app.LocalKeyboardInteractive.current,
            )
            // The host-trust question, above the sheets for the same reason.
            app.skerry.ui.trust.HostTrustHost(app.skerry.ui.app.LocalHostTrust.current)
            pending?.let { (host, dest, jump) ->
                MobilePasswordSheet(
                    host = host,
                    onDismiss = { pending = null },
                    onConnect = { pw -> pending = null; openMobileSession(sessions, state, host, SshAuth.Password(pw), jump, dest) },
                    // Desktop parity: only a team-shared host is offered our keychain (it keeps no
                    // credential link), a profile of our own gets the password field alone.
                    secrets = connectableSecrets(credentials?.credentials.orEmpty(), host, hostManager?.hosts.orEmpty()),
                    onUseSecret = { secret ->
                        pending = null
                        openMobileSession(sessions, state, host, secret.toSshAuth(), jump, dest)
                    },
                )
            }
            // VNC password prompt ("ask every time"): empty = server needs no password (None), else VNC-Auth.
            pendingVnc?.let { host ->
                MobilePasswordSheet(
                    host = host,
                    onDismiss = { pendingVnc = null },
                    onConnect = { pw ->
                        pendingVnc = null
                        openMobileVnc(sessions, state, hostManager, host, if (pw.isEmpty()) app.skerry.shared.vnc.VncAuth.None else app.skerry.shared.vnc.VncAuth.Password(pw))
                    },
                    secrets = connectableSecrets(credentials?.credentials.orEmpty(), host, hostManager?.hosts.orEmpty()),
                    onUseSecret = { secret ->
                        pendingVnc = null
                        openMobileVnc(sessions, state, hostManager, host, secret.toVncAuth())
                    },
                )
            }
            // RDP password prompt: "ask every time", or a team-shared profile that arrived without
            // a secret. Unlike VNC there is no anonymous mode, so an empty answer connects as one.
            pendingRdp?.let { host ->
                MobilePasswordSheet(
                    host = host,
                    onDismiss = { pendingRdp = null },
                    onConnect = { pw ->
                        pendingRdp = null
                        openMobileRdp(sessions, state, hostManager, host, pw, windowInfo.containerSize, displayScale)
                    },
                    secrets = connectableSecrets(credentials?.credentials.orEmpty(), host, hostManager?.hosts.orEmpty()),
                    onUseSecret = { secret ->
                        // A secret an RDP logon cannot use leaves the sheet where it is rather than
                        // closing it on a tap that opens nothing.
                        val password = secret.toRdpPassword()
                        if (password != null) {
                            pendingRdp = null
                            openMobileRdp(sessions, state, hostManager, host, password, windowInfo.containerSize, displayScale)
                        }
                    },
                )
            }
            // Production guard: confirm before a #prod session opens (ahead of the password sheet —
            // the question is whether to touch production at all).
            prodConnect?.let { request ->
                ProdConnectDialog(request, onDismiss = { prodConnect = null })
            }
            // Inside a session: keep it armed from the host tags and confirm the commands it holds.
            ProdGuardSync(sessions, state.confirmProductionWarnings)
            ProdCommandGate(sessions?.active)
            // Broken ProxyJump chain for the tapped host: explain instead of connecting.
            jumpProblem?.let { problem ->
                JumpErrorDialog(problem, onDismiss = { jumpProblem = null })
            }
        }
    }
}
