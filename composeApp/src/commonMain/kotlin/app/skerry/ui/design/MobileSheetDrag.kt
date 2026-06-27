package app.skerry.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Перетаскивание мобильного нижнего листа за хват. Тянем вниз — лист едет за пальцем; отпускание
 * за порогом плавно доезжает вниз за край и закрывается ([onDismiss]), недотянутый свайп пружинно
 * возвращается к нулю. Вверх лист не уезжает (`coerceAtLeast 0`), а жест висит только на зоне хвата
 * ([SheetDrag.handle]), чтобы не конфликтовать со скроллом содержимого.
 *
 * Применение: [SheetDrag.sheet] — на контейнер листа (смещение + замер высоты для доезда вниз),
 * [SheetDrag.handle] — на Box с полоской-хватом (ловит жест).
 */
@Composable
fun rememberSheetDrag(onDismiss: () -> Unit, dismissThreshold: Dp = 96.dp): SheetDrag {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }
    val thresholdPx = with(density) { dismissThreshold.toPx() }
    return remember(thresholdPx, onDismiss) { SheetDrag(dragY, scope, thresholdPx, onDismiss) }
}

class SheetDrag internal constructor(
    private val dragY: Animatable<Float, AnimationVector1D>,
    private val scope: CoroutineScope,
    private val thresholdPx: Float,
    private val onDismiss: () -> Unit,
) {
    // Высота листа в px — чтобы при закрытии доехать ровно за нижний край, а не дёрнуться.
    private var heightPx by mutableStateOf(0f)
    private var dismissing = false

    /** Навесить на контейнер листа: вертикальное смещение при перетаскивании + замер высоты. */
    val sheet: Modifier = Modifier
        .offset { IntOffset(0, dragY.value.roundToInt()) }
        .onSizeChanged { heightPx = it.height.toFloat() }

    /** Навесить на зону хвата: ловит вертикальный жест перетаскивания. */
    val handle: Modifier = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onVerticalDrag = { change, dy ->
                if (dismissing) return@detectVerticalDragGestures
                change.consume()
                scope.launch { dragY.snapTo((dragY.value + dy).coerceAtLeast(0f)) }
            },
            onDragEnd = {
                if (dismissing) return@detectVerticalDragGestures
                if (dragY.value > thresholdPx) {
                    dismissing = true
                    scope.launch {
                        // Доезжаем вниз за край листа коротким tween — затем снимаем composable.
                        val target = if (heightPx > 0f) heightPx else dragY.value + thresholdPx
                        dragY.animateTo(target, tween(durationMillis = 200))
                        onDismiss()
                    }
                } else {
                    // Недотянули — мягкая пружина обратно к нулю, без отскока.
                    scope.launch {
                        dragY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
                    }
                }
            },
        )
    }
}
