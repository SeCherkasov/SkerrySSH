package app.skerry.ui.sftp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.files.FileItemType
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.files.fileDisplayName
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sftp_copy
import app.skerry.ui.generated.resources.sftp_drag_shift_move
import app.skerry.ui.generated.resources.sftp_items_count
import app.skerry.ui.generated.resources.sftp_move
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** Where the chip sits relative to the pointer: below and right, clear of the cursor glyph. */
private val GHOST_GAP = 14.dp

private val GHOST_MAX_WIDTH = 320.dp

private val GHOST_SHAPE = RoundedCornerShape(8.dp)

/**
 * The chip that follows the pointer during a file drag: what is carried, whether the drop copies or
 * moves, and — over the other pane — the directory it lands in ([pathOf] that pane). [origin] is the window
 * position of the container it is drawn in, so the window-space pointer can be placed inside it.
 * Decorative for a screen reader: the drag is a mouse shortcut for F5/F6, which say all of this.
 */
@Composable
internal fun FileDragGhost(state: FileDragState, origin: Offset, mono: FontFamily, pathOf: (ActivePane) -> String) {
    val items = state.items
    val destPath = state.target?.let(pathOf)
    val single = items.singleOrNull()
    val onTarget = destPath != null
    Column(
        Modifier
            .offset {
                val gap = GHOST_GAP.roundToPx()
                val at = state.pointer - origin
                IntOffset(at.x.roundToInt() + gap, at.y.roundToInt() + gap)
            }
            .clearAndSetSemantics {}
            .widthIn(max = GHOST_MAX_WIDTH)
            .shadow(8.dp, GHOST_SHAPE)
            .background(Skerry.colors.panel, GHOST_SHAPE)
            .border(1.dp, if (onTarget) Skerry.colors.cyan else Skerry.colors.line, GHOST_SHAPE)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Sym(
                if (state.move) "drive_file_move" else "content_copy",
                size = 15.sp,
                color = if (onTarget) Skerry.colors.cyanBright else Skerry.colors.dim,
            )
            Txt(
                stringResource(if (state.move) Res.string.sftp_move else Res.string.sftp_copy),
                color = Skerry.colors.textBright,
                size = 12.sp,
                weight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (single != null) {
                Sym(sftpFileIcon(fileDisplayName(single.name), single.type), size = 14.sp, color = if (single.type == FileItemType.Directory) Skerry.colors.cyanBright else Skerry.colors.faint)
            }
            Txt(
                if (single != null) fileDisplayName(single.name) else stringResource(Res.string.sftp_items_count, items.size),
                color = Skerry.colors.text,
                size = 12.sp,
                font = mono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (destPath != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Sym("subdirectory_arrow_right", size = 13.sp, color = Skerry.colors.faint)
                Txt(destPath, color = Skerry.colors.dim, size = 11.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!state.move) {
            Txt(stringResource(Res.string.sftp_drag_shift_move), color = Skerry.colors.faint, size = 10.5.sp, maxLines = 1)
        }
    }
}

/**
 * Lights up a pane while a file drag from the other one is over it: a tint over its content and an
 * inset cyan frame, drawn on top so the listing's own row colours don't hide it.
 */
@Composable
internal fun Modifier.dropTargetHighlight(on: Boolean): Modifier {
    if (!on) return this
    val tint = Skerry.colors.cyan08
    val frame = Skerry.colors.cyan
    return drawWithContent {
        drawContent()
        drawRect(tint)
        val width = 2.dp.toPx()
        drawRect(
            frame,
            topLeft = Offset(width / 2, width / 2),
            size = size.copy(width = size.width - width, height = size.height - width),
            style = Stroke(width),
        )
    }
}
