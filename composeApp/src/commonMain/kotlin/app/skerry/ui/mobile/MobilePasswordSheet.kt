package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.shared.vault.Credential
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.host.rowLabel
import app.skerry.ui.secure.SecureScreen
import app.skerry.ui.vault.VaultPresentation
import app.skerry.ui.generated.resources.shell_use_saved_secret
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_password_label
import app.skerry.ui.generated.resources.term_connect
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.rememberModalPresence
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Txt
import app.skerry.ui.theme.Skerry

/**
 * Bottom password-prompt sheet on Connect to a host with no bound identity (styled like the
 * `New connection` sheet). The password goes to [onConnect] as a string and is used right away in
 * `SshAuth.Password`; the buffer lives only in this composable. A tap outside the sheet — [onDismiss].
 *
 * [secrets] mirror the desktop dialog: a team-shared host arrives without its credential link, so
 * the keychain is offered here too — a key-only server has no other way in.
 */
@Composable
fun MobilePasswordSheet(
    host: Host,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit,
    secrets: List<Credential> = emptyList(),
    onUseSecret: (Credential) -> Unit = {},
) {
    var password by remember { mutableStateOf("") }
    val submit = { if (password.isNotEmpty()) onConnect(password) }
    // The desktop dialog's two rules, on the phone as well: registered as a modal so the session
    // underneath does not claim the keyboard back, and the caret taken off it — a hardware keyboard
    // (a tablet, DeX, a paired one) would otherwise type the password into that session's shell.
    rememberModalPresence()
    val focusManager = LocalFocusManager.current
    val focus = remember(host.id) { FocusRequester() }
    LaunchedEffect(host.id) {
        // Cleared first, then claimed — the desktop dialog's order: if the field cannot take the
        // caret, the keys go nowhere rather than into the session this sheet opened over.
        focusManager.clearFocus(force = true)
        withFrameNanos {}
        focus.requestFocus(FocusDirection.Enter)
    }
    // Protect SSH password entry on connect from screenshots/Recent Apps previews (Android; desktop no-op).
    SecureScreen()
    MobileBottomSheet(
        onDismiss = onDismiss,
        panelModifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 30.dp),
    ) {
        Txt(host.rowLabel(), color = Skerry.colors.text, size = 20.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Txt(host.connectionSubtitle(), color = Skerry.colors.dim, size = 12.5.sp, font = LocalFonts.current.mono)
            Spacer(Modifier.height(18.dp))
            Txt(stringResource(Res.string.term_password_label), color = Skerry.colors.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
            Spacer(Modifier.height(6.dp))
            MobileFormInput(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.focusRequester(focus),
                placeholder = "••••••••",
                masked = true,
                imeAction = ImeAction.Go,
                onSubmit = { submit() },
            )
            if (secrets.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Txt(stringResource(Res.string.shell_use_saved_secret), color = Skerry.colors.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    secrets.forEach { secret ->
                        MobileSheetButton(
                            label = secret.label,
                            onClick = { onUseSecret(secret) },
                            modifier = Modifier.fillMaxWidth(),
                            icon = VaultPresentation.secretIcon(secret.secret),
                            filled = false,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (password.isNotEmpty()) Skerry.colors.cyan else Skerry.colors.cyan.copy(alpha = 0.4f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = submit)
                    .padding(15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(stringResource(Res.string.term_connect), color = Skerry.colors.ink, size = 16.sp, weight = FontWeight.Bold)
            }
        }
}
