package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.skerry.shared.files.FileItem
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.files.FileEditController
import app.skerry.ui.files.FileEditorScreen
import app.skerry.ui.files.FilePaneState
import app.skerry.ui.files.TransferCoordinator
import app.skerry.ui.files.fileDisplayPath
import app.skerry.ui.files.platformLocalBrowser
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ftail_open_failed
import app.skerry.ui.generated.resources.sftp_connecting
import app.skerry.ui.generated.resources.sftp_create
import app.skerry.ui.generated.resources.sftp_create_directory
import app.skerry.ui.generated.resources.sftp_new_folder
import app.skerry.ui.generated.resources.sftp_unavailable
import app.skerry.ui.generated.resources.sftp_upload_file
import app.skerry.ui.sftp.ConfirmOverwriteDialog
import app.skerry.ui.sftp.pickDownloadTarget
import app.skerry.ui.sftp.pickUploadSource
import app.skerry.ui.sftp.safeDownloadName
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.sftp.NameDialog
import app.skerry.ui.theme.Skerry

/**
 * Root Files tab: single-pane browser of the active session's remote SFTP over a cached
 * [TransferCoordinator]. The local device pane is removed (Android scoped storage makes it
 * useless), and so is the Remote/Local switch — the screen always shows the host's directory.
 *
 * Mode is picked by [mobileFilesMode]: no session manager — static mock ([MockMobileFilesView]);
 * a connected active session — live listing; otherwise a "no session" notice. Visible actions:
 * tap a folder to enter, tap a file (`ios_share` icon) to download via the system "Save to…", FAB
 * creates a directory / uploads a file from the device. Rename/delete and "Download to device" are
 * in the context menu (long-press).
 */
@Composable
fun MobileFilesScreen(onBack: (() -> Unit)? = null) {
    val mono = LocalFonts.current.mono
    val sessions = LocalSessions.current
    val active = sessions?.activeSession
    val uiState = active?.controller?.uiState
    val connected = uiState is ConnectionUiState.Connected
    val connecting = uiState is ConnectionUiState.Connecting
    when (mobileFilesMode(hasSessions = sessions != null, connected = connected, connecting = connecting)) {
        MobileFilesMode.Preview -> MockMobileFilesView(mono)
        // active?.let instead of !!: sessions.activeSession is a derived getter over two snapshot fields,
        // and a session-close race could leave it null even while connected — fall back to nothing.
        MobileFilesMode.Live -> active?.let { LiveMobileFilesView(it.controller, it.subtitle, mono, onBack) }
        // "Connecting…" with the host subtitle: after tapping SFTP/Connect the session is still
        // handshaking — don't flash "No active session". active?.let for the same close race as Live.
        MobileFilesMode.Connecting -> active?.let { ConnectingMobileFilesView(it.subtitle, onBack) }
        MobileFilesMode.NoSession -> NoSessionMobileFilesView(onBack)
    }
}

// Live path.

/**
 * Live Files screen over the session's cached [TransferCoordinator] (opened once and lives on the
 * session scope — switching tabs doesn't reset path/selection). Shows only the Remote pane (the
 * host's directory); the coordinator's Local pane is used only as the "Download to device" sink.
 */
