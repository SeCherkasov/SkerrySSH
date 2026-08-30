package app.skerry.ui.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.folderLabel
import app.skerry.ui.theme.Skerry

/**
 * Collapsible category/group header in snippet and runbook palettes and lists:
 * chevron + folder icon + folder label + count badge.
 */
@Composable
internal fun SnippetCategoryHeader(
    category: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = folderLabel(category),
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Sym(if (collapsed) "chevron_right" else "expand_more", size = 16.sp, color = Skerry.colors.faint)
        Sym("folder_open", size = 14.sp, color = Skerry.colors.cyanBright)
        Txt(
            label,
            color = Skerry.colors.dim,
            size = 12.5.sp,
            weight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Skerry.colors.card).padding(horizontal = 6.dp, vertical = 1.dp)) {
            Txt(count.toString(), color = Skerry.colors.faint, size = 10.sp)
        }
    }
}
