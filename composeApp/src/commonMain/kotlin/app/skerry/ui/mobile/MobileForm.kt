package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Txt
import app.skerry.ui.theme.Skerry

/**
 * Shared mobile form primitives (sheets/dialogs/settings): a field label and a text field. Single
 * source of truth instead of per-screen copies (new-connection sheet, snippets, ports, AI settings).
 */

/** Field label (uppercase) + content. */
@Composable
internal fun MobileFormField(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Txt(
            label.uppercase(),
            color = Skerry.colors.faint,
            size = 10.5.sp,
            weight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        content()
    }
}

/**
 * Sheet text field (dark background + cyan border, radius 11). [masked] hides input (password/
 * passphrase); [singleLine] = false + [mono] + [minHeightDp] gives a multiline monospace area
 * (pasting a PEM key, a snippet command), like desktop `ModalTextField`. [background] is the
 * field's fill color (snippet command uses [Skerry.colors.terminalBg]). [onSubmit] fires on Done/Send/Go.
 */
@Composable
internal fun MobileFormInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    masked: Boolean = false,
    singleLine: Boolean = true,
    mono: Boolean = false,
    background: Color = Skerry.colors.bg,
    minHeightDp: Int? = null,
    imeAction: ImeAction = ImeAction.Default,
    onSubmit: (() -> Unit)? = null,
) {
    val fonts = LocalFonts.current
    val family = if (mono) fonts.mono else fonts.ui
    val fontSize = if (mono) 12.5.sp else 15.sp
    val textColor = Skerry.colors.text
    val textStyle = remember(family, fontSize, textColor) {
        TextStyle(color = textColor, fontSize = fontSize, fontFamily = family, lineHeight = if (mono) 17.sp else 20.sp)
    }
    // Border/padding live in decorationBox so a click anywhere in the field places the caret.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = textStyle,
        cursorBrush = SolidColor(Skerry.colors.cyan),
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (masked) KeyboardType.Password else keyboardType, imeAction = imeAction),
        keyboardActions = if (onSubmit != null) {
            KeyboardActions(onDone = { onSubmit() }, onSend = { onSubmit() }, onGo = { onSubmit() })
        } else {
            KeyboardActions.Default
        },
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (minHeightDp != null) Modifier.heightIn(min = minHeightDp.dp) else Modifier)
                    .clip(RoundedCornerShape(11.dp))
                    .background(background)
                    .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(11.dp))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (value.isEmpty()) Txt(placeholder, color = Skerry.colors.faint, size = fontSize, font = if (mono) fonts.mono else null)
                inner()
            }
        },
    )
}
