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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.ui.forward.humanRate
import app.skerry.ui.forward.rateFraction
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.tunnel.TunnelEntry
import app.skerry.ui.tunnel.TunnelManager
import app.skerry.ui.tunnel.TunnelStatus
import app.skerry.ui.tunnel.buildTunnelDraft

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
 * Port forwarding (Tunnels) — ГЛОБАЛЬНЫЙ раздел (привычная модель SSH-клиентов): список сохранённых туннелей с
 * тумблерами on/off + редактор справа. Туннель самостоятелен (ссылается на хост по id) и при включении
 * сам открывает SSH-соединение через [TunnelManager]. Когда менеджер подан ([LocalTunnels]) — живой
 * список; без него (офскрин-рендер/превью) показывается статичный мок ([TUNNELS]).
 */
@Composable
fun TunnelsView() {
    val mono = LocalFonts.current.mono
    val manager = LocalTunnels.current
    val hosts = LocalHosts.current

    // Состояние правой панели: выбранный туннель и режим «создать новый» (кнопка New tunnel).
    var selectedId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(D.bg)) {
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Txt("Port forwarding", color = D.text, size = 15.sp, weight = FontWeight.SemiBold)
                Txt(
                    "Saved tunnels — local, remote and dynamic (SOCKS) forwards.",
                    color = D.dim, size = 12.sp, modifier = Modifier.padding(top = 2.dp),
                )
            }
            PrimaryButton("New tunnel", onClick = { adding = true; selectedId = null }, icon = "add")
        }
        HLine()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (manager == null) {
                MockTunnelsBody()
            } else {
                GlobalTunnelsBody(
                    manager = manager,
                    hosts = hosts,
                    mono = mono,
                    adding = adding,
                    selectedId = selectedId,
                    onSelect = { selectedId = it; adding = false },
                    // После удаления возвращаемся в режим «New tunnel», а не прыгаем на случайный
                    // оставшийся туннель: selectedId ещё держит снесённый id, и без сброса selected
                    // резолвился бы в firstOrNull().
                    onNew = { selectedId = null; adding = true },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Живой путь: глобальный список сохранённых туннелей + редактор справа.
// ---------------------------------------------------------------------------------------------

@Composable
private fun GlobalTunnelsBody(
    manager: TunnelManager,
    hosts: HostManagerController?,
    mono: FontFamily,
    adding: Boolean,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
) {
    val tunnels = manager.tunnels
    val activeCount = tunnels.count { it.status is TunnelStatus.Active }
    val selected = tunnels.firstOrNull { it.id == selectedId } ?: tunnels.firstOrNull()
    // Правая панель: редактор нового туннеля (New tunnel или пока список пуст), иначе правка выбранного.
    val showNew = adding || selected == null

    fun hostLabel(hostId: String): String = hosts?.find(hostId)?.label ?: hostId

    // Туннель, для которого показан диалог подтверждения удаления (null — диалога нет). Локально: удаление
    // самодостаточно (manager.delete), глобальное состояние не нужно — в отличие от close сессий.
    var pendingRemove by remember { mutableStateOf<TunnelEntry?>(null) }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp)) {
                if (tunnels.isEmpty()) {
                    EmptyTunnels()
                } else {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, D.cyan08, RoundedCornerShape(10.dp))) {
                        TunnelHeaderRow()
                        tunnels.forEach { entry ->
                            HLine()
                            // Лямбды стабилизируем по id: телеметрия активных туннелей тикает раз в секунду и
                            // без remember пересоздавала бы onSelect/onToggle, перерисовывая весь список.
                            val onRowSelect = remember(entry.id, onSelect) { { onSelect(entry.id) } }
                            val onRowToggle = remember(entry.id, manager) {
                                {
                                    if (entry.status is TunnelStatus.Active) manager.deactivate(entry.id)
                                    else manager.activate(entry.id)
                                }
                            }
                            TunnelRowGlobal(
                                entry = entry,
                                via = hostLabel(entry.tunnel.hostId),
                                mono = mono,
                                selected = !showNew && entry.id == selected?.id,
                                onSelect = onRowSelect,
                                onToggle = onRowToggle,
                            )
                        }
                    }
                    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Sym("bolt", size = 15.sp, color = D.moss)
                        Txt("$activeCount active ${if (activeCount == 1) "tunnel" else "tunnels"}", color = D.faint, size = 11.5.sp)
                    }
                }
            }
            VLine(D.line)
            TunnelEditor(
                manager = manager,
                hosts = hosts,
                mono = mono,
                existing = if (showNew) null else selected,
                onSaved = { onSelect(it) },
                onRequestRemove = { selected?.let { pendingRemove = it } },
            )
        }
        pendingRemove?.let { entry ->
            ConfirmActionDialog(
                title = "Remove \"${entry.tunnel.label}\"?",
                message = if (entry.status is TunnelStatus.Active) {
                    "This tunnel is active — it will be disconnected and deleted. This can't be undone."
                } else {
                    "This saved tunnel is deleted. This can't be undone."
                },
                confirmLabel = "Remove",
                onConfirm = { manager.delete(entry.id); pendingRemove = null; onNew() },
                onDismiss = { pendingRemove = null },
            )
        }
    }
}

