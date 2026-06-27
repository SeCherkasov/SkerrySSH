package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Канонические токены мобильного нижнего листа («New connection»): панель и затемнение. */
internal val SheetPanel = Color(0xFF0E1B26)
private val SheetScrim = Color(0x8C04080C)

/**
 * Единая обвязка мобильного нижнего листа: затемнение на весь экран ([SheetScrim], тап мимо — закрыть),
 * панель снизу со скруглением 26dp ([SheetPanel]), хват-полоской и drag-to-dismiss ([rememberSheetDrag]
 * / [SheetHandle]). Контейнер владеет ТОЛЬКО хромом — высоту, прокрутку, паддинги и `imePadding`
 * конкретный лист задаёт через [panelModifier], поэтому форма/детали/меню переиспользуют один скелет,
 * не теряя своих нюансов вёрстки.
 *
 * Тап по самой панели гасится (лист не закрывается), [content] идёт сразу под хватом в [ColumnScope]
 * панели — внутри доступен `Modifier.weight`/`verticalScroll` и пр.
 *
 * @param maxHeightFraction если задан — высота панели ограничена долей экрана (детальный лист, чтобы
 *   длинный контент скроллился, а не вылезал за верх); null — высота определяется содержимым/панель-модификатором.
 */
@Composable
fun MobileBottomSheet(
    onDismiss: () -> Unit,
    panelModifier: Modifier = Modifier,
    maxHeightFraction: Float? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(SheetScrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val drag = rememberSheetDrag(onDismiss)
        val cap = maxHeightFraction?.let { Modifier.heightIn(max = maxHeight * it) } ?: Modifier
        Column(
            Modifier
                .fillMaxWidth()
                .then(cap)
                .then(drag.sheet)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(SheetPanel)
                // Гасим клик по панели, чтобы тап по листу не закрывал его (закрытие — только скрим/свайп).
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .then(panelModifier),
        ) {
            SheetHandle(drag)
            content()
        }
    }
}
