package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.forward.ForwardDirection
import app.skerry.ui.forward.ForwardEntry
import app.skerry.ui.forward.ForwardStatus
import app.skerry.ui.forward.PortForwardController
import app.skerry.ui.forward.forwardDestText
import app.skerry.ui.forward.forwardSourceText
import app.skerry.ui.forward.forwardTypeLabel
import app.skerry.ui.forward.parseBindPort
import app.skerry.ui.forward.parseForwardInput

private data class TunnelRule(
    val type: String, val typeBg: Color, val typeFg: Color,
    val source: String, val arrow: String, val dest: String, val destDim: Boolean,
    val via: String, val active: Boolean,
)

private val TUNNELS = listOf(
    TunnelRule("LOCAL", D.cyan.copy(alpha = 0.12f), D.cyanBright, "127.0.0.1:8080", "arrow_forward", "10.0.0.5:80", false, "prod-web-01", true),
    TunnelRule("REMOTE", D.amber.copy(alpha = 0.14f), D.amber, "0.0.0.0:9000", "arrow_forward", "localhost:3000", false, "homelab-pi", true),
    TunnelRule("SOCKS", D.moss.copy(alpha = 0.14f), D.moss, "127.0.0.1:1080", "all_inclusive", "dynamic proxy", true, "db-master", false),
)

/**
 * Port forwarding (Tunnels) view: заголовок + таблица правил + панель деталей туннеля. Когда есть
 * живая подключённая сессия ([LocalSessions]), таблица рендерится поверх живого
 * [PortForwardController] активной сессии (реальные пробросы, статусы Starting/Active/Failed), а
 * правая панель — рабочая форма добавления туннеля (`-L`/`-R`/`-D`). Без сессии (офскрин-рендер
 * дизайна) показывается мок ([TUNNELS] + статичная форма деталей).
 */
@Composable
fun TunnelsView() {
    val mono = LocalFonts.current.mono
    val sessions = LocalSessions.current
    val active = sessions?.active
    val controller = active?.controller
    val connected = controller?.uiState is ConnectionUiState.Connected
    val live = sessions != null

    Column(Modifier.fillMaxSize().background(D.bg)) {
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Txt("Port forwarding", color = D.text, size = 15.sp, weight = FontWeight.SemiBold)
                val subtitle =
                    if (live) (active?.subtitle?.let { "$it · tunnels" } ?: "No active session")
                    else "SSH tunnels — local, remote and dynamic (SOCKS) port forwards."
                Txt(subtitle, color = D.dim, size = 12.sp, font = if (live) mono else FontFamily.Default, modifier = Modifier.padding(top = 2.dp))
            }
            // В live-режиме форма всегда видна справа, поэтому кнопка декоративна (как в макете).
            PrimaryButton("New rule", onClick = {}, icon = "add")
        }
        HLine()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !live -> MockTunnelsBody()
                connected && controller != null -> LiveTunnelsBody(controller, active?.title ?: "—", mono)
                else -> TunnelsNotice()
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Живой путь: таблица поверх PortForwardController активной сессии + форма добавления.
// ---------------------------------------------------------------------------------------------

@Composable
private fun LiveTunnelsBody(controller: ConnectionController, host: String, mono: FontFamily) {
    // openPortForwards кэшируется на соединении и живёт на scope сессии — переключение вкладок/вида
    // список не сбрасывает; все пробросы снимает disconnect().
    val forwards = remember(controller) { controller.openPortForwards() }
    val rules = forwards.forwards
    val activeCount = rules.count { it.status is ForwardStatus.Active }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp)) {
            if (rules.isEmpty()) {
                EmptyTunnels()
            } else {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, D.cyan08, RoundedCornerShape(10.dp))) {
                    TunnelHeaderRow()
                    rules.forEach { entry ->
                        HLine()
                        TunnelRowLive(entry, host, mono, onRemove = { forwards.remove(entry) })
                    }
                }
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Sym("bolt", size = 15.sp, color = D.moss)
                    Txt("$activeCount active ${if (activeCount == 1) "tunnel" else "tunnels"}", color = D.faint, size = 11.5.sp)
                }
            }
        }
        VLine(D.line)
        AddTunnelForm(forwards, mono)
    }
}

