package app.skerry.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.skerry.shared.ssh.SshConfigHost
import app.skerry.ui.design.Sym
import app.skerry.ui.theme.Skerry

/**
 * Checkbox glyph for an ssh_config import row, shared by the desktop modal and the mobile sheet so
 * the two stay visually in step. Uses the app icon set rather than a Material checkbox for
 * consistency with the rest of the chrome.
 */
@Composable
internal fun SshImportCheck(selected: Boolean, size: TextUnit = 18.sp) {
    Sym(
        if (selected) "check_box" else "check_box_outline_blank",
        size = size,
        color = if (selected) Skerry.colors.cyanBright else Skerry.colors.faint,
    )
}

/** Secondary line under an alias in the import preview: `user@host[:port]`, port shown only when
 *  non-default. Shared by the desktop and mobile import UIs. */
internal fun sshImportHostSummary(host: SshConfigHost): String = buildString {
    host.user?.let { append(it); append('@') }
    append(host.hostName)
    if (host.port != 22) { append(':'); append(host.port) }
}
