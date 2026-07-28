package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.Txt
import app.skerry.ui.design.avatarColor
import app.skerry.ui.sync.accountInitials
import app.skerry.ui.theme.Skerry

/**
 * Where a colleague is typing on a shared session: a caret bar in their colour rising into the cell
 * their keystrokes land in, with a name tag hanging under it (the line itself stays readable).
 *
 * Pinned to the cursor rather than parked in a corner, because on a shared shell the question is
 * *where* the other person is writing — a label in the corner only says *that* someone is. The
 * colour is the one their avatar carries in the share panel and the session directory, so the caret
 * is recognizable before the name is read.
 */
@Composable
internal fun CollaboratorCaret(account: String, modifier: Modifier = Modifier) {
    val color = avatarColor(account)
    Column(modifier, horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        // The bar comes first and points up into the cell the cursor is in; the tag hangs under it,
        // out of the way of the line being typed.
        Row(Modifier.size(width = CARET_BAR_WIDTH, height = CARET_BAR_HEIGHT).background(color)) {}
        Row(
            Modifier
                .background(color, RoundedCornerShape(topStart = 0.dp, topEnd = 5.dp, bottomEnd = 5.dp, bottomStart = 5.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Initials as well as the name: on a narrow pane the tag is what gets clipped first, and
            // two letters in a known colour still say who this is.
            Txt(accountInitials(account), color = Skerry.colors.ink, size = 8.sp, weight = FontWeight.Bold)
            Txt(account.substringBefore('@'), color = Skerry.colors.ink, size = 10.sp, weight = FontWeight.Medium)
        }
    }
}

private val CARET_BAR_WIDTH = 2.dp

/** Bar height: about one row, so it visibly reaches the cell above the tag. */
private val CARET_BAR_HEIGHT = 14.dp