@Composable
private fun EmptyTunnels() {
    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym("lan", size = 26.sp, color = D.faint)
            Txt("No tunnels yet", color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
            Txt("Add a forward on the right →", color = D.faint, size = 11.5.sp)
        }
    }
}

@Composable
private fun TunnelRowLive(entry: ForwardEntry, host: String, mono: FontFamily, onRemove: () -> Unit) {
    val (bg, fg) = when (entry.direction) {
        ForwardDirection.Local -> D.cyan.copy(alpha = 0.12f) to D.cyanBright
        ForwardDirection.Remote -> D.amber.copy(alpha = 0.14f) to D.amber
        ForwardDirection.Dynamic -> D.moss.copy(alpha = 0.14f) to D.moss
    }
    val arrow = if (entry.direction == ForwardDirection.Dynamic) "all_inclusive" else "arrow_forward"
    val dest = forwardDestText(entry)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.width(76.dp)) {
                Badge(forwardTypeLabel(entry.direction), bg = bg, fg = fg, radius = 4, size = 10.sp)
            }
            Txt(forwardSourceText(entry), color = D.textBright, size = 12.5.sp, font = mono, modifier = Modifier.weight(1f))
            Box(Modifier.width(20.dp)) { Sym(arrow, size = 16.sp, color = D.faint) }
            Txt(dest ?: "dynamic proxy", color = if (dest == null) D.dim else D.textBright, size = 12.5.sp, font = mono, modifier = Modifier.weight(1f))
            Txt(host, color = D.dim, size = 11.5.sp, font = mono, modifier = Modifier.width(110.dp))
            Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
                ActiveCell(entry.status, onRemove)
            }
        }
        (entry.status as? ForwardStatus.Failed)?.let {
            Txt(it.message, color = D.sunset, size = 11.sp, font = mono, modifier = Modifier.padding(start = 16.dp, bottom = 10.dp))
        }
    }
}

/** Ячейка ACTIVE по статусу: активный — тумблер (выкл = снять), поднимается — индикатор, ошибка — снять. */
@Composable
private fun ActiveCell(status: ForwardStatus, onRemove: () -> Unit) {
    when (status) {
        is ForwardStatus.Active -> Toggle(on = true, onToggle = onRemove)
        ForwardStatus.Starting -> Sym("hourglass_top", size = 16.sp, color = D.amber)
        is ForwardStatus.Failed ->
            Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onRemove).padding(2.dp)) {
                Sym("error", size = 16.sp, color = D.sunset)
            }
    }
}

