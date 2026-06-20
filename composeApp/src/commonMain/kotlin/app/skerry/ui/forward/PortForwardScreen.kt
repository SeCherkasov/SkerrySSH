package app.skerry.ui.forward

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.desktop.ChromeIconButton
import app.skerry.ui.desktop.SkerryIcon
import app.skerry.ui.desktop.SkerryIconKind
import app.skerry.ui.theme.SkerryColors

/**
 * Панель проброса портов одной сессии: форма добавления (`-L`/`-R`) сверху и список активных
 * пробросов снизу. Рендерит [PortForwardController]; подъём/снятие — его забота, экран лишь
 * собирает параметры и показывает состояние строк ([ForwardStatus]).
 */
@Composable
fun PortForwardScreen(
    controller: PortForwardController,
    mono: FontFamily,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(SkerryColors.nightSea)) {
        AddForwardForm(
            mono = mono,
            onAddLocal = controller::addLocal,
            onAddRemote = controller::addRemote,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(SkerryColors.line))

        val forwards = controller.forwards
        if (forwards.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Пробросов пока нет. Добавьте локальный (-L) или обратный (-R) туннель.",
                    color = SkerryColors.textFaint,
                    fontSize = 12.5.sp,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(forwards, key = { it.id }) { entry ->
                    ForwardRow(entry, mono, onRemove = { controller.remove(entry) })
                }
            }
        }
    }
}

@Composable
private fun AddForwardForm(
    mono: FontFamily,
    onAddLocal: (bindPort: Int, destHost: String, destPort: Int, bindHost: String) -> Unit,
    onAddRemote: (bindPort: Int, destHost: String, destPort: Int, bindHost: String) -> Unit,
) {
    var direction by remember { mutableStateOf(ForwardDirection.Local) }
    var bindPort by remember { mutableStateOf("") }
    var destHost by remember { mutableStateOf("") }
    var destPort by remember { mutableStateOf("") }

    val request = parseForwardInput(bindPort, destHost, destPort)

    Row(
        Modifier.fillMaxWidth().background(SkerryColors.nightSeaSoft).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DirectionToggle(direction, mono, onSelect = { direction = it })
        PortField(bindPort, onChange = { bindPort = it }, placeholder = "лок. порт", mono = mono, width = 92.dp)
        Text("→", color = SkerryColors.textFaint, fontSize = 14.sp)
        HostField(destHost, onChange = { destHost = it }, mono = mono)
        PortField(destPort, onChange = { destPort = it }, placeholder = "порт", mono = mono, width = 74.dp)
        AddButton(
            enabled = request != null,
            onClick = {
                val r = request ?: return@AddButton
                when (direction) {
                    ForwardDirection.Local -> onAddLocal(r.bindPort, r.destHost, r.destPort, "127.0.0.1")
                    ForwardDirection.Remote -> onAddRemote(r.bindPort, r.destHost, r.destPort, "127.0.0.1")
                }
                bindPort = ""; destHost = ""; destPort = ""
            },
        )
    }
}

/** Сегментированный переключатель -L / -R. */
@Composable
private fun DirectionToggle(
    selected: ForwardDirection,
    mono: FontFamily,
    onSelect: (ForwardDirection) -> Unit,
) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(SkerryColors.deep2).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (dir in ForwardDirection.entries) {
            val active = dir == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (active) SkerryColors.cyanSoft else Color.Transparent)
                    .clickable { onSelect(dir) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    if (dir == ForwardDirection.Local) "-L" else "-R",
                    color = if (active) SkerryColors.cyan else SkerryColors.textDim,
                    fontFamily = mono,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun PortField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    mono: FontFamily,
    width: Dp,
) {
    FieldBox(Modifier.width(width)) {
        BasicTextField(
            value = value,
            onValueChange = { new -> onChange(new.filter(Char::isDigit)) },
            singleLine = true,
            textStyle = TextStyle(color = SkerryColors.text, fontFamily = mono, fontSize = 12.sp),
            cursorBrush = SolidColor(SkerryColors.cyan),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, color = SkerryColors.textFaint, fontFamily = mono, fontSize = 12.sp)
                inner()
            },
        )
    }
}

@Composable
private fun HostField(value: String, onChange: (String) -> Unit, mono: FontFamily) {
    FieldBox(Modifier.width(170.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = SkerryColors.text, fontFamily = mono, fontSize = 12.sp),
            cursorBrush = SolidColor(SkerryColors.cyan),
            decorationBox = { inner ->
                if (value.isEmpty()) Text("хост назначения", color = SkerryColors.textFaint, fontFamily = mono, fontSize = 12.sp)
                inner()
            },
        )
    }
}

@Composable
private fun FieldBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SkerryColors.deep2)
            .border(1.dp, SkerryColors.lineStrong, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

@Composable
private fun AddButton(enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .alpha(if (enabled) 1f else 0.4f)
            .background(SkerryColors.cyan)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkerryIcon(SkerryIconKind.Add, tint = SkerryColors.deep2, size = 15.dp)
        Box(Modifier.width(5.dp))
        Text("Поднять", color = SkerryColors.deep2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Одна строка списка пробросов: бейдж направления, маршрут, статус и кнопка снятия. */
@Composable
private fun ForwardRow(entry: ForwardEntry, mono: FontFamily, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SkerryColors.nightSeaSoft)
            .border(1.dp, SkerryColors.line, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(4.dp)).background(SkerryColors.cyanSoft).padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Text(
                if (entry.direction == ForwardDirection.Local) "-L" else "-R",
                color = SkerryColors.cyan, fontFamily = mono, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(forwardRouteText(entry), color = SkerryColors.text, fontFamily = mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            StatusLine(entry.status, mono)
        }
        ChromeIconButton(SkerryIconKind.Close, onClick = onRemove)
    }
}

@Composable
private fun StatusLine(status: ForwardStatus, mono: FontFamily) {
    val (text, color) = when (status) {
        ForwardStatus.Starting -> "поднимается…" to SkerryColors.amber
        is ForwardStatus.Active -> "активен · порт ${status.boundPort}" to SkerryColors.moss
        is ForwardStatus.Failed -> status.message to SkerryColors.storm
    }
    Text(text, color = color, fontFamily = mono, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * Маршрут проброса для показа. Сторона слушателя зависит от направления (-L: эта машина, -R: сервер).
 * Общий для desktop ([PortForwardScreen]) и мобильного списка — чтобы формат не разъезжался.
 */
internal fun forwardRouteText(entry: ForwardEntry): String {
    val listenPort = (entry.status as? ForwardStatus.Active)?.boundPort ?: entry.requestedPort
    val side = if (entry.direction == ForwardDirection.Local) "localhost" else "server"
    return "$side:$listenPort  →  ${entry.destHost}:${entry.destPort}"
}
