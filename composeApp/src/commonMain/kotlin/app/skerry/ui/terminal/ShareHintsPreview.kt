package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.InitialsAvatar
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.share_control_asked
import app.skerry.ui.generated.resources.share_control_granted
import app.skerry.ui.generated.resources.share_control_request
import app.skerry.ui.generated.resources.share_control_wants
import app.skerry.ui.generated.resources.share_deny
import app.skerry.ui.generated.resources.share_grant
import app.skerry.ui.generated.resources.share_read_only
import app.skerry.ui.generated.resources.share_typing
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Sharing hints for a live session, floated over the terminal they are about: who is typing into
 * this shell, who is asking to be allowed to, and — on a viewer's side — whether typing is allowed.
 *
 * Over the terminal, not in a panel: this is what someone needs to see the moment a colleague
 * starts typing into their shell, and a notice they have to go find arrives after the command ran.
 * The action sits inside the same card as the line it answers.
 */
@Composable
internal fun ShareHintCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Skerry.colors.bannerScrim)
            .border(1.dp, Skerry.colors.cyan20, RoundedCornerShape(9.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** One line of a hint card: a colleague's avatar (or an icon) and its text. */
@Composable
internal fun ShareHintLine(text: String, account: String? = null, icon: String? = null, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            account != null -> InitialsAvatar(account, size = 20.dp)
            icon != null -> Sym(icon, size = 15.sp, color = tint)
        }
        Txt(text, color = tint, size = 12.sp)
    }
}
