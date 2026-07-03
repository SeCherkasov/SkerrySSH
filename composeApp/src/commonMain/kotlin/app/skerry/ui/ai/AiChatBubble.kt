package app.skerry.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ai.AiRole
import app.skerry.ui.design.D
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt

/**
 * Кликабельный заголовок сворачиваемого quick-chat (настройки AI, desktop и mobile): название,
 * подпись и шеврон. Чат по умолчанию свёрнут — служебный инструмент проверки связи не должен
 * постоянно занимать форму.
 */
@Composable
internal fun AiQuickChatHeader(title: String, desc: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onToggle).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt(desc, color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Sym(if (open) "expand_less" else "expand_more", size = 16.sp, color = D.faint)
    }
}

/**
 * Пузырь реплики quick-chat в настройках AI: user — справа с cyan-тоном, assistant — слева
 * приглушённый. Один на desktop-секцию и мобильный экран (раньше жил двумя копиями).
 */
@Composable
internal fun AiChatBubble(role: AiRole, text: String) {
    val mine = role == AiRole.USER
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier.clip(RoundedCornerShape(8.dp))
                .background(if (mine) D.cyan10 else Color(0x0DFFFFFF))
                .border(1.dp, if (mine) D.cyan14 else D.line, RoundedCornerShape(8.dp))
                .padding(horizontal = 11.dp, vertical = 8.dp),
        ) {
            Txt(text, color = if (mine) D.text else D.dim, size = 12.5.sp, lineHeight = 18.sp)
        }
    }
}
