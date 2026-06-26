package app.skerry.ui.design

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Универсальный диалог подтверждения деструктивного действия (разрыв сессии, закрытие split-панели,
 * удаление туннеля): скрим + карточка макета, заголовок + пояснение + Cancel/[confirmLabel]. Тот же
 * визуальный язык, что [DesktopDeleteHostDialog]/[DesktopPasswordDialog]; кнопка подтверждения по
 * умолчанию в [D.sunset] (опасное действие). [onDismiss] также срабатывает по клику вне карточки.
 */
@Composable
fun ConfirmActionDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmColor: Color = D.sunset,
) {
    val noop = remember { MutableInteractionSource() }
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
            Txt(title, color = D.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
            Txt(message, color = D.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Txt("Cancel", color = D.dim, size = 12.5.sp)
                }
                PrimaryButton(confirmLabel, onClick = onConfirm, bg = confirmColor, fg = Color(0xFF1A0B07))
            }
        }
    }
}
