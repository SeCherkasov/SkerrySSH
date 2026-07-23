package app.skerry.ui.files

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.TextUnit
import app.skerry.ui.theme.Skerry

/**
 * Type-to-jump path editor shared by the desktop SFTP panes and the mobile Files breadcrumb.
 * Prefilled with [path] fully selected so typing replaces it outright, autofocused on entry;
 * losing focus cancels (guarded against the initial unfocused frame before requestFocus lands, so
 * a stale editor is never left open). Enter commits and Esc cancels on hardware keyboards; the IME
 * "Go" action commits on touch, and [KeyboardType.Uri] keeps "/" handy with autocorrect off. The
 * caller supplies the surface chrome via [decoration] and closes the editor in [onCommit]/
 * [onCancel] — a commit is followed by the closing field's blur, so both must stay idempotent.
 */
@Composable
fun PathJumpField(
    path: String,
    mono: FontFamily,
    textSize: TextUnit,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    decoration: @Composable (inner: @Composable () -> Unit) -> Unit,
) {
    var draft by remember { mutableStateOf(TextFieldValue(path, TextRange(0, path.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    var everFocused by remember { mutableStateOf(false) }
    BasicTextField(
        value = draft,
        onValueChange = { draft = it },
        singleLine = true,
        textStyle = TextStyle(color = Skerry.colors.text, fontSize = textSize, fontFamily = mono),
        cursorBrush = SolidColor(Skerry.colors.cyan),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onCommit(draft.text) }),
        modifier = modifier
            .focusRequester(focus)
            .onFocusChanged { if (it.isFocused) everFocused = true else if (everFocused) onCancel() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> { onCommit(draft.text); true }
                    Key.Escape -> { onCancel(); true }
                    else -> false
                }
            },
        decorationBox = { inner -> decoration(inner) },
    )
}
