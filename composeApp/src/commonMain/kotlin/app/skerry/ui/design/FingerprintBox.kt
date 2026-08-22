package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.theme.Skerry

/**
 * A key fingerprint drawn the way both places that show one draw it: monospaced on the terminal
 * ground, in a box tinted by what the fingerprint means — the known-hosts manager and the trust
 * dialog that asks about the same key while a handshake waits.
 *
 * Wraps rather than ellipsizes: a fingerprint the user is asked to compare character by character
 * must be on screen whole.
 */
@Composable
fun FingerprintBox(text: String, color: Color, border: Color, mono: FontFamily, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Skerry.colors.terminalBg)
            .border(1.dp, border, RoundedCornerShape(7.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Txt(text, color = color, size = 10.5.sp, font = mono)
    }
}
