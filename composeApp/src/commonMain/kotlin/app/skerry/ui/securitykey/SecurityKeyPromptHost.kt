package app.skerry.ui.securitykey

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import app.skerry.ui.design.rememberPromptFocus
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.Txt
import app.skerry.ui.design.sanitizeServerText
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.security_key_continue
import app.skerry.ui.generated.resources.security_key_local_only
import app.skerry.ui.generated.resources.security_key_pin_retries
import app.skerry.ui.generated.resources.security_key_pin_title
import app.skerry.ui.generated.resources.security_key_touch_instruction
import app.skerry.ui.generated.resources.security_key_touch_title
import app.skerry.ui.theme.Skerry
import app.skerry.ui.vault.DialogButtons
import app.skerry.ui.vault.DialogField
import app.skerry.ui.vault.VaultDialogScaffold
import org.jetbrains.compose.resources.stringResource

@Composable
fun SecurityKeyPromptHost(controller: SecurityKeyPromptController) {
    val question by controller.pending.collectAsState()
    question?.let { current ->
        key(current.id) {
            var pin by remember { mutableStateOf("") }
            val focus = remember { FocusRequester() }
            val prompt = rememberPromptFocus(focus, current.id)
            DisposableEffect(current.id) { onDispose { pin = "" } }
            VaultDialogScaffold(
                stringResource(if (current.pin) Res.string.security_key_pin_title else Res.string.security_key_touch_title),
                stringResource(Res.string.security_key_local_only),
                onDismiss = { pin = ""; controller.answer(current.id, null) },
                modifier = prompt,
            ) {
                Txt(sanitizeServerText(current.context.endpoint, maxChars = 512, allowNewlines = false), color = Skerry.colors.textBright, size = 12.sp)
                Txt(sanitizeServerText(current.context.fingerprint, maxChars = 512, allowNewlines = false), color = Skerry.colors.dim, size = 11.sp)
                Txt(sanitizeServerText(current.context.application, maxChars = 512, allowNewlines = false), color = Skerry.colors.dim, size = 11.sp)
                current.retries?.let { Txt(stringResource(Res.string.security_key_pin_retries, it), color = Skerry.colors.sunset, size = 11.sp) }
                if (current.pin) {
                    DialogField(stringResource(Res.string.security_key_pin_title), pin, { if (it.length <= 63) pin = it }, placeholder = "", password = true, modifier = Modifier.focusRequester(focus))
                } else {
                    Txt(stringResource(Res.string.security_key_touch_instruction), color = Skerry.colors.dim, size = 12.sp, modifier = Modifier.padding(top = 12.dp).focusRequester(focus).focusable())
                }
                DialogButtons(
                    confirmLabel = stringResource(Res.string.security_key_continue),
                    confirmEnabled = !current.pin || securityKeyPinValid(pin),
                    onDismiss = { pin = ""; controller.answer(current.id, null) },
                    onConfirm = { val answer = pin.toCharArray(); pin = ""; controller.answer(current.id, answer) },
                )
            }
        }
    }
}
