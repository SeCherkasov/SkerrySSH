package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Оверлей блокировки `docs/new/Skerry.html`: радиальный фон, крупный логотип, поле мастер-пароля,
 * кнопки Unlock + биометрия (отпечаток) и подпись про zero-knowledge. Перенос 1:1; реальная
 * разблокировка/биометрия подключается отдельно (тут [DesktopDesignState.unlock] — заглушка).
 */
@Composable
fun LockScreen(state: DesktopDesignState) {
    var pwd by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(colors = listOf(Color(0xFF122332), D.bg)))
            .clickable(enabled = false) {}
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF142634), Color(0xFF0A141B), Color(0xFF05090D)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            BrandMark(size = 88.dp)
        }
        Box(Modifier.height(22.dp))
        Txt("Skerry is locked", color = D.text, size = 22.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
        Box(Modifier.height(6.dp))
        Txt("Enter your master password to unlock the harbor", color = D.dim, size = 13.sp)
        Box(Modifier.height(32.dp))
        Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(D.surface2)
                    .border(1.dp, D.cyan14, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Sym("lock", size = 18.sp, color = D.faint)
                Box(Modifier.weight(1f)) {
                    if (pwd.isEmpty()) Txt("Master password", color = D.faint, size = 14.sp)
                    BasicTextField(
                        value = pwd,
                        onValueChange = { pwd = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = TextStyle(color = D.text, fontSize = 14.sp, fontFamily = LocalFonts.current.ui),
                        cursorBrush = SolidColor(D.cyan),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Unlock", onClick = state::unlock, modifier = Modifier.weight(1f))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, D.cyan14, RoundedCornerShape(8.dp))
                        .clickable(onClick = state::unlock)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Sym("fingerprint", size = 16.sp, color = D.dim)
                }
            }
        }
        Box(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("shield_lock", size = 14.sp, color = D.faint)
            Txt("Master password never leaves this device", color = D.faint, size = 11.sp)
        }
    }
}
