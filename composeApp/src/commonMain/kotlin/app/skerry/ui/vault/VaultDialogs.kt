package app.skerry.ui.vault

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.vault.SshCertificateInspector
import app.skerry.shared.vault.SshKeyType
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vault_add
import app.skerry.ui.generated.resources.vault_add_password
import app.skerry.ui.generated.resources.vault_any_principal
import app.skerry.ui.generated.resources.vault_browse
import app.skerry.ui.generated.resources.vault_cancel
import app.skerry.ui.generated.resources.vault_cert_read_error
import app.skerry.ui.generated.resources.vault_cert_valid_summary
import app.skerry.ui.generated.resources.vault_confirm_master_subtitle
import app.skerry.ui.generated.resources.vault_confirm_master_title
import app.skerry.ui.generated.resources.vault_copy
import app.skerry.ui.generated.resources.vault_delete
import app.skerry.ui.generated.resources.vault_delete_detail_bound
import app.skerry.ui.generated.resources.vault_delete_detail_none
import app.skerry.ui.generated.resources.vault_delete_title
import app.skerry.ui.generated.resources.vault_dialog_add_password_subtitle
import app.skerry.ui.generated.resources.vault_dialog_generate_subtitle
import app.skerry.ui.generated.resources.vault_dialog_generate_title
import app.skerry.ui.generated.resources.vault_dialog_import_subtitle
import app.skerry.ui.generated.resources.vault_dialog_key_file_subtitle
import app.skerry.ui.generated.resources.vault_field_algorithm
import app.skerry.ui.generated.resources.vault_field_cert_path
import app.skerry.ui.generated.resources.vault_field_certificate
import app.skerry.ui.generated.resources.vault_field_key_path
import app.skerry.ui.generated.resources.vault_field_master_password
import app.skerry.ui.generated.resources.vault_field_name
import app.skerry.ui.generated.resources.vault_field_passphrase
import app.skerry.ui.generated.resources.vault_field_password
import app.skerry.ui.generated.resources.vault_field_private_key_pem
import app.skerry.ui.generated.resources.vault_generate
import app.skerry.ui.generated.resources.vault_hint_cert_sibling
import app.skerry.ui.generated.resources.vault_hint_cert_sibling_opaque
import app.skerry.ui.generated.resources.vault_import
import app.skerry.ui.generated.resources.vault_import_certificate
import app.skerry.ui.generated.resources.vault_key_file_missing
import app.skerry.ui.generated.resources.vault_link
import app.skerry.ui.generated.resources.vault_link_key_file
import app.skerry.ui.generated.resources.vault_password_mismatch_retry
import app.skerry.ui.generated.resources.vault_placeholder_master_password
import app.skerry.ui.generated.resources.vault_placeholder_name_cert
import app.skerry.ui.generated.resources.vault_placeholder_name_key
import app.skerry.ui.generated.resources.vault_placeholder_name_key_file
import app.skerry.ui.generated.resources.vault_placeholder_name_password
import app.skerry.ui.generated.resources.vault_placeholder_optional
import app.skerry.ui.generated.resources.vault_placeholder_password
import app.skerry.ui.generated.resources.vault_rename
import app.skerry.ui.generated.resources.vault_rename_title
import app.skerry.ui.nav.PlatformBackHandler
import app.skerry.ui.vault.title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.Chip
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.LocalFonts
import app.skerry.shared.ssh.keyFileSiblingRef
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Txt
import app.skerry.ui.design.fieldFocus
import app.skerry.ui.design.rememberFieldDraft
import app.skerry.ui.theme.Skerry

