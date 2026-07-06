package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Modal overlay scrim: full-screen dimming, tap outside the card calls [onDismiss]; the card
 * centers (or uses [contentAlignment]). The card inside [content] must consume its own clicks
 * ([consumeClicks]), or tapping it would also dismiss the modal.
 */
@Composable
fun ModalScrim(
    onDismiss: () -> Unit,
    scrimColor: Color = D.modalScrim,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val noop = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(scrimColor)
            .clickable(interactionSource = noop, indication = null, onClick = onDismiss),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/** Consumes clicks on a modal card (no-op clickable, no indication) so taps don't reach the scrim. */
@Composable
fun Modifier.consumeClicks(): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
