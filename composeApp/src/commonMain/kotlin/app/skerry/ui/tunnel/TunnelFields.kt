package app.skerry.ui.tunnel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.MeterBar
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ports_no_saved_hosts
import app.skerry.ui.design.fieldFocus
import app.skerry.ui.design.rememberFieldDraft
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

// Form controls of the tunnel side panels (editor, service scan, autostart). Shared so the panels
// stay a layout each, and a change to how a field looks lands in one place.

/** Editable tunnel form field: boxed input with a placeholder. */
@Composable
internal fun EditField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    mono: FontFamily,
    keyboardType: KeyboardType = KeyboardType.Text,
    /** See `ModalTextField`: select the prefilled value on focus so the next keystroke replaces it. */
    selectAllOnFocus: Boolean = false,
) {
    val textColor = Skerry.colors.text
    val textStyle = remember(mono, textColor) { TextStyle(color = textColor, fontSize = 12.5.sp, fontFamily = mono) }
    val draft = rememberFieldDraft(value, selectAllOnFocus)
    BasicTextField(
        value = draft.textFieldValue(value),
        onValueChange = { draft.accept(it, value, onValueChange) },
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(Skerry.colors.cyan),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth().fieldFocus(draft),
        decorationBox = { inner ->
            Box(fieldBox()) {
                if (value.isEmpty()) Txt(placeholder, color = Skerry.colors.faint, size = 12.5.sp, font = mono)
                inner()
            }
        },
    )
}

/** Tunnel-type dropdown (-L/-R/-D) over the form. */
@Composable
internal fun TypePicker(current: TunnelDirection, onPick: (TunnelDirection) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { PickerTrigger(current.displayLabel()) { open = !open } },
        menu = { width ->
            MenuSurface(width) {
                listOf(TunnelDirection.Local, TunnelDirection.Remote, TunnelDirection.Dynamic).forEach { option ->
                    MenuItem(option.displayLabel(), active = option == current) { onPick(option); open = false }
                }
            }
        },
    )
}

/** Host dropdown over the form; empty shows a hint to add a host. */
@Composable
internal fun HostPicker(current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { PickerTrigger(current) { open = !open } },
        menu = { width ->
            MenuSurface(width, scrollable = true) {
                if (options.isEmpty()) {
                    Txt(stringResource(Res.string.ports_no_saved_hosts), color = Skerry.colors.faint, size = 12.sp, modifier = Modifier.padding(12.dp))
                } else {
                    options.forEach { (id, name) ->
                        MenuItem(name, active = false) { onPick(id); open = false }
                    }
                }
            }
        },
    )
}

/** Up/down throughput meter of a live tunnel. */
@Composable
internal fun ThroughputRow(icon: String, color: Color, fraction: Float, value: String, mono: FontFamily) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Sym(icon, size = 14.sp, color = color)
        MeterBar(fraction, color, Modifier.weight(1f))
        Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
            Txt(value, color = Skerry.colors.dim, size = 11.sp, font = mono)
        }
    }
}

@Composable
private fun PickerTrigger(value: String, onClick: () -> Unit) {
    Row(
        fieldBox(onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(value, color = Skerry.colors.text, size = 12.5.sp)
        Sym("expand_more", size = 16.sp, color = Skerry.colors.faint)
    }
}

@Composable
private fun MenuSurface(width: Dp, scrollable: Boolean = false, content: @Composable () -> Unit) {
    Column(
        Modifier.width(width)
            .clip(RoundedCornerShape(8.dp))
            .background(Skerry.colors.surface2)
            .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(8.dp))
            .then(if (scrollable) Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()) else Modifier),
    ) {
        content()
    }
}

@Composable
private fun MenuItem(label: String, active: Boolean, onClick: () -> Unit) {
    Txt(
        label,
        color = if (active) Skerry.colors.cyanBright else Skerry.colors.text,
        size = 12.5.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

/**
 * The boxed look every field and picker trigger shares. [onClick] lands before the padding, so the
 * whole box reacts rather than only the text inside it.
 */
@Composable
private fun fieldBox(onClick: (() -> Unit)? = null): Modifier {
    val clipped = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
    return (if (onClick != null) clipped.clickable(onClick = onClick) else clipped)
        .background(Skerry.colors.bg)
        .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(6.dp))
        .padding(horizontal = 10.dp, vertical = 8.dp)
}