@Composable
private fun AddTunnelForm(controller: PortForwardController, mono: FontFamily) {
    var direction by remember { mutableStateOf(ForwardDirection.Local) }
    var bindHost by remember { mutableStateOf("127.0.0.1") }
    var bindPort by remember { mutableStateOf("") }
    var destHost by remember { mutableStateOf("") }
    var destPort by remember { mutableStateOf("") }

    val isDynamic = direction == ForwardDirection.Dynamic
    val request = if (isDynamic) null else parseForwardInput(bindPort, destHost, destPort)
    val bind = parseBindPort(bindPort)
    val canAdd = if (isDynamic) bind != null else request != null

    val (badgeBg, badgeFg) = when (direction) {
        ForwardDirection.Local -> D.cyan.copy(alpha = 0.12f) to D.cyanBright
        ForwardDirection.Remote -> D.amber.copy(alpha = 0.14f) to D.amber
        ForwardDirection.Dynamic -> D.moss.copy(alpha = 0.14f) to D.moss
    }

    Column(
        Modifier.width(308.dp).fillMaxHeight().background(D.surface2).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge(forwardTypeLabel(direction), bg = badgeBg, fg = badgeFg, radius = 4, size = 10.sp)
            Txt("New tunnel", color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
        }
        FieldLabel("Type")
        ClickableSelect(directionDisplay(direction)) { direction = direction.next() }
        Box(Modifier.padding(bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { FieldLabel("Bind address"); EditField(bindHost, { bindHost = it }, "127.0.0.1", mono) }
            Column(Modifier.width(70.dp)) { FieldLabel("Port"); EditField(bindPort, { bindPort = it }, "0", mono, KeyboardType.Number) }
        }
        if (!isDynamic) {
            Box(Modifier.padding(bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { FieldLabel("Destination"); EditField(destHost, { destHost = it }, "10.0.0.5", mono) }
                Column(Modifier.width(70.dp)) { FieldLabel("Port"); EditField(destPort, { destPort = it }, "80", mono, KeyboardType.Number) }
            }
        } else {
            Box(Modifier.padding(bottom = 4.dp))
            Txt("SOCKS5 proxy — destination is chosen per connection by each client.", color = D.faint, size = 11.sp, lineHeight = 15.sp)
        }
        Box(Modifier.padding(bottom = 18.dp))
        PrimaryButton(
            label = "Add tunnel",
            onClick = {
                if (!canAdd) return@PrimaryButton
                val host = bindHost.trim().ifEmpty { "127.0.0.1" }
                when (direction) {
                    ForwardDirection.Local -> request?.let { controller.addLocal(it.bindPort, it.destHost, it.destPort, host) }
                    ForwardDirection.Remote -> request?.let { controller.addRemote(it.bindPort, it.destHost, it.destPort, host) }
                    ForwardDirection.Dynamic -> bind?.let { controller.addDynamic(it, host) }
                }
                bindPort = ""; destHost = ""; destPort = ""
            },
            modifier = Modifier.fillMaxWidth(),
            bg = if (canAdd) D.cyan else Color(0x14FFFFFF),
            fg = if (canAdd) Color(0xFF0A1A26) else D.faint,
        )
    }
}

private fun ForwardDirection.next(): ForwardDirection = when (this) {
    ForwardDirection.Local -> ForwardDirection.Remote
    ForwardDirection.Remote -> ForwardDirection.Dynamic
    ForwardDirection.Dynamic -> ForwardDirection.Local
}

private fun directionDisplay(direction: ForwardDirection): String = when (direction) {
    ForwardDirection.Local -> "Local forward (-L)"
    ForwardDirection.Remote -> "Remote forward (-R)"
    ForwardDirection.Dynamic -> "Dynamic SOCKS (-D)"
}

/** Тело без активной подключённой сессии. */
@Composable
private fun TunnelsNotice() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym("lan", size = 28.sp, color = D.faint)
            Txt("No active session", color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
            Txt("Connect a host to set up port forwarding", color = D.faint, size = 11.5.sp)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Мок-путь (офскрин-рендер/превью): статичная таблица + форма деталей из макета.
// ---------------------------------------------------------------------------------------------

@Composable
private fun MockTunnelsBody() {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, D.cyan08, RoundedCornerShape(10.dp))) {
                TunnelHeaderRow()
                TUNNELS.forEach { rule ->
                    HLine()
                    TunnelRow(rule)
                }
            }
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sym("bolt", size = 15.sp, color = D.moss)
                Txt("2 active tunnels · 1.4 MB forwarded this session", color = D.faint, size = 11.5.sp)
            }
        }
        VLine(D.line)
        TunnelDetail()
    }
}