@Composable
private fun EmptyTunnels() {
    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym("lan", size = 26.sp, color = D.faint)
            Txt("No tunnels yet", color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
            Txt("Add a tunnel on the right →", color = D.faint, size = 11.5.sp)
        }
    }
}

@Composable
private fun TunnelRowGlobal(
    entry: TunnelEntry,
    via: String,
    mono: FontFamily,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
) {
    val t = entry.tunnel
    val (bg, fg) = directionColors(t.direction)
    val arrow = if (t.direction == TunnelDirection.Dynamic) "all_inclusive" else "arrow_forward"
    val dest = if (t.direction == TunnelDirection.Dynamic) null else "${t.destHost}:${t.destPort}"
    val dim = entry.status !is TunnelStatus.Active
    Column(Modifier.fillMaxWidth().clickable(onClick = onSelect).background(if (selected) D.cyan08 else Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.width(76.dp)) {
                Badge(directionBadge(t.direction), bg = bg, fg = fg, radius = 4, size = 10.sp)
            }
            Txt(sourceText(entry), color = if (dim) D.dim else D.textBright, size = 12.5.sp, font = mono, modifier = Modifier.weight(1f))
            Box(Modifier.width(20.dp)) { Sym(arrow, size = 16.sp, color = D.faint) }
            Txt(dest ?: "dynamic proxy", color = if (dest == null || dim) D.dim else D.textBright, size = 12.5.sp, font = mono, modifier = Modifier.weight(1f))
            Txt(via, color = D.dim, size = 11.5.sp, font = mono, modifier = Modifier.width(110.dp))
            Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
                ActiveCellGlobal(entry, onToggle)
            }
        }
        (entry.status as? TunnelStatus.Failed)?.let {
            Txt(it.message, color = D.sunset, size = 11.sp, font = mono, modifier = Modifier.padding(start = 16.dp, bottom = 10.dp))
        }
    }
}

/** Источник: bind-адрес и порт слушателя (для активного — фактический boundPort, иначе запрошенный). */
private fun sourceText(entry: TunnelEntry): String {
    val port = (entry.status as? TunnelStatus.Active)?.boundPort ?: entry.tunnel.bindPort
    return "${entry.tunnel.bindHost}:$port"
}

/** Ячейка ACTIVE: активный — тумблер вкл, подъём — песочные часы, иначе — тумблер выкл (включить/повторить). */
@Composable
private fun ActiveCellGlobal(entry: TunnelEntry, onToggle: () -> Unit) {
    when (entry.status) {
        is TunnelStatus.Active -> Toggle(on = true, onToggle = onToggle)
        TunnelStatus.Connecting -> Sym("hourglass_top", size = 16.sp, color = D.amber)
        else -> Toggle(on = false, onToggle = onToggle)
    }
}

/**
 * Редактор туннеля (создание/правка): имя, тип, via-host (выпадающий список хостов), bind и dest.
 * Save собирает [buildTunnelDraft] и пишет через [TunnelManager]; для существующего показаны Remove и
 * live-throughput (когда активен). Поля сбрасываются при смене [existing] (через `key`).
 */
