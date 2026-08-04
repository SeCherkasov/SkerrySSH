package app.skerry.ui.desktop

import app.skerry.ui.connection.toRdpPassword
import app.skerry.shared.ssh.isRdp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.vault.Vault
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.connection.JumpChainProblem
import app.skerry.ui.connection.JumpChainResolution
import app.skerry.ui.connection.JumpErrorDialog
import app.skerry.ui.connection.resolveJumpChain
import app.skerry.shared.ssh.isVnc
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.connection.toTarget
import app.skerry.ui.connection.toVncAuth
import app.skerry.ui.connection.toSshAuth
import app.skerry.ui.app.CustomGroup
import app.skerry.ui.app.GroupDialog
import app.skerry.ui.host.GroupDialog as GroupEditDialog
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.session.BroadcastPanel
import app.skerry.ui.session.SessionView
import app.skerry.ui.session.broadcastTargets
import app.skerry.ui.session.PaneSyncBinder
import app.skerry.ui.session.SessionsController
import app.skerry.ui.runbook.RunbookStartDialog
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.snippet.SnippetRunDialog
import app.skerry.ui.terminal.CommandPalette
import app.skerry.ui.terminal.CastPlayerOverlay
import app.skerry.ui.terminal.recordingOutcomeMessage
import app.skerry.ui.vault.VaultGate
import app.skerry.ui.vault.tearDownForLock
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_this_session
import app.skerry.ui.generated.resources.term_ai_dismiss
import app.skerry.ui.generated.resources.term_player_invalid
import app.skerry.ui.generated.resources.term_player_title
import app.skerry.ui.generated.resources.term_record_start
import app.skerry.ui.generated.resources.shell_disconnect_all_message
import app.skerry.ui.generated.resources.shell_disconnect_all_title
import app.skerry.ui.generated.resources.shell_disconnect_title
import app.skerry.ui.generated.resources.shell_disconnect_message
import app.skerry.ui.generated.resources.shell_disconnect
import app.skerry.ui.generated.resources.shell_close_pane_title
import app.skerry.ui.generated.resources.shell_close_pane_message
import app.skerry.ui.generated.resources.shell_connect
import app.skerry.ui.generated.resources.shell_replace_pane_title
import app.skerry.ui.generated.resources.shell_close_panel
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.ConfirmActionDialog
import app.skerry.ui.host.DesktopDeleteHostDialog
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.connection.DesktopPasswordDialog
import app.skerry.ui.connection.connectableSecrets
import app.skerry.ui.app.DesktopView
import app.skerry.ui.design.NoticeDialog
import app.skerry.ui.app.LocalConnectHost
import app.skerry.ui.app.LocalShowTerminal
import app.skerry.ui.app.LocalConnectPane
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalHostClickConnectMode
import app.skerry.ui.app.LocalRunSnippetOnHost
import app.skerry.ui.app.LocalRunbookRunner
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.LocalTerminalHistory
import app.skerry.ui.app.LocalSync
import app.skerry.ui.vault.LockScreen
import app.skerry.ui.host.NewConnectionModal
import app.skerry.ui.host.SshConfigImportModal
import app.skerry.ui.sync.PairingShowDialog
import app.skerry.ui.app.PendingClose
import app.skerry.ui.app.remoteChromeHidden
import app.skerry.ui.settings.SettingsPanel
import app.skerry.ui.sync.SyncSetupDialog
import app.skerry.ui.i18n.label
import app.skerry.ui.host.HostSection
import app.skerry.ui.theme.Skerry
import app.skerry.ui.host.ProdConnectDialog
import app.skerry.ui.host.ProdConnectRequest
import app.skerry.ui.host.prodConnectGate
import app.skerry.ui.host.ProdCommandGate
import app.skerry.ui.host.prodGuardDialogOpen
import app.skerry.ui.host.ProdGuardSync
import app.skerry.ui.host.rememberProductionLookup