@Composable
internal fun GenerateKeyDialog(onDismiss: () -> Unit, onCreate: (name: String, type: SshKeyType) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(SshKeyType.ED25519) }
    val valid = name.isNotBlank()
    VaultDialogScaffold(stringResource(Res.string.vault_dialog_generate_title), stringResource(Res.string.vault_dialog_generate_subtitle), onDismiss) {
        DialogField(stringResource(Res.string.vault_field_name), name, { name = it }, placeholder = stringResource(Res.string.vault_placeholder_name_key))
        Txt(stringResource(Res.string.vault_field_algorithm), color = Skerry.colors.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SshKeyType.entries.forEach { option ->
                Chip(option.label, active = option == type, modifier = Modifier.clickable { type = option })
            }
        }
        DialogButtons(confirmLabel = stringResource(Res.string.vault_generate), confirmEnabled = valid, onDismiss = onDismiss, onConfirm = { onCreate(name.trim(), type) })
    }
}

@Composable
internal fun AddPasswordDialog(onDismiss: () -> Unit, onCreate: (name: String, password: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && password.isNotEmpty()
    VaultDialogScaffold(stringResource(Res.string.vault_add_password), stringResource(Res.string.vault_dialog_add_password_subtitle), onDismiss) {
        DialogField(stringResource(Res.string.vault_field_name), name, { name = it }, placeholder = stringResource(Res.string.vault_placeholder_name_password))
        Box(Modifier.padding(top = 16.dp)) {
            DialogField(stringResource(Res.string.vault_field_password), password, { password = it }, placeholder = stringResource(Res.string.vault_placeholder_password), password = true)
        }
        DialogButtons(confirmLabel = stringResource(Res.string.vault_add), confirmEnabled = valid, onDismiss = onDismiss, onConfirm = { onCreate(name.trim(), password) })
    }
}

@Composable
internal fun ImportCertificateDialog(
    inspector: SshCertificateInspector,
    onDismiss: () -> Unit,
    onCreate: (name: String, pem: String, certificate: String, passphrase: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var pem by remember { mutableStateOf("") }
    var certificate by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    // Metadata is computed from the entered cert string — this doubles as validation that it's a valid certificate.
    val info = remember(certificate, inspector) { certificate.trim().takeIf { it.isNotEmpty() }?.let { inspector.inspect(it) } }
    val certInvalid = certificate.isNotBlank() && info == null
    val valid = name.isNotBlank() && pem.isNotBlank() && info != null

    VaultDialogScaffold(stringResource(Res.string.vault_import_certificate), stringResource(Res.string.vault_dialog_import_subtitle), onDismiss) {
        DialogField(stringResource(Res.string.vault_field_name), name, { name = it }, placeholder = stringResource(Res.string.vault_placeholder_name_cert))
        Box(Modifier.padding(top = 16.dp)) {
            DialogField(stringResource(Res.string.vault_field_private_key_pem), pem, { pem = it }, placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----", singleLine = false, keyboardType = KeyboardType.Password)
        }
        Box(Modifier.padding(top = 16.dp)) {
            DialogField(stringResource(Res.string.vault_field_certificate), certificate, { certificate = it }, placeholder = "ssh-…-cert-v01@openssh.com …", singleLine = false)
        }
        Box(Modifier.padding(top = 16.dp)) {
            DialogField(stringResource(Res.string.vault_field_passphrase), passphrase, { passphrase = it }, placeholder = stringResource(Res.string.vault_placeholder_optional), password = true)
        }
        when {
            certInvalid -> Txt(stringResource(Res.string.vault_cert_read_error), color = Skerry.colors.sunset, size = 11.sp, modifier = Modifier.padding(top = 12.dp))
            info != null -> {
                val principalsPart = if (info.principals.isEmpty()) stringResource(Res.string.vault_any_principal) else info.principals.joinToString(", ")
                Txt(
                    stringResource(Res.string.vault_cert_valid_summary, info.keyTypeLabel, principalsPart, info.validUntil),
                    color = Skerry.colors.moss, size = 11.sp, modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        DialogButtons(confirmLabel = stringResource(Res.string.vault_import), confirmEnabled = valid, onDismiss = onDismiss, onConfirm = { onCreate(name.trim(), pem.trim(), certificate.trim(), passphrase.ifBlank { null }) })
    }
}

/** One ref line in the detail panel, in monospace, with a note when the file isn't readable here. */
@Composable
internal fun RefRow(ref: String, missing: Boolean, mono: FontFamily) {
    Txt(ref, color = Skerry.colors.textBright, size = 11.sp, font = mono, modifier = Modifier.padding(bottom = if (missing) 4.dp else 16.dp))
    if (missing) {
        Txt(stringResource(Res.string.vault_key_file_missing), color = Skerry.colors.sunset, size = 11.sp, modifier = Modifier.padding(bottom = 16.dp))
    }
}

/**
 * Links a key (and optionally a certificate) that stays on disk. Unlike [ImportCertificateDialog]
 * nothing is read here: only the locations are kept, which is what lets a short-lived certificate
 * keep working while its issuer rewrites the file.
 *
 * The certificate field may be left empty — the hint spells out which sibling would be used instead,
 * or that there'd be no certificate at all for a ref with no siblings to guess at (an Android
 * document Uri).
 */
@Composable
internal fun LinkKeyFileDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, keyRef: String, certificateRef: String?, passphrase: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var keyRef by remember { mutableStateOf("") }
    var certRef by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val valid = name.isNotBlank() && keyRef.isNotBlank()
    val browseTitle = stringResource(Res.string.vault_link_key_file)

    VaultDialogScaffold(stringResource(Res.string.vault_link_key_file), stringResource(Res.string.vault_dialog_key_file_subtitle), onDismiss) {
        DialogField(stringResource(Res.string.vault_field_name), name, { name = it }, placeholder = stringResource(Res.string.vault_placeholder_name_key_file))
        Box(Modifier.padding(top = 16.dp)) {
            RefField(stringResource(Res.string.vault_field_key_path), keyRef, { keyRef = it }, "~/.ssh/id_ed25519") {
                scope.launch { pickSecretFileRef(browseTitle)?.let { keyRef = it } }
            }
        }
        Box(Modifier.padding(top = 16.dp)) {
            RefField(stringResource(Res.string.vault_field_cert_path), certRef, { certRef = it }, "~/.ssh/id_ed25519-cert.pub") {
                scope.launch { pickSecretFileRef(browseTitle)?.let { certRef = it } }
            }
        }
        if (certRef.isBlank()) {
            val sibling = keyFileSiblingRef(keyRef)
            Txt(
                if (sibling != null) stringResource(Res.string.vault_hint_cert_sibling, sibling)
                else stringResource(Res.string.vault_hint_cert_sibling_opaque),
                color = Skerry.colors.faint, size = 11.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
        Box(Modifier.padding(top = 16.dp)) {
            DialogField(stringResource(Res.string.vault_field_passphrase), passphrase, { passphrase = it }, placeholder = stringResource(Res.string.vault_placeholder_optional), password = true)
        }
        DialogButtons(
            confirmLabel = stringResource(Res.string.vault_link),
            confirmEnabled = valid,
            onDismiss = onDismiss,
            onConfirm = { onCreate(name.trim(), keyRef.trim(), certRef.trim().ifBlank { null }, passphrase.ifBlank { null }) },
        )
    }
}

/** A ref field with a "Browse…" button next to it; the path stays editable by hand (pasted or typed). */
@Composable
private fun RefField(label: String, value: String, onValueChange: (String) -> Unit, placeholder: String, onBrowse: () -> Unit) {
    Column {
        DialogField(label, value, onValueChange, placeholder = placeholder)
        GhostButton(stringResource(Res.string.vault_browse), onClick = onBrowse, modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * Master-password re-authentication before copying a password to the clipboard (shared by desktop and
 * mobile keychain — the no-biometrics path). On wrong input [error] shows an error and the form stays
 * open for another attempt. The field clears when the dialog is recreated.
 */
@Composable
internal fun PasswordConfirmDialog(error: Boolean, busy: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    VaultDialogScaffold(stringResource(Res.string.vault_confirm_master_title), stringResource(Res.string.vault_confirm_master_subtitle), onDismiss) {
        DialogField(stringResource(Res.string.vault_field_master_password), password, { password = it }, placeholder = stringResource(Res.string.vault_placeholder_master_password), password = true)
        if (error) Txt(stringResource(Res.string.vault_password_mismatch_retry), color = Skerry.colors.sunset, size = 11.sp, modifier = Modifier.padding(top = 12.dp))
        // confirmEnabled is disabled while verifying (Argon2id) — otherwise a double-tap would run it twice.
        DialogButtons(confirmLabel = stringResource(Res.string.vault_copy), confirmEnabled = password.isNotEmpty() && !busy, onDismiss = onDismiss, onConfirm = { onConfirm(password) })
    }
}

/**
 * Renames a keychain secret: a single prefilled NAME field. The secret material and id are untouched
 * (only the label changes). Confirm is enabled only for a non-blank label that actually differs — a
 * rename to the same name is a pointless sync push. Shared by desktop and mobile. [onConfirm] gets the
 * trimmed label.
 */
@Composable
internal fun RenameSecretDialog(currentLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(currentLabel) }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() && trimmed != currentLabel
    VaultDialogScaffold(stringResource(Res.string.vault_rename_title, currentLabel), null, onDismiss) {
        // The old label arrives prefilled: select it so typing replaces the name outright.
        DialogField(stringResource(Res.string.vault_field_name), name, { name = it }, placeholder = currentLabel, selectAllOnFocus = name == currentLabel)
        DialogButtons(confirmLabel = stringResource(Res.string.vault_rename), confirmEnabled = valid, onDismiss = onDismiss, onConfirm = { onConfirm(trimmed) })
    }
}

@Composable
internal fun DeleteSecretDialog(label: String, boundHostCount: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    VaultDialogScaffold(stringResource(Res.string.vault_delete_title, label), null, onDismiss) {
        val detail = if (boundHostCount == 0) {
            stringResource(Res.string.vault_delete_detail_none)
        } else {
            stringResource(Res.string.vault_delete_detail_bound, boundHostCount)
        }
        Txt(detail, color = Skerry.colors.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 4.dp))
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            CancelButton(stringResource(Res.string.vault_cancel), onClick = onDismiss)
            PrimaryButton(stringResource(Res.string.vault_delete), onClick = onConfirm, bg = Skerry.colors.sunset, fg = Skerry.colors.sunsetInk)
        }
    }
}

@Composable
internal fun VaultDialogScaffold(title: String, subtitle: String?, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    // System back/gesture closes the dialog (same as tapping the scrim). Intercepts back before the
    // shell's navigation on Android (dispatcher LIFO); BackHandler without a dispatcher is a no-op on desktop.
    PlatformBackHandler(onBack = onDismiss)
    Box(
        // The dialog centers in the visible area; it ends up above the keyboard on its own — on mobile the
        // root `safeDrawing` shrinks the area above the IME, and `Center` centers within what's left (no-op on desktop).
        Modifier.fillMaxSize().background(Skerry.colors.modalScrim).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
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
                // Absorbs the click on the card so it doesn't close the dialog (same as DesktopPasswordDialog).
                // indication = null: the card is a static surface, not a button — no hover/press highlight.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                // Scrolls the content: a tall dialog (certificate import — 4 fields) doesn't fit under the
                // on-screen keyboard; scrolling keeps the fields and buttons reachable. No-op on desktop.
                .verticalScroll(rememberScrollState())
                .padding(26.dp),
        ) {
            Txt(title, color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
            if (subtitle != null) Txt(subtitle, color = Skerry.colors.dim, size = 12.5.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            else Spacer(Modifier.padding(top = 8.dp))
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DialogField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = false,
    singleLine: Boolean = true,
    // Explicit keyboard type override: for visible but sensitive fields (PEM key) set
    // KeyboardType.Password — disables IME autocorrect/dictionary without visually masking input.
    keyboardType: KeyboardType? = null,
    /** See `ModalTextField`: select the prefilled value on focus so the next keystroke replaces it. */
    selectAllOnFocus: Boolean = false,
) {
    val ui = LocalFonts.current.ui
    val mono = LocalFonts.current.mono
    // Multi-line fields (PEM/certificate) use monospace so long blobs read like a file.
    val textColor = Skerry.colors.text
    val style = remember(ui, mono, singleLine, textColor) {
        TextStyle(color = textColor, fontSize = if (singleLine) 13.sp else 11.sp, fontFamily = if (singleLine) ui else mono)
    }
    // Auto-scroll to focus above the keyboard. The window in adjustResize mode (see AndroidManifest)
    // shrinks itself when the keyboard appears, so WindowInsets.ime is always 0 here — observing the inset
    // is useless, and a single bring-into-view would fire BEFORE the window resize and miss. Instead,
    // while the field is focused, it re-requests bring-into-view on EVERY frame for the first ~450ms
    // (keyboard/resize animation duration) — the field is guaranteed to end up above the keyboard
    // regardless of window mode. No keyboard on desktop: a couple of bringIntoView frames are harmless (no-op).
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    // Gap below the field during auto-scroll — focus surfaces above the keyboard with breathing room, not flush.
    val marginPx = with(LocalDensity.current) { 16.dp.toPx() }
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        val start = withFrameNanos { it }
        var now = start
        while (now - start < 450_000_000L) {
            val s = fieldSize
            requester.bringIntoView(
                if (s == IntSize.Zero) null
                else Rect(0f, 0f, s.width.toFloat(), s.height.toFloat() + marginPx),
            )
            now = withFrameNanos { it }
        }
    }
    val draft = rememberFieldDraft(value, selectAllOnFocus, masked = password, singleLine = singleLine)
    Column {
        Txt(label, color = Skerry.colors.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(bottom = 5.dp))
        // Capsule/padding live in decorationBox so a click anywhere in the field (incl. the empty area
        // below the caret in multi-line PEM/certificate fields) places the caret.
        BasicTextField(
            value = draft.textFieldValue(value),
            onValueChange = { draft.accept(it, value, onValueChange) },
            modifier = Modifier.fillMaxWidth().fieldFocus(draft).onFocusChanged { focused = it.isFocused },
            singleLine = singleLine,
            textStyle = style,
            cursorBrush = SolidColor(Skerry.colors.cyan),
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Done else ImeAction.Default, keyboardType = keyboardType ?: if (password) KeyboardType.Password else KeyboardType.Text),
            keyboardActions = KeyboardActions(),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().bringIntoViewRequester(requester).onSizeChanged { fieldSize = it }.clip(RoundedCornerShape(7.dp)).background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp))
                        .then(if (singleLine) Modifier else Modifier.heightIn(min = 72.dp, max = 132.dp))
                        .padding(horizontal = 11.dp, vertical = 10.dp)
                        .then(if (singleLine) Modifier else Modifier.verticalScroll(rememberScrollState())),
                ) {
                    if (value.isEmpty()) Txt(placeholder, color = Skerry.colors.faint, size = if (singleLine) 13.sp else 11.sp, font = if (singleLine) ui else mono)
                    inner()
                }
            },
        )
    }
}

@Composable
internal fun DialogButtons(confirmLabel: String, confirmEnabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CancelButton(stringResource(Res.string.vault_cancel), onClick = onDismiss)
        PrimaryButton(confirmLabel, onClick = onConfirm, enabled = confirmEnabled)
    }
}

// Mock path (offscreen render/preview): the same sidebar, rows and panel over sample secrets.
