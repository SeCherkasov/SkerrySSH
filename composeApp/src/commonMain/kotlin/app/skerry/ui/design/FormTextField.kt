package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.theme.Skerry

/*
 * The form's plain text input. It grew up in the connection modal and moved here when the folder
 * select ([GroupSelectField]) needed the same field outside `ui/host` — one text field for the forms
 * that draw a bordered box with a placeholder, rather than a second copy per section.
 */

/**
 * Editable form text field (optional leading icon): layout style plus placeholder.
 * [masked] hides input (password/passphrase); [singleLine] = false plus [mono] plus [minHeightDp]
 * gives a multi-line monospace area for pasting a private key (PEM).
 */
@Composable
internal fun ModalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    masked: Boolean = false,
    singleLine: Boolean = true,
    mono: Boolean = false,
    minHeightDp: Int? = null,
    /**
     * Select the whole value when the field takes focus, so the next keystroke replaces it. For a
     * value the form filled in itself (a still-default port) — never for one the user typed.
     */
    selectAllOnFocus: Boolean = false,
    /** Drawn at the right edge, inside the border — the serial device field hangs its menu arrow here. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val fonts = LocalFonts.current
    val family = if (mono) fonts.mono else fonts.ui
    val fontSize = if (mono) 11.5.sp else 13.sp
    val textColor = Skerry.colors.text
    val textStyle = remember(family, fontSize, textColor) {
        TextStyle(color = textColor, fontSize = fontSize, fontFamily = family, lineHeight = if (mono) 16.sp else 18.sp)
    }
    // Masked input and multi-line areas keep the plain caret: a selected password reveals its
    // length, and replacing a whole pasted key wholesale is not what the field is for.
    val draft = rememberFieldDraft(value, selectAllOnFocus, masked, singleLine)
    // Border/icon live in decorationBox so a click anywhere on the field places the caret.
    BasicTextField(
        value = draft.textFieldValue(value),
        onValueChange = { draft.accept(it, value, onValueChange) },
        singleLine = singleLine,
        textStyle = textStyle,
        cursorBrush = SolidColor(Skerry.colors.cyan),
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (masked) KeyboardType.Password else keyboardType),
        modifier = Modifier.fillMaxWidth().fieldFocus(draft).fieldName(),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (minHeightDp != null) Modifier.heightIn(min = minHeightDp.dp) else Modifier)
                    .clip(RoundedCornerShape(7.dp)).background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (icon != null) Sym(icon, size = 16.sp, color = Skerry.colors.faint)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Txt(placeholder, color = Skerry.colors.faint, size = fontSize, font = if (mono) fonts.mono else null)
                    inner()
                }
                trailing?.invoke()
            }
        },
    )
}
