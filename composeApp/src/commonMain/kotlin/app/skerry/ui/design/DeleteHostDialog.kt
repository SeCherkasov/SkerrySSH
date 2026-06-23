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
import app.skerry.shared.host.Host
import app.skerry.ui.connection.connectionSubtitle

/**
 * Диалог подтверждения удаления профиля хоста (вызывается из контекстного меню сайдбара). Удаляется
 * только сама запись каталога; привязанный keychain-секрет ([Host.credentialId]) остаётся в vault —
 * он переиспользуемый (один ключ/пароль на несколько хостов) и управляется во вкладке Vault, поэтому
 * каскадного удаления секрета здесь нет (в отличие от обратного направления в `VaultView`). Стиль —
 * скрим + карточка макета, как [DesktopPasswordDialog].
 */
@Composable
fun DesktopDeleteHostDialog(host: Host, onDismiss: () -> Unit, onConfirm: () -> Unit) {
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
            Txt("Delete \"${host.label}\"?", color = D.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
            Txt(host.connectionSubtitle(), color = D.dim, size = 12.5.sp, font = LocalFonts.current.mono, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            Txt(
                "This connection profile is removed from your archipelago. Any stored credential stays in your vault. This can't be undone.",
                color = D.dim, size = 12.5.sp, lineHeight = 18.sp,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Txt("Cancel", color = D.dim, size = 12.5.sp)
                }
                PrimaryButton("Delete", onClick = onConfirm, bg = D.sunset, fg = Color(0xFF1A0B07))
            }
        }
    }
}
