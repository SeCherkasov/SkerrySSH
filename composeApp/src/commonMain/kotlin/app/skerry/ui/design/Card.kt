package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.theme.Skerry

/**
 * A titled panel of a dashboard: the tunnels overview and the host monitor are both built out of
 * these, so the two read as the same surface rather than two designers' takes on a box.
 *
 * The title is optional — a card that is one table needs no heading over the table's own header row.
 */
@Composable
fun Card(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(CARD_RADIUS))
            .background(Skerry.colors.surface2)
            .border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(CARD_RADIUS))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (title != null) CardTitle(title)
        content()
    }
}

/** Heading of a [Card], also usable on a block inside one. */
@Composable
fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Txt(text, color = Skerry.colors.text, size = 12.sp, weight = FontWeight.SemiBold, modifier = modifier)
}

private val CARD_RADIUS = 10.dp
