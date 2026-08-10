package app.skerry.ui.host

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import app.skerry.shared.serial.SerialPortInfo
import app.skerry.ui.connection.ConnectionTestStatus
import app.skerry.ui.connection.connectionTestFailureText
import app.skerry.ui.host.NewConnectionFormState
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_tag_add_placeholder
import app.skerry.ui.generated.resources.conn_test_checking
import app.skerry.ui.generated.resources.conn_test_connected
import app.skerry.ui.generated.resources.conn_test_rtt_ms
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.LocalFieldLabel
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.fieldFocus
import app.skerry.ui.design.fieldName
import app.skerry.ui.design.rememberFieldDraft
import app.skerry.ui.ai.PolicyOption
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.i18n.label
import app.skerry.ui.vault.title
import app.skerry.ui.theme.Skerry
import app.skerry.ui.generated.resources.shell_tip_remove
import app.skerry.ui.generated.resources.shell_tip_show_options

@Composable
internal fun Spacer14() = Box(Modifier.size(14.dp))

@Composable
internal fun Field(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Txt(label.uppercase(), color = Skerry.colors.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(bottom = 5.dp))
        // The caption is a sibling of the input, so nothing connects the two on its own — see
        // [LocalFieldLabel]. The input inside adopts it as its accessible name.
        CompositionLocalProvider(LocalFieldLabel provides label) { content() }
    }
}

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

/**
 * The Serial "Device" field: the port path, typed by hand or picked from the ports discovered on this
 * machine (desktop: jSerialComm, Android: USB-OTG). Both, rather than either — a port that appeared
 * after the form opened has to be typeable, and a path nobody remembers has to be pickable. Writes
 * [NewConnectionFormState.address]; with no ports discovered it is an ordinary text field.
 */
@Composable
internal fun SerialDeviceField(form: NewConnectionFormState) {
    // Enumerated once when the form opens (cheap, no permission needed). A machine with no ports —
    // or a platform without serial support — gets a plain text field, with no arrow to open.
    val ports = remember { serialPortOptions(listSerialPorts()) }
    var menuOpen by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = menuOpen && ports.isNotEmpty(),
        onDismiss = { menuOpen = false },
        // Focusable so a click anywhere else closes the list: unlike the tag picker this menu is not
        // typed into, and a non-focusable Popup never gets onDismissRequest — it would hang over the
        // form while the user moved on to the baud rate. Passed explicitly, not left to the default.
        focusable = true,
        trigger = {
            ModalTextField(
                form.address, { form.address = it }, "/dev/ttyUSB0 or COM3", icon = "usb",
                trailing = if (ports.isEmpty()) {
                    null
                } else {
                    {
                        Sym(
                            if (menuOpen) "expand_less" else "expand_more",
                            contentDescription = stringResource(Res.string.shell_tip_show_options),
                            size = 16.sp,
                            color = Skerry.colors.faint,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { menuOpen = !menuOpen },
                        )
                    }
                },
            )
        },
        menu = { width ->
            SuggestionMenu(width) {
                ports.forEach { port ->
                    key(port.systemName) {
                        SerialPortRow(port, selected = form.address == port.systemName) {
                            form.address = port.systemName
                            menuOpen = false
                        }
                    }
                }
            }
        },
    )
}

/** One row of the device menu: the port path, with the driver's description under it. */
@Composable
private fun SerialPortRow(port: SerialPortInfo, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(if (selected) Skerry.colors.cyan10 else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Txt(port.systemName, color = if (selected) Skerry.colors.cyanBright else Skerry.colors.text, size = 12.5.sp)
        Txt(port.description, color = Skerry.colors.faint, size = 11.sp)
    }
}

/** Container for suggestion dropdowns (group/tags): trigger width, scrolls on overflow, menu style. */
@Composable
internal fun SuggestionMenu(width: Dp, content: @Composable () -> Unit) {
    Column(
        Modifier.width(width).clip(RoundedCornerShape(7.dp)).background(Skerry.colors.surfaceDeep).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp))
            .heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
    ) { content() }
}

/** One suggestion row in the dropdown list: a single label, click to select. */
@Composable
internal fun SuggestionRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, color = Skerry.colors.text, size = 12.5.sp)
    }
}

/** Tag pill with a remove cross; [tag] is the canonical form, shown on screen with a `#` prefix. */
@Composable
internal fun RemovableTagPill(tag: String, onRemove: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Skerry.colors.cyan.copy(alpha = 0.12f)).padding(start = 9.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Txt("#$tag", color = Skerry.colors.cyanBright, size = 11.sp)
        Box(Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(2.dp), contentAlignment = Alignment.Center) {
            Sym("close", contentDescription = stringResource(Res.string.shell_tip_remove), size = 12.sp, color = Skerry.colors.cyanBright)
        }
    }
}

/** "Test connection" status in the modal footer: checking / success (with RTT) / failure with a reason. */
@Composable
internal fun TestStatusLabel(status: ConnectionTestStatus) {
    when (status) {
        ConnectionTestStatus.Idle -> {}
        ConnectionTestStatus.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("progress_activity", size = 14.sp, color = Skerry.colors.dim)
            Txt(stringResource(Res.string.conn_test_checking), color = Skerry.colors.dim, size = 11.5.sp)
        }
        is ConnectionTestStatus.Success -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("check_circle", size = 14.sp, color = Skerry.colors.moss)
            // RTT goes on its own line: the footer slot is narrow and a single "Connected · N ms"
            // string used to wrap mid-unit.
            Column {
                Txt(stringResource(Res.string.conn_test_connected), color = Skerry.colors.moss, size = 11.5.sp)
                status.roundTripMillis?.let {
                    Txt(stringResource(Res.string.conn_test_rtt_ms, it), color = Skerry.colors.moss.copy(alpha = 0.75f), size = 10.5.sp)
                }
            }
        }
        is ConnectionTestStatus.Failure -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("error", size = 14.sp, color = Skerry.colors.storm)
            Txt(connectionTestFailureText(status.problem), color = Skerry.colors.storm, size = 11.5.sp)
        }
    }
}

/** Inline input for a new tag inside the Tags block: Enter ([onCommit]) or a comma commits the pill. */
@Composable
internal fun TagInput(value: String, onValueChange: (String) -> Unit, onCommit: () -> Unit, onFocusChanged: ((Boolean) -> Unit)? = null, modifier: Modifier = Modifier) {
    val fonts = LocalFonts.current
    val textColor = Skerry.colors.text
    val textStyle = remember(fonts.ui, textColor) { TextStyle(color = textColor, fontSize = 12.5.sp, fontFamily = fonts.ui) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(Skerry.colors.cyan),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit() }),
        modifier = modifier.widthIn(min = 72.dp).fieldName().onFocusChanged { onFocusChanged?.invoke(it.isFocused) },
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Txt(stringResource(Res.string.conn_tag_add_placeholder), color = Skerry.colors.faint, size = 12.5.sp)
                inner()
            }
        },
    )
}

@Composable
internal fun PolicyRow(opt: PolicyOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Skerry.colors.cyan10 else Color.Transparent)
            .border(1.dp, if (selected) Skerry.colors.cyan else Skerry.colors.cyan06, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(16.dp).clip(CircleShape).border(1.5.dp, if (selected) Skerry.colors.cyan else Skerry.colors.faint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(Skerry.colors.cyan))
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Sym(opt.icon, size = 14.sp, color = Skerry.colors.dim)
                Txt(stringResource(opt.title), color = Skerry.colors.text, size = 13.sp, weight = FontWeight.Medium)
            }
            Txt(stringResource(opt.desc), color = Skerry.colors.dim, size = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
