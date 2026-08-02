package app.skerry.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.theme.Skerry

/**
 * One fact in a detail panel: a dim label on the left, its value in monospace on the right, both on
 * a single line — the type/fingerprint/added rows of the Vault panel. Two older rows of the same
 * shape (`InfoRow` in the session info panel, `CardRow` in the tunnel dashboard) still spell out
 * their own spacing; converging them onto this one is a separate change, noted in the guidelines.
 *
 * The value is right-aligned and elided rather than wrapped — a panel of facts stays a scannable
 * column, and a value too long to fit (a full public key, a path) belongs in a code block, not here.
 */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Skerry.colors.text,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, color = Skerry.colors.dim, size = 12.sp, maxLines = 1)
        Txt(
            value,
            modifier = Modifier.weight(1f, fill = false),
            color = valueColor,
            size = 11.5.sp,
            font = LocalFonts.current.mono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            align = TextAlign.End,
        )
    }
}
