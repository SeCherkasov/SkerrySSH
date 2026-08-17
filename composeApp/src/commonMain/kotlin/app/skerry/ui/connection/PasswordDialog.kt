package app.skerry.ui.connection

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.ui.host.rowLabel
import app.skerry.shared.ssh.isRdp
import app.skerry.shared.ssh.isVnc
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_connect_to
import app.skerry.ui.generated.resources.shell_password_caps
import app.skerry.ui.generated.resources.shell_password_host_placeholder
import app.skerry.ui.generated.resources.shell_not_stored_once
import app.skerry.ui.generated.resources.shell_use_saved_secret
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.generated.resources.shell_connect
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.rememberPromptFocus
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.fieldName
import app.skerry.ui.theme.Skerry
import app.skerry.ui.vault.VaultPresentation
import androidx.compose.ui.platform.testTag
import app.skerry.ui.app.UiTags

/**
 * Password-entry dialog for connecting to a host with no bound identity (parity with the mobile
 * `PasswordSheet`). The password isn't saved: it goes straight into [onConnect] as a one-shot
 * session secret. Style — scrim + card layout, like [NewConnectionModal].
 *
 * [secrets] are the keychain entries offered as an alternative to typing one: a host shared by a
 * team arrives with its credential link stripped, so without this a member could only reach a
 * password-authenticated box — a key-only server would have no way in at all. Picking one connects
 * with it directly and does not bind it to the profile (the shared profile isn't ours to edit).
 */
@Composable
fun DesktopPasswordDialog(
    host: Host,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit,
    secrets: List<Credential> = emptyList(),
    onUseSecret: (Credential) -> Unit = {},
) {
    val noop = remember { MutableInteractionSource() }
    // Keyed like the focus effect below: were the dialog ever re-pointed at another host in place,
    // an unkeyed buffer would submit the first host's password to the second.
    var password by remember(host.id) { mutableStateOf("") }
    val submit = { if (password.isNotEmpty()) onConnect(password) }
    // And the field takes the caret itself. Without it the keyboard stays where it was — on the
    // live session this dialog opened over — and the password is typed into that shell instead,
    // which is also why the keyboard-interactive dialog focuses its first answer.
    val focus = remember(host.id) { FocusRequester() }
    val prompt = rememberPromptFocus(focus, host.id)

    Box(
        prompt.fillMaxSize().background(Skerry.colors.modalScrim).clickable(interactionSource = noop, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Skerry.colors.surfaceDeep)
                .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                .clickable(interactionSource = noop, indication = null, onClick = {})
                .padding(26.dp),
        ) {
            Txt(stringResource(Res.string.shell_connect_to, host.rowLabel()), color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
            Txt(host.connectionSubtitle(), color = Skerry.colors.dim, size = 12.5.sp, font = LocalFonts.current.mono, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))

            Txt(stringResource(Res.string.shell_password_caps), color = Skerry.colors.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(bottom = 5.dp))
            val ui = LocalFonts.current.ui
            val textColor = Skerry.colors.text
            val style = remember(ui, textColor) { TextStyle(color = textColor, fontSize = 13.sp, fontFamily = ui) }
            // Capsule/padding/icon live in decorationBox so a click anywhere in the field places the caret.
            BasicTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(Skerry.colors.cyan),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Password),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                // Named after the placeholder, not the "PASSWORD" caption above it: the caption is
                // drawn in caps and a screen reader should not be handed shouted text, and "connection
                // password" also tells it apart from the vault's master password.
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(focus)
                    .fieldName(fallback = stringResource(Res.string.shell_password_host_placeholder))
                    .testTag(UiTags.FORM_FIELD),
                decorationBox = { inner ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 11.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Sym("key", size = 16.sp, color = Skerry.colors.faint)
                        Box(Modifier.weight(1f)) {
                            if (password.isEmpty()) Txt(stringResource(Res.string.shell_password_host_placeholder), color = Skerry.colors.faint, size = 13.sp)
                            inner()
                        }
                    }
                },
            )

            if (secrets.isNotEmpty()) {
                Txt(
                    stringResource(Res.string.shell_use_saved_secret),
                    color = Skerry.colors.faint,
                    size = 10.5.sp,
                    weight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier.padding(top = 16.dp, bottom = 5.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    secrets.forEach { secret ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(7.dp))
                                .background(Skerry.colors.bg)
                                .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp))
                                .clickable { onUseSecret(secret) }
                                .padding(horizontal = 11.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Sym(VaultPresentation.secretIcon(secret.secret), size = 15.sp, color = Skerry.colors.cyan)
                            Txt(secret.label, color = Skerry.colors.text, size = 12.5.sp)
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sym("shield_lock", size = 14.sp, color = Skerry.colors.moss)
                    Txt(stringResource(Res.string.shell_not_stored_once), color = Skerry.colors.faint, size = 11.sp)
                }
                CancelButton(stringResource(Res.string.shell_cancel), onClick = onDismiss, modifier = Modifier.testTag(UiTags.FORM_CANCEL))
                PrimaryButton(stringResource(Res.string.shell_connect), onClick = submit, enabled = password.isNotEmpty(), modifier = Modifier.testTag(UiTags.FORM_SAVE))
            }
        }
    }
}

/**
 * Keychain entries worth offering when connecting to [host] with nothing bound to the profile.
 *
 * Only for a host outside [ownCatalog] — that is, a team-shared one, which arrives with its
 * credential link stripped and can't be edited to carry a secret; without the list a key-only server
 * would have no way in. A profile of our own is prompting because we set it to ("ask every time"),
 * so the dialog stays a bare password field and binding a secret is left to the edit form.
 *
 * SSH (and Mosh over it) authenticates with any kind of secret; VNC-Auth and an RDP logon know only
 * a password, so a key there would be a row that cannot work. Order is the keychain's own, so the
 * list reads the same as the vault screen.
 */
fun connectableSecrets(credentials: List<Credential>, host: Host, ownCatalog: List<Host>): List<Credential> = when {
    ownCatalog.any { it.id == host.id } -> emptyList()
    host.connectionType.isVnc || host.connectionType.isRdp ->
        credentials.filter { it.secret is CredentialSecret.Password }

    else -> credentials
}
