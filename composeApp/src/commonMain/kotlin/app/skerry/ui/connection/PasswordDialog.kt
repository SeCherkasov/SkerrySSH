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
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_connect_to
import app.skerry.ui.generated.resources.shell_password_caps
import app.skerry.ui.generated.resources.shell_password_host_placeholder
import app.skerry.ui.generated.resources.shell_not_stored_once
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.generated.resources.shell_connect
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.D
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt

/**
 * Диалог ввода пароля при подключении к хосту без привязанной identity (паритет с мобильным
 * `PasswordSheet`). Пароль не сохраняется: уходит прямиком в [onConnect] как разовый секрет сессии.
 * Стиль — скрим + карточка макета, как [NewConnectionModal].
 */
@Composable
fun DesktopPasswordDialog(host: Host, onDismiss: () -> Unit, onConnect: (String) -> Unit) {
    val noop = remember { MutableInteractionSource() }
    var password by remember { mutableStateOf("") }
    val submit = { if (password.isNotEmpty()) onConnect(password) }

    Box(
        Modifier.fillMaxSize().background(Color(0xB3060E16)).clickable(interactionSource = noop, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(D.surfaceDeep)
                .border(1.dp, D.cyan14, RoundedCornerShape(12.dp))
                .clickable(interactionSource = noop, indication = null, onClick = {})
                .padding(26.dp),
        ) {
            Txt(stringResource(Res.string.shell_connect_to, host.label), color = D.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
            Txt(host.connectionSubtitle(), color = D.dim, size = 12.5.sp, font = LocalFonts.current.mono, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))

            Txt(stringResource(Res.string.shell_password_caps), color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(bottom = 5.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 11.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Sym("key", size = 16.sp, color = D.faint)
                val ui = LocalFonts.current.ui
                val style = remember(ui) { TextStyle(color = D.text, fontSize = 13.sp, fontFamily = ui) }
                Box(Modifier.fillMaxWidth()) {
                    if (password.isEmpty()) Txt(stringResource(Res.string.shell_password_host_placeholder), color = D.faint, size = 13.sp)
                    BasicTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        textStyle = style,
                        cursorBrush = SolidColor(D.cyan),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Password),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sym("shield_lock", size = 14.sp, color = D.moss)
                    Txt(stringResource(Res.string.shell_not_stored_once), color = D.faint, size = 11.sp)
                }
                Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Txt(stringResource(Res.string.shell_cancel), color = D.dim, size = 12.5.sp)
                }
                PrimaryButton(stringResource(Res.string.shell_connect), onClick = submit, bg = if (password.isNotEmpty()) D.cyan else D.cyan.copy(alpha = 0.4f))
            }
        }
    }
}