/**
 * The main chrome (titlebar → rail+viewport → statusbar) and overlays. [onLock] != null —
 * the live path behind the gate: the "Unlocked" chip locks the vault. null — the mock path:
 * locking is drawn by the stub [LockScreen] driven by [DesktopDesignState.locked].
 */
@Composable
internal fun DesktopChrome(
    state: DesktopDesignState,
    onLock: (() -> Unit)?,
    sessions: SessionsController?,
    credentials: CredentialManagerController?,
    onVaultUnlocked: () -> Unit,
    customGroupsProvider: () -> List<CustomGroup>,
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
    // three paths ([PendingAuth]): new tab / pane (the target pane is fixed at the moment the host is
    // chosen, not at submit — otherwise switching tabs while typing the password would connect in the
    // wrong place) / snippet's "Run on host" (also remembers the command).
    var pendingAuth by remember { mutableStateOf<PendingAuth?>(null) }
    // VNC "ask every time": a host with no stored password prompts before opening the framebuffer tab.
    var pendingVncHost by remember { mutableStateOf<Host?>(null) }
    // Same for RDP, for the profiles that carry a user name but no stored password (an imported
    // `.rdp` file never carries credentials).
    var pendingRdpHost by remember { mutableStateOf<Host?>(null) }

    // Production guard: a connection to a #prod host is held here until confirmed. It wraps ALL
    // connect paths (new tab / pane / VNC / snippet), so the confirmation can't be walked around by
    // taking another route to the same host.
    var prodConnect by remember { mutableStateOf<ProdConnectRequest?>(null) }

    // ProxyJump chain resolution failed for the clicked host — connecting would either dial
    // forever or silently go direct, so a notice is shown instead ([JumpErrorDialog]).
    var jumpProblem by remember { mutableStateOf<JumpChainProblem?>(null) }
    val hostManager = LocalHosts.current

    // Single connect dispatcher with resolved auth already in hand: where the session goes is decided
    // by [PendingAuth]'s type. The ProxyJump chain is resolved here — right before the session
    // opens — so a password prompt in between can't act on a stale chain.
    fun openResolved(target: PendingAuth, auth: SshAuth) {
        val jump = when (
            // A jump hop authenticates with its own secret — resolved through useForConnect so the
            // key of a host reached only as a bastion doesn't read as "never used".
            val chain = resolveJumpChain(target.host, { id -> hostManager?.find(id) }, { id -> credentials?.useForConnect(id) })
        ) {
            is JumpChainResolution.Unavailable -> { jumpProblem = chain.problem; return }
            is JumpChainResolution.Resolved -> chain.jump
        }
        when (target) {
            is PendingAuth.NewTab -> openHostSession(sessions, state, target.host, auth, jump)
            is PendingAuth.Pane -> openPaneSession(sessions, state, target.tabId, target.paneId, target.host, auth, jump)
            is PendingAuth.Snippet ->
                openHostSession(sessions, state, target.host, auth, jump) { it.send(target.line) }
        }
    }

    // Opens an RDP tab with the password in hand — from the profile's secret or from the prompt.
    fun openRdpWith(host: Host, password: String) {
        state.recordRecentHost(host.id)
        sessions?.openRdp(
            host.id,
            host.label,
            host.connectionSubtitle(),
            app.skerry.ui.remote.RdpConnectRequest(
                host = host.address,
                port = host.port,
                username = host.username,
                password = password,
                width = RDP_DEFAULT_WIDTH,
                height = RDP_DEFAULT_HEIGHT,
                clientName = RDP_CLIENT_NAME,
                loadBalanceInfo = host.rdp?.loadBalanceInfo.orEmpty(),
                audioOutput = host.rdp?.audioOutput == true,
                audioDeviceId = host.rdp?.audioOutputDeviceId.orEmpty(),
                clipboard = host.rdp?.clipboard != false,
                imageQuality = host.rdp?.quality ?: app.skerry.shared.rdp.RdpImageQuality.DEFAULT,
            ),
            remoteResize = host.vncResizeToWindow,
            onRemoteResizeChanged = { on -> hostManager?.setVncResizeToWindow(host.id, on) },
        )
        state.showSection(HostSection.RemoteDesktops)
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
            prodConnect = prodConnectGate(host) {
                if (host.connectionType.isRdp) {
                    // RDP logs on as a Windows user: the profile carries the name (optionally
                    // `DOMAIN\\user`) and the vault the password. A stored password connects
                    // straight away; without one the prompt asks for it, exactly as VNC does. A
                    // profile with no user name at all (what an imported `.rdp` file usually is)
                    // has nothing to prompt for, so its form opens instead.
                    val password = credentials?.useForConnect(host.credentialId)?.toRdpPassword()
                    when {
                        password != null -> openRdpWith(host, password)
                        host.username.isBlank() -> state.openEditModal(host)
                        else -> pendingRdpHost = host
                    }
                    Unit
                } else if (host.connectionType.isVnc) {
                    // VNC opens a framebuffer tab (not a terminal). A stored password is used directly;
                    // "ask every time" (no bound secret) prompts for one first. No ProxyJump/host-key path.
                    val cred = credentials?.useForConnect(host.credentialId)
                    if (cred != null) {
                        state.recordRecentHost(host.id)
                        sessions?.openVnc(
                            host.id, host.label, host.connectionSubtitle(), host.toTarget(), cred.toVncAuth(),
                            remoteResize = host.vncResizeToWindow,
                            onRemoteResizeChanged = { on -> hostManager?.setVncResizeToWindow(host.id, on) },
                        )
                        state.showSection(HostSection.RemoteDesktops)
                    } else {
                        pendingVncHost = host
                    }
                    Unit
                } else {
                    connectOrAsk(PendingAuth.NewTab(host))
                }
            }
        }
    }

    // Snippet's "Run on host": open a session to the host and send the already-resolved command
    // line (newline included) once connected. Dynamic variables were confirmed before this point —
    // SnippetManager.run parks them in the dialog and only then hands the line over.
    val runSnippetOnHost = remember(sessions, credentials, hostManager, state) {
        { host: Host, line: String ->
            // The line goes into the confirmation: it runs the moment the session opens, before the
            // session's own guard is bound to it, so this dialog is where it has to be read.
            prodConnect = prodConnectGate(host, snippetLine = line) { connectOrAsk(PendingAuth.Snippet(host, line)) }
        }
    }

    // Same resolution, but into a pane of the active tab (its own independent session).
    val connectPaneHost = remember(sessions, credentials, hostManager, state) {
        { host: Host, paneId: String ->
            prodConnect = prodConnectGate(host) { connectOrAsk(PendingAuth.Pane(host, sessions?.activeId, paneId)) }
        }
    }

    // Pending password-prompt dialogs are dismissed on lock (don't leave password entry sitting under
    // the lock screen). Stabilized like onRootKey below: the lambda flows into TitleBar and
    // lockAction, and without remember a new instance on every recomposition would force them to
    // recompute for nothing.
    // Only the UI part of locking lives here. Tearing down what holds the secret is
    // [tearDownForLock], handed to VaultGate as onBeforeLock: the background and idle auto-locks
    // never reach this lambda, so anything security-relevant put here would be skipped by them.
    val onLockWithTunnels: (() -> Unit)? = if (onLock == null) null else remember(onLock, state) {
        {
            pendingAuth = null
            pendingVncHost = null
            pendingRdpHost = null
            // Same for a held-back production connect: after unlock it must be re-initiated, not
            // waiting behind the lock screen with its "Connect" button armed.
            prodConnect = null
            // Also dismiss any pending disconnect/close confirmation — after unlock an action needs a
            // fresh user intent (like pendingAuth), not to "resurface" on its own.
            state.dismissClose()
            onLock()
        }
    }

    // Same navigation step [openHostSession] takes after connecting, for sessions opened from
    // another screen (joining a share from Teams). Stable, like the connect lambdas above.
    val showTerminal = remember(state, sessions) {
        { if (sessions != null) state.showSection(HostSection.Terminal) else state.showView(DesktopView.Terminal) }
    }

    CompositionLocalProvider(
        LocalConnectHost provides connectHost,
        LocalConnectPane provides connectPaneHost,
        LocalRunSnippetOnHost provides runSnippetOnHost,
        LocalCredentials provides credentials,
        LocalHostClickConnectMode provides state.settings.hostClickConnectMode,
        LocalShowTerminal provides showTerminal,
    ) {
        // Global snippet hotkey: preview events flow from the root down to focus, so the root Box
        // intercepts the chord before the terminal does. If a saved shortcut matches and there's a
        // connected session, run the command in its terminal and consume the event. GATE: only fires
        // when a live session is on screen (no app overlay/modal/settings) — otherwise a chord typed
        // into the snippet editor's fields (Command/ShortcutField) or New connection would go to the
        // terminal as a command.
        val snippets = LocalSnippets.current
        // Live lock on the live path (teardown itself runs in VaultGate); state.lock is mock/preview.
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
                    state.sshImportOpen ||
                    state.rdpImportOpen ||
                    state.commandPaletteOpen || state.broadcastOpen || state.castRecording != null ||
                    // The snippet-variable confirmation dialog is modal too: a hotkey firing over it
                    // would type into the terminal under the dialog or race the pending run.
                    snippets?.pendingRun != null ||
                    // Same for both production-guard dialogs. This handler runs on the root's
                    // preview pass, above the focus their scrim takes, so a snippet chord would
                    // otherwise reach the production shell while the user is reading the question.
                    prodConnect != null || prodGuardDialogOpen(sessions?.active)
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
        // Full-window remote desktop: the picture takes the window, chrome and all — see [DesktopShell].
        val bareDesktop = remoteChromeHidden(
            immersive = state.remoteImmersive,
            desktopSession = sessions?.activeDesktop != null,
            overlayOpen = state.appOverlay != null,
        )
        Box(Modifier.fillMaxSize().background(Skerry.colors.bg).onPreviewKeyEvent(onRootKey)) {
            DesktopShell(state, onLockWithTunnels, windowChrome, bare = bareDesktop)
            // Mock/preview only: with live sessions a recording opens in its own tab (SessionView.Player),
            // and this state is never set. Esc (via ModalScrim) closes the overlay.
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
                    targets = broadcastTargets(sessions, rememberProductionLookup()),
                    onDismiss = state::closeBroadcast,
                )
            }
            if (state.commandPaletteOpen) {
                // The palette fills the command line of the pane in focus, so it reads that pane's
                // own history key.
                val palettePane = sessions?.active?.focusedPane
                val liveTerminal = (palettePane?.controller?.uiState as? ConnectionUiState.Connected)?.terminal
                CommandPalette(
                    history = termHistory,
                    currentKey = palettePane?.controller?.historyKey,
                    onPick = { command ->
                        liveTerminal?.applyHistoryCommand(command)
                        state.closeCommandPalette()
                    },
                    onDismiss = state::closeCommandPalette,
                )
            }
            if (state.modalOpen) NewConnectionModal(state, editHost = state.editingHost, duplicateOf = state.duplicatingHost)
            state.sshImport?.let { SshConfigImportModal(state, it) }
            state.rdpImport?.let { app.skerry.ui.host.RdpFileImportModal(state, it) }
            if (state.settingsOpen) SettingsPanel(state)
            // Sync onboarding modal over settings: appears via "Set up sync", closes itself on a
            // successful connect. Only when the coordinator is supplied (the mock path with no backend has none).
            LocalSync.current?.let { if (state.syncSetupOpen) SyncSetupDialog(it, onDismiss = state::closeSyncSetup) }
            // "Link a device" dialog: shows a QR/code for quick pairing of a new device.
            LocalSync.current?.let { if (state.pairingOpen) PairingShowDialog(it, onDismiss = state::closePairing) }
            if (onLock == null && state.locked) LockScreen(state)
            // A server asking for a second factor mid-connect. Sits above the other dialogs: the
            // connection is blocked waiting for this answer, and it can appear over any of them
            // (a snippet run reconnecting, a tunnel dialing) — whatever is underneath keeps its state.
            app.skerry.ui.connection.KeyboardInteractiveHost(
                app.skerry.ui.app.LocalKeyboardInteractive.current,
            )
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
                    // Only a team-shared host is offered our keychain here (its credential link is
                    // stripped on share, so a key-only server would be unreachable); a profile of our
                    // own asked for this prompt, so it gets the password field alone.
                    secrets = connectableSecrets(credentials?.credentials.orEmpty(), pending.host, hostManager?.hosts.orEmpty()),
                    onUseSecret = { secret ->
                        pendingAuth = null
                        openResolved(pending, secret.toSshAuth())
                    },
                )
            }
            // VNC password prompt ("ask every time"): an empty entry means the server needs no password
            // (security type None); a non-empty one is the VNC-Auth password.
            pendingVncHost?.let { host ->
                val openVncWith = { auth: app.skerry.shared.vnc.VncAuth ->
                    pendingVncHost = null
                    state.recordRecentHost(host.id)
                    sessions?.openVnc(
                        host.id, host.label, host.connectionSubtitle(), host.toTarget(), auth,
                        remoteResize = host.vncResizeToWindow,
                        onRemoteResizeChanged = { on -> hostManager?.setVncResizeToWindow(host.id, on) },
                    )
                    state.showSection(HostSection.RemoteDesktops)
                }
                DesktopPasswordDialog(
                    host = host,
                    onDismiss = { pendingVncHost = null },
                    onConnect = { pw ->
                        openVncWith(if (pw.isEmpty()) app.skerry.shared.vnc.VncAuth.None else app.skerry.shared.vnc.VncAuth.Password(pw))
                    },
                    secrets = connectableSecrets(credentials?.credentials.orEmpty(), host, hostManager?.hosts.orEmpty()),
                    onUseSecret = { secret -> openVncWith(secret.toVncAuth()) },
                )
            }
            // RDP password prompt: the profile names the user, this supplies the password for this
            // session only. An empty entry is refused by the dialog — RDP has no anonymous logon.
            pendingRdpHost?.let { host ->
                DesktopPasswordDialog(
                    host = host,
                    onDismiss = { pendingRdpHost = null },
                    onConnect = { pw ->
                        pendingRdpHost = null
                        openRdpWith(host, pw)
                    },
                    secrets = connectableSecrets(credentials?.credentials.orEmpty(), host, hostManager?.hosts.orEmpty()),
                    onUseSecret = { secret ->
                        // A secret an RDP logon cannot use leaves the prompt where it is: closing it
                        // on a click that opens no session is a dead end with nothing said.
                        val password = secret.toRdpPassword()
                        if (password != null) {
                            pendingRdpHost = null
                            openRdpWith(host, password)
                        }
                    },
                )
            }
            // Production guard: confirm before a #prod session opens. Sits in front of the password
            // prompt — the question is whether to touch production at all, not which secret to use.
            prodConnect?.let { request ->
                ProdConnectDialog(request, onDismiss = { prodConnect = null })
            }
            // …and, once inside, keep every open session armed and confirm the risky commands it
            // holds. At the root, so the confirmation is never covered by the terminal's own chrome.
            ProdGuardSync(sessions, state.settings.confirmProductionWarnings)
            // Keeps each tab's synchronized-input wiring in step with its toggle.
            PaneSyncBinder(sessions)
            ProdCommandGate(sessions?.active)
            // Broken ProxyJump chain for the clicked host: explain instead of connecting (never
            // silently direct). Set by openResolved for all three connect paths.
            jumpProblem?.let { problem ->
                JumpErrorDialog(problem, onDismiss = { jumpProblem = null })
            }
            // Confirmation for a snippet with ${{…}} variables — every launch path (palette,
            // hotkey, "Run on host", library) parks such a run in SnippetManager.pendingRun.
            snippets?.let { SnippetRunDialog(it) }
            // Confirmation before a runbook starts: it previews every step with its variables
            // resolved, so the procedure is agreed to once instead of step by step. Confirming hands
            // the work area of the tab the run was started from to the run screen — a desktop route
            // (Viewport); the mobile chrome shows the run in its floating panel instead.
            val runSessions = LocalSessions.current
            LocalRunbookRunner.current?.let { runner ->
                RunbookStartDialog(runner) { runSessions?.setActiveView(SessionView.Runbook) }
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
                is GroupDialog.Create -> GroupEditDialog(
                    initialName = "",
                    onDismiss = state::dismissGroupDialog,
                    onSave = { name -> state.addCustomGroup(name, gd.section); state.dismissGroupDialog() },
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
            // Confirm disconnecting a session (power) / closing a pane — destructive, no auto-reconnect.
            when (val pc = state.pendingClose) {
                is PendingClose.Session -> {
                    val tab = sessions?.tab(pc.id)
                    // The power button closes the tab, and a split tab takes every pane with it —
                    // so a tab holding several sessions is warned about as a group, not by the name
                    // of whichever host happens to be its first pane.
                    val open = tab?.panes.orEmpty().filterNot { it.isBlank }
                    val name = tab?.displayTitle ?: stringResource(Res.string.shell_this_session)
                    ConfirmActionDialog(
                        title = if (open.size > 1) {
                            stringResource(Res.string.shell_disconnect_all_title)
                        } else {
                            stringResource(Res.string.shell_disconnect_title, name)
                        },
                        message = if (open.size > 1) {
                            stringResource(
                                Res.string.shell_disconnect_all_message,
                                open.joinToString(", ") { it.displayTitle.ifBlank { it.subtitle } },
                            )
                        } else {
                            stringResource(Res.string.shell_disconnect_message)
                        },
                        confirmLabel = stringResource(Res.string.shell_disconnect),
                        onConfirm = { sessions?.close(pc.id); state.dismissClose() },
                        onDismiss = state::dismissClose,
                    )
                }
                is PendingClose.Pane -> {
                    val pane = sessions?.tab(pc.tabId)?.pane(pc.paneId)
                    val paneName = pane?.let { p -> p.displayTitle.ifBlank { p.subtitle } }
                        .orEmpty().ifBlank { stringResource(Res.string.shell_this_session) }
                    ConfirmActionDialog(
                        title = stringResource(Res.string.shell_close_pane_title),
                        message = stringResource(Res.string.shell_close_pane_message, paneName),
                        confirmLabel = stringResource(Res.string.shell_close_panel),
                        onConfirm = { sessions?.closePane(pc.tabId, pc.paneId); state.dismissClose() },
                        onDismiss = state::dismissClose,
                    )
                }
                null -> {}
            }
            // Confirm re-pointing a pane that already holds a session: the connection it runs goes
            // down the moment the new one is dialled, and the picker sits under the whole header.
            state.pendingPaneConnect?.let { pending ->
                val pane = sessions?.tab(pending.tabId)?.pane(pending.paneId)
                val paneName = pane?.let { p -> p.displayTitle.ifBlank { p.subtitle } }
                    .orEmpty().ifBlank { stringResource(Res.string.shell_this_session) }
                val connectPane = LocalConnectPane.current
                ConfirmActionDialog(
                    title = stringResource(Res.string.shell_replace_pane_title, pending.host.label),
                    message = stringResource(Res.string.shell_close_pane_message, paneName),
                    confirmLabel = stringResource(Res.string.shell_connect),
                    onConfirm = { connectPane(pending.host, pending.paneId); state.dismissPaneConnect() },
                    onDismiss = state::dismissPaneConnect,
                )
            }
        }
    }
}
