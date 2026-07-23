package app.skerry.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.sync.SyncSetupForm
import app.skerry.ui.sync.SyncStatus
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sync_setup_title
import app.skerry.ui.generated.resources.sync_setup_dialog_desc
import app.skerry.ui.generated.resources.sync_field_server_url
import app.skerry.ui.generated.resources.sync_placeholder_server_url
import app.skerry.ui.generated.resources.sync_field_account
import app.skerry.ui.generated.resources.sync_placeholder_account
import app.skerry.ui.generated.resources.sync_field_master_password
import app.skerry.ui.generated.resources.sync_placeholder_master_password
import app.skerry.ui.generated.resources.sync_insecure_url_warning
import app.skerry.ui.generated.resources.sync_connecting
import app.skerry.ui.generated.resources.sync_zero_knowledge
import app.skerry.ui.generated.resources.sync_cancel
import app.skerry.ui.generated.resources.sync_connect
import app.skerry.ui.generated.resources.sync_keep_connected
import app.skerry.ui.generated.resources.sync_keep_connected_sub_long
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.theme.Skerry

/**
 * Self-hosted sync onboarding modal: server URL + accountId + master password, one "Connect" action
 * (the coordinator registers a new account or logs into an existing one — no mode choice).
 * Zero-knowledge — the password goes to [SyncCoordinator] as a CharArray and is wiped there; here it's
 * held as a string only until submit and cleared right after. [ModalScrim] + card style; closes
 * itself when the coordinator reaches [SyncStatus.Online].
 */
@Composable
fun SyncSetupDialog(sync: SyncCoordinator, onDismiss: () -> Unit) {
    val noop = remember { MutableInteractionSource() }
    val status by sync.status.collectAsState()

    // Prefill from the saved link (after restart/Reconnect): server+account known, only the password needed.
    val saved = remember { sync.savedConfig }
    var serverUrl by remember { mutableStateOf(saved?.serverUrl ?: "") }
    var account by remember { mutableStateOf(saved?.accountId ?: "") }
    var password by remember { mutableStateOf("") }
    var keepConnected by remember { mutableStateOf(saved?.keepConnected ?: true) }

    val form = SyncSetupForm(serverUrl, account)
    val canSubmit = form.canSubmit(password.length) && status != SyncStatus.Busy

    // Close only if this dialog initiated the connection and it reached Online — otherwise a dialog
    // opened while a session is already active would collapse on the first composition.
    var connecting by remember { mutableStateOf(false) }
    LaunchedEffect(status) {
        if (connecting && status is SyncStatus.Online) {
            password = ""
            onDismiss()
        }
    }

    val submit = submit@{
        if (!canSubmit) return@submit
        connecting = true
        val pw = password.toCharArray() // the coordinator wipes the array
        password = ""
        val url = form.normalizedServerUrl
        val acc = form.normalizedAccountId
        // The coordinator owns the launch (its own scope) — not tied to this composable's lifecycle.
        // One call: the coordinator decides register vs login.
        sync.connect(url, acc, pw, keepConnected)
    }

    // Connecting hit an existing account under a different password (issue #28): swap the form for the
    // re-key confirmation. Placed after the state above so connecting/LaunchedEffect persist and still
    // close this dialog once the confirmed re-connect reaches Online.
    (status as? SyncStatus.NeedsPasswordReplaceConfirm)?.let { pending ->
        PasswordReplaceConfirmDialog(sync, pending.accountId, onDismiss)
        return
    }

    // ModalScrim (not a hand-rolled Box): registers in ModalPresence so the settings scrim below
    // (this dialog is composed as its sibling at the app root) doesn't reclaim focus and strip the
    // caret from these fields. Esc dismisses; a stray scrim click doesn't (a half-typed master
    // password must not be discarded) — Cancel is the explicit close.
    ModalScrim(onDismiss = onDismiss, scrimColor = Skerry.colors.modalScrim) {
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Skerry.colors.surfaceDeep)
                .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                .clickable(interactionSource = noop, indication = null, onClick = {})
                .padding(26.dp),
        ) {
            Txt(stringResource(Res.string.sync_setup_title), color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
            Txt(
                stringResource(Res.string.sync_setup_dialog_desc),
                color = Skerry.colors.dim, size = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            FieldLabel(stringResource(Res.string.sync_field_server_url), top = 16.dp)
            SyncField(stringResource(Res.string.sync_placeholder_server_url), serverUrl, "dns", KeyboardType.Uri, ImeAction.Next) { serverUrl = it }

            FieldLabel(stringResource(Res.string.sync_field_account))
            SyncField(stringResource(Res.string.sync_placeholder_account), account, "person", KeyboardType.Text, ImeAction.Next) { account = it }

            FieldLabel(stringResource(Res.string.sync_field_master_password))
            SyncField(stringResource(Res.string.sync_placeholder_master_password), password, "key", KeyboardType.Password, ImeAction.Done, secret = true, onSubmit = { submit() }) { password = it }

            KeepConnectedRow(keepConnected) { keepConnected = it }

            // http:// is allowed (local test/LAN without a TLS proxy) but defenseless against MITM — warn explicitly.
            if (form.isInsecureUrl) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sym("warning", size = 14.sp, color = Skerry.colors.sunset)
                    Txt(stringResource(Res.string.sync_insecure_url_warning), color = Skerry.colors.sunset, size = 11.sp, lineHeight = 15.sp)
                }
            }

            val failed = status as? SyncStatus.Failed
            if (failed != null) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sym("error", size = 14.sp, color = Skerry.colors.sunset)
                    Txt(syncFailureText(failed), color = Skerry.colors.sunset, size = 11.5.sp)
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sym("shield_lock", size = 14.sp, color = Skerry.colors.moss)
                    Txt(
                        if (status == SyncStatus.Busy) stringResource(Res.string.sync_connecting) else stringResource(Res.string.sync_zero_knowledge),
                        color = Skerry.colors.faint, size = 11.sp,
                    )
                }
                CancelButton(stringResource(Res.string.sync_cancel), onClick = onDismiss)
                PrimaryButton(stringResource(Res.string.sync_connect), onClick = { submit() }, enabled = canSubmit)
            }
        }
    }
}

