package app.skerry.ui.design

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition

@Composable
actual fun SkerryTextContextMenu(content: @Composable () -> Unit) {
    // Only consulted while Compose's own `isNewContextMenuEnabled` flag is off, which is the default
    // in Compose Multiplatform 1.9.3. The parallel "new" implementation ignores this local entirely,
    // so a version bump that flips the default would silently restore the white Material menu —
    // TextContextMenuTest is what catches that.
    CompositionLocalProvider(LocalContextMenuRepresentation provides SkerryContextMenu, content = content)
}

/**
 * The menu Compose opens on a right click, drawn as a [MenuPanel] so it matches the "⋮" menus the
 * app opens itself. The items come from Compose (and are localized by it); only their presentation
 * is ours — over a selection that is a single Copy row, over a text field it is Cut / Copy / Paste /
 * Select all.
 */
private object SkerryContextMenu : ContextMenuRepresentation {

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val status = state.status
        if (status !is ContextMenuState.Status.Open) return
        // Read from inside the popup's own composition, where the focus manager is the popup's.
        var focus: FocusManager? by mutableStateOf(null)
        var inputMode: InputModeManager? by mutableStateOf(null)
        Popup(
            popupPositionProvider = rememberPopupPositionProviderAtPosition(status.rect.center),
            onDismissRequest = { state.status = ContextMenuState.Status.Closed },
            // Focusable so Esc and a click outside close it, as with the app's own menus.
            properties = PopupProperties(focusable = true),
            // Up/Down walk the rows, as they do in the representation this replaces. Without it the
            // arrows would be swallowed by the focusable popup and the menu would look keyboard-dead.
            onKeyEvent = { event ->
                val direction = when {
                    event.type != KeyEventType.KeyDown -> null
                    event.key == Key.DirectionDown -> FocusDirection.Next
                    event.key == Key.DirectionUp -> FocusDirection.Previous
                    else -> null
                }
                direction?.let {
                    inputMode?.requestInputMode(InputMode.Keyboard)
                    focus?.moveFocus(it)
                    true
                } ?: false
            },
        ) {
            focus = LocalFocusManager.current
            inputMode = LocalInputModeManager.current
            MenuPanel {
                items().forEach { item ->
                    MenuItem(item.label) {
                        // Closed first, then the action — the order Compose's own representation uses,
                        // so an action that opens a dialog does not leave a menu standing behind it.
                        state.status = ContextMenuState.Status.Closed
                        item.onClick()
                    }
                }
            }
        }
    }
}
