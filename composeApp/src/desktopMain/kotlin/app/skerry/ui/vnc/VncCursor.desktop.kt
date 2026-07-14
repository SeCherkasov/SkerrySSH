package app.skerry.ui.vnc

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

// AWT has no "no cursor", so the blank one is a 1x1 fully transparent custom cursor — the standard
// way to hide the pointer over a Swing/AWT surface. Built once: createCustomCursor talks to the
// toolkit and the icon is immutable.
private val BLANK_CURSOR: PointerIcon by lazy {
    PointerIcon(
        Toolkit.getDefaultToolkit().createCustomCursor(
            BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
            Point(0, 0),
            "vnc-blank",
        ),
    )
}

@Composable
internal actual fun hiddenPointerIcon(): PointerIcon? = BLANK_CURSOR