/** "Keep me connected" checkbox: remember the link and restore the session without re-entering the password. */
@Composable
private fun KeepConnectedRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))
                .background(if (checked) Skerry.colors.cyan else Color.Transparent)
                .border(1.dp, if (checked) Skerry.colors.cyan else Skerry.colors.cyan14, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Sym("check", size = 13.sp, color = Skerry.colors.ink)
        }
        Column(Modifier.weight(1f)) {
            Txt(stringResource(Res.string.sync_keep_connected), color = Skerry.colors.text, size = 12.5.sp, weight = FontWeight.Medium)
            Txt(stringResource(Res.string.sync_keep_connected_sub_long), color = Skerry.colors.faint, size = 11.sp)
        }
    }
}

@Composable
internal fun FieldLabel(text: String, top: androidx.compose.ui.unit.Dp = 12.dp) {
    Txt(text, color = Skerry.colors.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = top, bottom = 5.dp))
}

@Composable
internal fun SyncField(
    placeholder: String,
    value: String,
    icon: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    secret: Boolean = false,
    onSubmit: () -> Unit = {},
    onChange: (String) -> Unit,
) {
    val ui = LocalFonts.current.ui
    val textColor = Skerry.colors.text
    val style = remember(ui, textColor) { TextStyle(color = textColor, fontSize = 13.sp, fontFamily = ui) }
    // Capsule/padding/icon live in decorationBox so a click anywhere in the field places the caret.
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(Skerry.colors.cyan),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = imeAction, keyboardType = keyboardType),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }, onGo = { onSubmit() }, onSend = { onSubmit() }),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 11.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Sym(icon, size = 16.sp, color = Skerry.colors.faint)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Txt(placeholder, color = Skerry.colors.faint, size = 13.sp)
                    inner()
                }
            }
        },
    )
}
