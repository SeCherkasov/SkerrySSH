package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Одно действие нижнего листа-меню: подпись + опц. иконка; [danger] красит в коралл (деструктивное). */
data class MobileSheetAction(
    val label: String,
    val onClick: () -> Unit,
    val icon: String? = null,
    val danger: Boolean = false,
)

/** Кладёт лист в левый-верхний угол окна — контент сам растягивается на весь экран. */
private val FullWindowPosition = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

/**
 * Единый нижний лист-меню контекстных действий (Forget key / Edit-Remove / Disconnect и т. п.) в той
 * же идиоме, что листы New connection / Vault: затемнение на весь экран, панель снизу с хватом-полоской
 * и drag-to-dismiss ([MobileBottomSheet]). Действия рендерятся полноширинными кнопками
 * ([MobileSheetButton]) — как кнопки Copy/Delete листа Vault, а не плоскими строками: первое
 * недеструктивное действие залито cyan (primary), остальные — контурные, деструктивные — коралловый контур.
 *
 * Обёрнут в полноэкранный [Popup], поэтому вставляется как `target?.let { MobileActionSheet(...) }` в
 * любом месте дерева — затемнение всегда кроет весь экран, а не только строку-источник. Тап по кнопке
 * закрывает лист и затем выполняет [MobileSheetAction.onClick]; тап мимо/свайп вниз/Back — [onDismiss].
 */
@Composable
fun MobileActionSheet(
    title: String,
    actions: List<MobileSheetAction>,
    onDismiss: () -> Unit,
    subtitle: String? = null,
) {
    // Первое недеструктивное действие — primary (залитая кнопка); danger никогда не заливаем.
    val primaryIndex = actions.indexOfFirst { !it.danger }
    Popup(
        popupPositionProvider = FullWindowPosition,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        MobileBottomSheet(onDismiss = onDismiss, panelModifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Txt(title, color = D.text, size = 15.sp, weight = FontWeight.SemiBold)
            if (subtitle != null) {
                Txt(subtitle, color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                actions.forEachIndexed { index, action ->
                    MobileSheetButton(
                        label = action.label,
                        onClick = { onDismiss(); action.onClick() },
                        modifier = Modifier.fillMaxWidth(),
                        icon = action.icon,
                        filled = index == primaryIndex,
                        danger = action.danger,
                    )
                }
            }
        }
    }
}

/**
 * Кнопка действий нижнего листа в мобильной метрике (паритет с [MobileNewConnectionSheet]): 12-dp
 * скругление, vertical 13-dp, 15-sp — крупный тач-таргет, а не desktop-[PrimaryButton] (8-dp/12-sp),
 * который на телефоне выглядел мелким. [filled] — залитая cyan; иначе контурная. [danger] красит
 * контур/текст в [D.sunset] (удаление). Общая для листов Vault и [MobileActionSheet].
 */
@Composable
internal fun MobileSheetButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    filled: Boolean = true,
    danger: Boolean = false,
) {
    val fg = when {
        filled -> Color(0xFF0A1A26)
        danger -> D.sunset
        else -> D.text
    }
    val base = Modifier.clip(RoundedCornerShape(12.dp))
        .then(if (filled) Modifier.background(D.cyan) else Modifier.border(1.dp, if (danger) D.sunset.copy(alpha = 0.3f) else D.cyan14, RoundedCornerShape(12.dp)))
    Row(
        modifier.then(base)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Sym(icon, size = 17.sp, color = fg)
        Txt(label, color = fg, size = 15.sp, weight = if (filled) FontWeight.Bold else FontWeight.Medium)
    }
}