@Composable
private fun LiveMobileFilesView(controller: ConnectionController, subtitle: String, mono: FontFamily, onBack: (() -> Unit)? = null) {
    var coord by remember(controller) { mutableStateOf<TransferCoordinator?>(null) }
    var openError by remember(controller) { mutableStateOf<String?>(null) }
    var creatingFolder by remember(controller) { mutableStateOf(false) }
    var fabOpen by remember(controller) { mutableStateOf(false) }
    // Quick name filter row under the breadcrumb (the funnel icon toggles it).
    var filterOpen by remember(controller) { mutableStateOf(false) }
    // Built-in viewer/editor over the cursored file (long-press menu). The controller comes from the
    // coordinator (session scope), so closing the modal never cancels a save in flight.
    var editor by remember(controller) { mutableStateOf<FileEditController?>(null) }
    // UI scope only for the native file picker (FAB Upload); the transfer itself lives on the
    // session scope inside the coordinator and survives the view leaving composition.
    val uiScope = rememberCoroutineScope()
    // stringResource can't be called inside LaunchedEffect — resolve the value beforehand.
    val openFailedMsg = stringResource(Res.string.ftail_open_failed)
    LaunchedEffect(controller) {
        openError = null
        try {
            coord = controller.openTransferCoordinator(platformLocalBrowser(), subtitle)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // sshj/transport text carries addresses and internals — only the localized reason is shown.
            openError = openFailedMsg
        }
    }

    val c = coord
    // A path opened from terminal output ("Open in Files" on a selection): reveal it in the remote
    // pane once the coordinator is up. Keyed on the request so a repeat click is honoured too.
    LaunchedEffect(c, controller.pendingRevealPath) {
        if (c != null) controller.takeRevealRequest()?.let { c.remote.revealPath(it) }
    }
    val openEditor = editor
    // Built-in viewer/editor (long-press → View/Edit): takes over the screen instead of opening a
    // dialog, so it gets the full height and the same chrome as the file list.
    if (openEditor != null) {
        FileEditorScreen(
            controller = openEditor,
            onClose = { editor = null },
            modifier = Modifier.fillMaxSize(),
            // No function keys on touch: Save/Close sit in the header instead.
            showKeyBar = false,
        )
        return
    }
    Box(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            MobileFilesTitle(onBack)
            when {
                // One neutral centered status for the whole waiting phase: session handshake →
                // SFTP channel open → first directory listing. Text and position stay fixed (same
                // idea as the terminal — it doesn't matter whether it's a terminal or sftp), so the
                // screen doesn't flash or jump vertically. Breadcrumb + list show only once loaded.
                openError != null -> MobileFilesNoticeBox("error", stringResource(Res.string.sftp_unavailable), openError, Skerry.colors.sunset)
                c == null || c.remote.state is FilePaneState.Loading ->
                    MobileFilesNoticeBox("sync", stringResource(Res.string.sftp_connecting), subtitle, Skerry.colors.cyanBright)
                else -> {
                    val pane = c.remote
                    // File row's visible action (ios_share): download OUT of the sandbox via the
                    // system "Save to…" ([pickDownloadTarget] → SAF on Android, native dialog on
                    // desktop) — the picker is suspend, so it goes through uiScope. Stabilized on
                    // (c, uiScope) so the lambda isn't recreated on every recomposition (e.g. when
                    // the transfer card updates) and doesn't needlessly invalidate the list.
                    val onTransfer = remember(c, uiScope) {
                        { item: FileItem ->
                            uiScope.launch { pickDownloadTarget(safeDownloadName(item.name))?.let { c.downloadToTarget(item, it) } }
                            Unit
                        }
                    }
                    // "Download to device" (long-press on a file): download WITHOUT a dialog into
                    // the app's directory (coordinator's Local pane), so the file lands on device right away.
                    val downloadHere = remember(c) {
                        { item: FileItem -> c.remote.selectOnly(item); c.downloadSelection() }
                    }
                    MobileLiveBreadcrumb(pane, mono, filterOpen, onFilterOpenChange = { filterOpen = it })
                    if (filterOpen || pane.nameFilter.isNotEmpty()) {
                        MobileFilterRow(pane, mono, onClose = { filterOpen = false })
                    }
                    // View/Edit a remote file in place (parity with desktop F3/F4).
                    val openEditor = remember(c) {
                        { item: FileItem, readOnly: Boolean ->
                            editor = c.openEditor(fromLocal = false, item = item, readOnly = readOnly)
                        }
                    }
                    MobileLivePane(
                        pane = pane,
                        mono = mono,
                        onTransfer = onTransfer,
                        onDownloadHere = downloadHere,
                        onOpenEditor = openEditor,
                        modifier = Modifier.weight(1f),
                    )
                    MobileTransferCard(c.transfer, mono, onDismiss = c::dismissCompleted)
                    Spacer(Modifier.height(88.dp)) // room for the floating FAB (push screen has no tab bar)
                }
            }
        }
        // Scrim behind the expanded menu: tapping outside collapses the FAB (mobile speed-dial idiom).
        if (fabOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        fabOpen = false
                    },
            )
        }
        // Single "+" FAB: expands actions over the Remote pane — "Create directory" and "Upload
        // file" (uploadSource targets remote.path). Actions stack up ABOVE the button with labels.
        if (c != null && openError == null && c.remote.state !is FilePaneState.Loading) {
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (fabOpen) {
                    MobileFabAction("create_new_folder", stringResource(Res.string.sftp_create_directory)) {
                        fabOpen = false
                        creatingFolder = true
                    }
                    MobileFabAction("upload", stringResource(Res.string.sftp_upload_file)) {
                        fabOpen = false
                        uiScope.launch { pickUploadSource()?.let { c.uploadSource(it) } }
                    }
                }
                MobileFabButton(open = fabOpen, onClick = { fabOpen = !fabOpen })
            }
        }
        if (creatingFolder && c != null) {
            // Create a directory in the Remote pane. Reuses the shared NameDialog
            // (validates empty/"/"/"."/".."/control chars), like desktop "New folder".
            val pane = c.remote
            NameDialog(
                title = stringResource(Res.string.sftp_new_folder),
                confirmLabel = stringResource(Res.string.sftp_create),
                initial = "",
                existing = pane.currentEntryNames(),
                onConfirm = { pane.mkdir(it); creatingFolder = false },
                onDismiss = { creatingFolder = false },
            )
        }
        // Overwrite conflict (download/upload found a same-named object at the destination) — the
        // same confirmation dialog as desktop; the coordinator is shared, so [overwrite] state is shared too.
        c?.overwrite?.let { conflict ->
            ConfirmOverwriteDialog(
                names = conflict.names,
                onConfirm = { c.resolveOverwrite(true) },
                onDismiss = { c.resolveOverwrite(false) },
            )
        }
    }
}
