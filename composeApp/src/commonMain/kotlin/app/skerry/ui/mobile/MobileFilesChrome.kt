package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.files.FilePaneController
import app.skerry.ui.files.PathJumpField
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sftp_connecting
import app.skerry.ui.generated.resources.sftp_files_title
import app.skerry.ui.generated.resources.sftp_filter_hint
import app.skerry.ui.generated.resources.sftp_no_session
import app.skerry.ui.generated.resources.sftp_no_session_hint
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.theme.Skerry

/**
 * "Files" title (28sp, as in the layout). Actions (create directory/upload) live in the shared
 * "+" FAB. [onBack] (push-mode SFTP from a host card) adds a back arrow on the left, like the
 * terminal; absent (`null`) in preview.
 */
@Composable
internal fun MobileFilesTitle(onBack: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack != null) 14.dp else 22.dp, end = 22.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) {
            Sym(
                "chevron_left",
                size = 27.sp,
                color = Skerry.colors.cyanBright,
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack),
            )
        }
        MobileScreenTitle(stringResource(Res.string.sftp_files_title))
    }
}

/**
 * Breadcrumb row below the title: host icon (dns) + "label : path" of the active Remote session.
 * When [onGoToPath] is supplied (live mode) the crumb is tappable: it turns into a path input so a
 * known destination can be typed and jumped to (IME "Go" → [onGoToPath], blur → cancel). The editor
 * closes on its own once the pane navigates (the row re-keys on [path]); the mock passes no callback.
 */
@Composable
internal fun MobileFilesBreadcrumbRow(
    label: String,
    path: String,
    mono: FontFamily,
    onGoToPath: ((String) -> Unit)? = null,
    // Live mode: the trailing funnel icon toggles the quick-filter row; [filterActive] tints it.
    onToggleFilter: (() -> Unit)? = null,
    filterActive: Boolean = false,
) {
    var editing by remember(path) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Sym("dns", size = 16.sp, color = Skerry.colors.moss)
        if (editing && onGoToPath != null) {
            PathJumpField(
                path = path,
                mono = mono,
                textSize = 12.sp,
                onCommit = { onGoToPath(it); editing = false },
                onCancel = { editing = false },
                modifier = Modifier.weight(1f),
            ) { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Skerry.colors.bg)
                        .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) { inner() }
            }
        } else {
            Txt(
                mobileFilesBreadcrumb(label, path),
                color = Skerry.colors.dim,
                size = 12.sp,
                font = mono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).then(
                    if (onGoToPath != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { editing = true }
                    } else {
                        Modifier
                    },
                ),
            )
        }
        if (onToggleFilter != null && !editing) {
            Sym(
                "filter_alt",
                size = 18.sp,
                color = if (filterActive) Skerry.colors.cyanBright else Skerry.colors.faint,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleFilter,
                ),
            )
        }
    }
}

/**
 * Quick name filter row under the breadcrumb: live filtering as you type
 * ([FilePaneController.setNameFilter] — substring or `*`/`?` glob). The close icon clears the
 * filter and hides the row. The local text state is re-keyed on the pane's path: navigation
 * clears the controller's filter, so the field follows.
 */
@Composable
internal fun MobileFilterRow(pane: FilePaneController, mono: FontFamily, onClose: () -> Unit) {
    var text by remember(pane, pane.path) { mutableStateOf(pane.nameFilter) }
    // Focus the field the moment the row appears (funnel tap), so typing starts immediately.
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { fieldFocus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                pane.setNameFilter(it)
            },
            singleLine = true,
            textStyle = TextStyle(color = Skerry.colors.text, fontSize = 13.sp, fontFamily = mono),
            cursorBrush = SolidColor(Skerry.colors.cyan),
            modifier = Modifier.weight(1f).focusRequester(fieldFocus),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Skerry.colors.bg)
                        .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    if (text.isEmpty()) {
                        Txt(stringResource(Res.string.sftp_filter_hint), color = Skerry.colors.dim, size = 13.sp, font = mono)
                    }
                    inner()
                }
            },
        )
        IconBtn(
            "close",
            onClick = {
                pane.setNameFilter("")
                onClose()
            },
            box = 26,
            icon = 16.sp,
        )
    }
}

/** Round "+" FAB for Files — the shared [MobileFabButton]; when expanded, [open] shows "x" to collapse. */
@Composable
internal fun MobileFabButton(open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    MobileFabButton(onClick = onClick, modifier = modifier, icon = if (open) "close" else "add", iconSize = 26.sp)
}

/** "+" FAB menu item: pill with icon and label (floats above the button). */
@Composable
internal fun MobileFabAction(icon: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Skerry.colors.surface2)
            .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(14.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym(icon, size = 20.sp, color = Skerry.colors.cyanBright)
        Txt(label, color = Skerry.colors.text, size = 13.5.sp, weight = FontWeight.Medium)
    }
}

/** Centered notice in the free area below the title (connecting/opening/loading/error). */
@Composable
internal fun MobileFilesNoticeBox(icon: String, title: String, subtitle: String?, color: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MobileFilesNoticeContent(icon, title, subtitle, color)
    }
}

@Composable
private fun MobileFilesNoticeContent(icon: String, title: String, subtitle: String?, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Sym(icon, size = 30.sp, color = color)
        Txt(title, color = Skerry.colors.text, size = 14.sp, weight = FontWeight.Medium)
        if (subtitle != null) Txt(subtitle, color = Skerry.colors.faint, size = 12.sp)
    }
}

/** Session manager exists but the active session isn't connected: title + notice. */
@Composable
internal fun NoSessionMobileFilesView(onBack: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        MobileFilesTitle(onBack)
        MobileFilesNoticeBox("cloud_off", stringResource(Res.string.sftp_no_session), stringResource(Res.string.sftp_no_session_hint), Skerry.colors.faint)
    }
}

/** Active session is still connecting (tapped SFTP/Connect): title + "Connecting…" with the host subtitle. */
@Composable
internal fun ConnectingMobileFilesView(subtitle: String, onBack: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        MobileFilesTitle(onBack)
        MobileFilesNoticeBox("sync", stringResource(Res.string.sftp_connecting), subtitle, Skerry.colors.cyanBright)
    }
}

// Mock (preview/offscreen).
