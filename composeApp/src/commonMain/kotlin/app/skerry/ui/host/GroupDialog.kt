package app.skerry.ui.host

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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_group_rename_title
import app.skerry.ui.generated.resources.shell_group_new_title
import app.skerry.ui.generated.resources.shell_group_rename_subtitle
import app.skerry.ui.generated.resources.shell_group_new_subtitle
import app.skerry.ui.generated.resources.shell_group_name_placeholder
import app.skerry.ui.generated.resources.shell_group_delete
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.generated.resources.shell_save
import app.skerry.ui.generated.resources.shell_create
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.D
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Txt

/**
 * Диалог создания/правки группы хостов: одно поле имени + кнопки. [onDelete] != null — режим правки
 * (показывается кнопка «Delete group», разгруппировывающая хосты группы). Сохранение недоступно при
 * пустом имени. Стиль — скрим + карточка макета, как [DesktopDeleteHostDialog]/[ConfirmActionDialog].
 */
@Composable
fun GroupDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val editing = onDelete != null
    var name by remember { mutableStateOf(initialName) }
    val noop = remember { MutableInteractionSource() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val canSave = name.trim().isNotEmpty()
    val save = { if (canSave) onSave(name) }
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
            Txt(
                if (editing) stringResource(Res.string.shell_group_rename_title) else stringResource(Res.string.shell_group_new_title),
                color = D.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
            )
            Txt(
                if (editing) stringResource(Res.string.shell_group_rename_subtitle)
                else stringResource(Res.string.shell_group_new_subtitle),
                color = D.dim, size = 12.5.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            // Рамку/плейсхолдер кладём в decorationBox + fillMaxWidth, чтобы клик по всей площади ставил
            // каретку (правило для рукописных полей проекта).
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = TextStyle(color = D.text, fontSize = 13.sp, fontFamily = LocalFonts.current.ui),
                cursorBrush = SolidColor(D.cyan),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                decorationBox = { inner ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0x08FFFFFF))
                            .border(1.dp, D.line, RoundedCornerShape(7.dp))
                            .padding(horizontal = 11.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.fillMaxWidth()) {
                            if (name.isEmpty()) Txt(stringResource(Res.string.shell_group_name_placeholder), color = D.faint, size = 13.sp)
                            inner()
                        }
                    }
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onDelete != null) {
                    Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onDelete).padding(horizontal = 14.dp, vertical = 9.dp)) {
                        Txt(stringResource(Res.string.shell_group_delete), color = D.sunset, size = 12.5.sp)
                    }
                }
                Box(Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Txt(stringResource(Res.string.shell_cancel), color = D.dim, size = 12.5.sp)
                }
                PrimaryButton(if (editing) stringResource(Res.string.shell_save) else stringResource(Res.string.shell_create), onClick = save, enabled = canSave)
            }
        }
    }
}
