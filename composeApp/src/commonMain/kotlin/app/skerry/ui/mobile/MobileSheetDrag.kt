package app.skerry.ui.mobile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Drag-to-dismiss for a mobile bottom sheet via its handle. Dragging down follows the finger; on
 * release, the outcome depends on both position and gesture velocity: it slides down and dismisses
 * ([onDismiss]) past the threshold or on a sharp downward flick, while a sharp upward move always
 * springs back to zero. The sheet never moves above zero (`coerceAtLeast 0`); the gesture is scoped
 * to the handle area ([SheetDrag.handle]) to avoid conflicting with content scroll.
 *
 * Usage: [SheetDrag.sheet] on the sheet container (offset + height measurement for the dismiss
 * animation), [SheetDrag.handle] on the handle-bar Box (captures the gesture).
 */
@Composable
fun rememberSheetDrag(onDismiss: () -> Unit, dismissThreshold: Dp = 96.dp): SheetDrag {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }
    val thresholdPx = with(density) { dismissThreshold.toPx() }
    // Downward flick velocity (px/s) above which the gesture dismisses on its own, without reaching the threshold.
    val flingVelocityPx = with(density) { 450.dp.toPx() }
    // Upward velocity (px/s) above which the gesture always cancels dismissal, even past the threshold.
    val cancelVelocityPx = with(density) { 250.dp.toPx() }
    return remember(thresholdPx, flingVelocityPx, cancelVelocityPx, onDismiss) {
        SheetDrag(dragY, scope, thresholdPx, flingVelocityPx, cancelVelocityPx, onDismiss)
    }
}

class SheetDrag internal constructor(
    private val dragY: Animatable<Float, AnimationVector1D>,
    private val scope: CoroutineScope,
    private val thresholdPx: Float,
    private val flingVelocityPx: Float,
    private val cancelVelocityPx: Float,
    private val onDismiss: () -> Unit,
) {
    // Sheet height in px, so the dismiss animation ends exactly past the bottom edge.
    private var heightPx by mutableStateOf(0f)
    private var dismissing = false

    /** Applied to the sheet container: vertical offset while dragging plus height measurement. */
    val sheet: Modifier = Modifier
        .offset { IntOffset(0, dragY.value.roundToInt()) }
        .onSizeChanged { heightPx = it.height.toFloat() }

    /** Applied to the handle area: captures the vertical drag gesture. */
    val handle: Modifier = Modifier.pointerInput(Unit) {
        val tracker = VelocityTracker()
        detectVerticalDragGestures(
            onDragStart = {
                tracker.resetTracking()
                // Stop an in-flight spring-back animation so it doesn't fight the new gesture.
                scope.launch { dragY.stop() }
            },
            onVerticalDrag = { change, dy ->
                if (dismissing) return@detectVerticalDragGestures
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                scope.launch { dragY.snapTo((dragY.value + dy).coerceAtLeast(0f)) }
            },
            onDragEnd = {
                if (dismissing) return@detectVerticalDragGestures
                // y > 0 is downward, y < 0 is upward. A clear upward move always cancels dismissal
                // (even past the threshold); otherwise dismiss on a downward flick or past the threshold.
                val velocityY = tracker.calculateVelocity().y
                val movingUp = velocityY < -cancelVelocityPx
                val flungDown = velocityY > flingVelocityPx
                val pulledFar = dragY.value > thresholdPx
                if (!movingUp && (flungDown || pulledFar)) {
                    dismissing = true
                    scope.launch {
                        val target = if (heightPx > 0f) heightPx else dragY.value + thresholdPx
                        dragY.animateTo(target, tween(durationMillis = 200), initialVelocity = velocityY)
                        onDismiss()
                    }
                } else {
                    scope.launch {
                        dragY.animateTo(
                            0f,
                            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                            initialVelocity = velocityY,
                        )
                    }
                }
            },
            onDragCancel = {
                if (dismissing) return@detectVerticalDragGestures
                scope.launch {
                    dragY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
                }
            },
        )
    }
}

/**
 * Shared bottom sheet handle: full-width touch zone (captures [SheetDrag.handle]) and a centered
 * bar of consistent size/color/radius across mobile sheets. Owns its own vertical padding, so the
 * sheet's column needs no top padding for the handle.
 */
@Composable
fun SheetHandle(drag: SheetDrag, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().then(drag.handle).padding(top = 10.dp, bottom = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 38.dp, height = 5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x2EFFFFFF)),
        )
    }
}
