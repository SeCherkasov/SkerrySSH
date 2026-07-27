package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.sync.accountInitials
import app.skerry.ui.theme.Skerry

/**
 * Round avatar with an account's initials — who is on a session, at a glance. Skerry stores no
 * profile pictures (an image would have to live on the sync server in the clear, which the
 * zero-knowledge model does not allow), so the identity is drawn from the account id itself:
 * initials from its local part, colour picked deterministically so the same colleague keeps the
 * same colour on every device.
 */
@Composable
fun InitialsAvatar(accountId: String, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val tint = avatarColor(accountId)
    Box(
        modifier.size(size).background(tint.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Txt(
            accountInitials(accountId),
            color = tint,
            size = (size.value * 0.42f).sp,
            weight = FontWeight.Medium,
        )
    }
}

/**
 * The avatar's colour for [accountId]: a stable pick from the theme's accents, so a colleague is
 * recognizable by colour without anything being stored about them. Theme tokens only — an avatar
 * must follow the palette like everything else.
 */
@Composable
fun avatarColor(accountId: String): Color {
    val palette = listOf(
        Skerry.colors.cyanBright,
        Skerry.colors.amber,
        Skerry.colors.moss,
        Skerry.colors.tealLight,
        Skerry.colors.sunset,
    )
    // A plain sum, not hashCode(): it must give the same colour on every platform and JVM version.
    val seed = accountId.fold(0) { acc, ch -> (acc + ch.code) % 4096 }
    return palette[seed % palette.size]
}