@Composable
private fun TunnelEditor(
    manager: TunnelManager,
    hosts: HostManagerController?,
    mono: FontFamily,
    existing: TunnelEntry?,
    onSaved: (String) -> Unit,
    onRequestRemove: () -> Unit,
) {
    val editingId = existing?.id
    val seed = existing?.tunnel
    // Ключ = editingId: поля — изолированный буфер правки, заполняется один раз на выбранный туннель.
    // Мутации entry.tunnel в обход (save из другого места для того же id) сюда намеренно НЕ долетают —
    // приоритет у незавершённых правок пользователя.
    var label by remember(editingId) { mutableStateOf(seed?.label ?: "") }
    var direction by remember(editingId) { mutableStateOf(seed?.direction ?: TunnelDirection.Local) }
    var hostId by remember(editingId) { mutableStateOf(seed?.hostId) }
    var bindHost by remember(editingId) { mutableStateOf(seed?.bindHost ?: "127.0.0.1") }
    var bindPort by remember(editingId) { mutableStateOf(seed?.bindPort?.toString() ?: "") }
    var destHost by remember(editingId) { mutableStateOf(seed?.destHost ?: "") }
    var destPort by remember(editingId) { mutableStateOf(seed?.destPort?.toString() ?: "") }

    val isDynamic = direction == TunnelDirection.Dynamic
    val draft = buildTunnelDraft(editingId, label, hostId, direction, bindHost, bindPort, destHost, destPort)
    val (badgeBg, badgeFg) = directionColors(direction)
    val hostList = hosts?.hosts ?: emptyList()
    val hostLabel = hostId?.let { id -> hostList.firstOrNull { it.id == id }?.label } ?: "Select host…"

    Column(
        Modifier.width(308.dp).fillMaxHeight().background(D.surface2).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge(directionBadge(direction), bg = badgeBg, fg = badgeFg, radius = 4, size = 10.sp)
            Txt(if (existing == null) "New tunnel" else "Tunnel detail", color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
        }
        FieldLabel("Name")
        EditField(label, { label = it }, "web tunnel", mono)
        Box(Modifier.padding(bottom = 12.dp))
        FieldLabel("Type")
        ClickableSelect(directionDisplay(direction)) { direction = direction.next() }
        Box(Modifier.padding(bottom = 12.dp))
        FieldLabel("Via host")
        HostPicker(hostLabel, hostList.map { it.id to it.label }, onPick = { hostId = it })
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
        if (existing != null && existing.status is TunnelStatus.Active) {
            Box(Modifier.padding(bottom = 16.dp))
            FieldLabel("Live throughput")
            ThroughputRow("arrow_upward", D.cyanBright, rateFraction(existing.upRate), humanRate(existing.upRate), mono)
            Box(Modifier.padding(bottom = 8.dp))
            ThroughputRow("arrow_downward", D.moss, rateFraction(existing.downRate), humanRate(existing.downRate), mono)
            Box(Modifier.padding(bottom = 10.dp))
            // Правка активного туннеля сохраняется, но проброс уже поднят — новые параметры подхватятся
            // при следующем включении (save не перезапускает соединение).
            Txt("Changes apply after the tunnel is restarted.", color = D.faint, size = 11.sp, lineHeight = 15.sp)
        }
        Box(Modifier.padding(bottom = 18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                label = "Save",
                onClick = { draft?.let { onSaved(manager.save(it)) } },
                modifier = Modifier.weight(1f),
                bg = if (draft != null) D.cyan else Color(0x14FFFFFF),
                fg = if (draft != null) Color(0xFF0A1A26) else D.faint,
            )
            if (existing != null) {
                GhostButton("Remove", onClick = onRequestRemove, fg = D.sunset, border = D.sunset.copy(alpha = 0.3f), modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Выпадающий список хостов поверх формы (через [AnchoredDropdown]); пустой — подсказка добавить хост. */
@Composable
private fun HostPicker(current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { open = !open }.background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt(current, color = D.text, size = 12.5.sp)
                Sym("expand_more", size = 16.sp, color = D.faint)
            }
        },
        menu = { width ->
            Column(
                Modifier.width(width).clip(RoundedCornerShape(8.dp)).background(D.surface2).border(1.dp, D.cyan14, RoundedCornerShape(8.dp)).heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
            ) {
                if (options.isEmpty()) {
                    Txt("No saved hosts", color = D.faint, size = 12.sp, modifier = Modifier.padding(12.dp))
                } else {
                    options.forEach { (id, name) ->
                        Txt(
                            name, color = D.text, size = 12.5.sp,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(id); open = false }.padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        },
    )
}

private fun directionColors(direction: TunnelDirection): Pair<Color, Color> = when (direction) {
    TunnelDirection.Local -> D.cyan.copy(alpha = 0.12f) to D.cyanBright
    TunnelDirection.Remote -> D.amber.copy(alpha = 0.14f) to D.amber
    TunnelDirection.Dynamic -> D.moss.copy(alpha = 0.14f) to D.moss
}

private fun directionBadge(direction: TunnelDirection): String = when (direction) {
    TunnelDirection.Local -> "LOCAL"
    TunnelDirection.Remote -> "REMOTE"
    TunnelDirection.Dynamic -> "SOCKS"
}

private fun TunnelDirection.next(): TunnelDirection = when (this) {
    TunnelDirection.Local -> TunnelDirection.Remote
    TunnelDirection.Remote -> TunnelDirection.Dynamic
    TunnelDirection.Dynamic -> TunnelDirection.Local
}

private fun directionDisplay(direction: TunnelDirection): String = when (direction) {
    TunnelDirection.Local -> "Local forward (-L)"
    TunnelDirection.Remote -> "Remote forward (-R)"
    TunnelDirection.Dynamic -> "Dynamic SOCKS (-D)"
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
                Txt("2 active tunnels", color = D.faint, size = 11.5.sp)
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
        FieldLabel("Name")
        InputField("web tunnel", mono)
        Box(Modifier.padding(bottom = 12.dp))
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

/** Редактируемое поле формы туннеля (стиль [InputField] + плейсхолдер + ввод). */
@Composable
private fun EditField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    mono: FontFamily,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val textStyle = remember(mono) { TextStyle(color = D.text, fontSize = 12.5.sp, fontFamily = mono) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(D.cyan),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 12.5.sp, font = mono)
                inner()
            }
        },
    )
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