@Composable
private fun TunnelHeaderRow() {
    Row(
        Modifier.fillMaxWidth().background(Color(0x05FFFFFF)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HeaderCell("TYPE", Modifier.width(76.dp))
        HeaderCell("SOURCE", Modifier.weight(1f))
        Box(Modifier.width(20.dp))
        HeaderCell("DESTINATION", Modifier.weight(1f))
        HeaderCell("VIA HOST", Modifier.width(110.dp))
        HeaderCell("ACTIVE", Modifier.width(56.dp), end = true)
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier = Modifier, end: Boolean = false) {
    Box(modifier, contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart) {
        Txt(text, color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun TunnelRow(rule: TunnelRule) {
    val mono = LocalFonts.current.mono
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(76.dp)) {
            Badge(rule.type, bg = rule.typeBg, fg = rule.typeFg, radius = 4, size = 10.sp)
        }
        Txt(rule.source, color = D.textBright, size = 12.5.sp, font = mono, modifier = Modifier.weight(1f))
        Box(Modifier.width(20.dp)) { Sym(rule.arrow, size = 16.sp, color = D.faint) }
        Txt(rule.dest, color = if (rule.destDim) D.dim else D.textBright, size = 12.5.sp, font = mono, modifier = Modifier.weight(1f))
        Txt(rule.via, color = D.dim, size = 11.5.sp, font = mono, modifier = Modifier.width(110.dp))
        Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
            Toggle(rule.active, onToggle = {})
        }
    }
}

@Composable
private fun TunnelDetail() {
    val mono = LocalFonts.current.mono
    Column(
        Modifier.width(308.dp).fillMaxHeight().background(D.surface2).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge("LOCAL", bg = D.cyan.copy(alpha = 0.12f), fg = D.cyanBright, radius = 4, size = 10.sp)
            Txt("Tunnel detail", color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
        }
        FieldLabel("Type")
        SelectField("Local forward")
        Box(Modifier.padding(bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { FieldLabel("Bind address"); InputField("127.0.0.1", mono) }
            Column(Modifier.width(70.dp)) { FieldLabel("Port"); InputField("8080", mono) }
        }
        Box(Modifier.padding(bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { FieldLabel("Destination"); InputField("10.0.0.5", mono) }
            Column(Modifier.width(70.dp)) { FieldLabel("Port"); InputField("80", mono) }
        }
        Box(Modifier.padding(bottom = 16.dp))
        FieldLabel("Live throughput")
        ThroughputRow("arrow_upward", D.cyanBright, 0.38f, "42 KB/s", mono)
        Box(Modifier.padding(bottom = 8.dp))
        ThroughputRow("arrow_downward", D.moss, 0.71f, "1.1 MB/s", mono)
        Box(Modifier.padding(bottom = 18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Save", onClick = {}, modifier = Modifier.weight(1f))
            GhostButton("Remove", onClick = {}, fg = D.sunset, border = D.sunset.copy(alpha = 0.3f), modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Txt(text.uppercase(), color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(bottom = 5.dp))
}

@Composable
private fun SelectField(value: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(value, color = D.text, size = 12.5.sp)
        Sym("expand_more", size = 16.sp, color = D.faint)
    }
}

/** Кликабельный селект: тот же стиль, что [SelectField], но циклически меняет значение по тапу. */
@Composable
private fun ClickableSelect(value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(value, color = D.text, size = 12.5.sp)
        Sym("expand_more", size = 16.sp, color = D.faint)
    }
}

@Composable
private fun InputField(value: String, mono: FontFamily) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Txt(value, color = D.text, size = 12.5.sp, font = mono)
    }
}

/** Редактируемое поле формы добавления туннеля (стиль [InputField] + плейсхолдер + ввод). */
@Composable
private fun EditField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    mono: FontFamily,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 12.5.sp, font = mono)
        val textStyle = remember(mono) { TextStyle(color = D.text, fontSize = 12.5.sp, fontFamily = mono) }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(D.cyan),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        )
    }
}

@Composable
private fun ThroughputRow(icon: String, color: Color, fraction: Float, value: String, mono: FontFamily) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Sym(icon, size = 14.sp, color = color)
        MeterBar(fraction, color, Modifier.weight(1f))
        Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
            Txt(value, color = D.dim, size = 11.sp, font = mono)
        }
    }
}
