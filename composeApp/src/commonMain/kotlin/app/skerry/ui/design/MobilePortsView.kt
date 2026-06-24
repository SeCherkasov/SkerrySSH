package app.skerry.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.window.Popup
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.ui.forward.humanRate
import app.skerry.ui.forward.rateFraction
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.tunnel.TunnelEntry
import app.skerry.ui.tunnel.TunnelManager
import app.skerry.ui.tunnel.TunnelStatus
import app.skerry.ui.tunnel.buildTunnelDraft

/** Фон карточки туннеля (белый 3%) макета PORTS. */
private val TunnelCardBg = Color(0x08FFFFFF)

/** Панель нижнего листа (`#0E1B26`) — та же, что лист New connection. */
private val SheetPanel = Color(0xFF0E1B26)

/**
 * Push-экран Port forwarding мобильного макета `docs/new/Skerry Mobile.html`: шапка-назад + карточки
 * сохранённых туннелей + кнопка New tunnel. Туннели — ГЛОБАЛЬНЫЙ раздел (модель Termius): список и
 * включение/выключение идут через [TunnelManager] ([LocalTunnels]), без привязки к открытой сессии —
 * каждый туннель сам открывает соединение к своему хосту. Тумблер карточки = on/off, long-press →
 * Edit/Remove, New tunnel/Edit открывают лист-редактор. Без менеджера (превью/офскрин) — мок макета.
 */
@Composable
fun MobilePortsScreen(state: MobileDesignState) {
    val mono = LocalFonts.current.mono
    val manager = LocalTunnels.current
    val hosts = LocalHosts.current
    // Открытый редактор: null — закрыт, иначе id правимого туннеля либо "" для нового. Держим на уровне
    // экрана: лист — полноэкранный оверлей в корневом Box (как лист New connection в MobileChrome).
    // Без ключа: сброс — только явный onDismiss/onEdit, не смена идентичности менеджера.
    var editorFor by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(D.bg)) {
        Column(Modifier.fillMaxSize()) {
            MobilePortsHeader(onBack = state::pop)
            if (manager == null) {
                MockMobilePortsBody(mono)
            } else {
                LiveMobilePortsBody(
                    manager = manager,
                    hosts = hosts,
                    mono = mono,
                    onNew = { editorFor = "" },
                    onEdit = { editorFor = it },
                )
            }
        }
        if (manager != null && editorFor != null) {
            MobileTunnelEditorSheet(
                manager = manager,
                hosts = hosts,
                mono = mono,
                existing = editorFor?.takeIf { it.isNotEmpty() }?.let { id -> manager.tunnels.firstOrNull { it.id == id } },
                onDismiss = { editorFor = null },
            )
        }
    }
}

/** Шапка push-экрана: chevron_left (назад) + «Port forwarding». */
@Composable
private fun MobilePortsHeader(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Sym(
            "chevron_left",
            size = 27.sp,
            color = D.cyanBright,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack,
            ),
        )
        Txt("Port forwarding", color = D.text, size = 18.sp, weight = FontWeight.Bold)
    }
}

// ──────────────────────────────────────── Live ────────────────────────────────────────

@Composable
private fun LiveMobilePortsBody(
    manager: TunnelManager,
    hosts: HostManagerController?,
    mono: FontFamily,
    onNew: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val tunnels = manager.tunnels

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (tunnels.isEmpty()) {
            MobileEmptyTunnels()
        } else {
            tunnels.forEach { entry ->
                // key по id: forEach переиспользует слоты позиционно — без ключа открытое контекстное
                // меню «переехало» бы на соседнюю карточку при добавлении/снятии туннеля.
                key(entry.id) {
                    // Лямбды стабилизируем по id: телеметрия активных туннелей тикает раз в секунду и
                    // без remember пересоздавала бы их, перерисовывая все карточки. entry.status —
                    // snapshot-стейт, читается в момент клика, поэтому захват entry безопасен.
                    val onToggle = remember(entry.id, manager) {
                        {
                            if (entry.status is TunnelStatus.Active) manager.deactivate(entry.id)
                            else manager.activate(entry.id)
                        }
                    }
                    val onEditRow = remember(entry.id, onEdit) { { onEdit(entry.id) } }
                    val onRemoveRow = remember(entry.id, manager) { { manager.delete(entry.id) } }
                    LiveTunnelCard(
                        entry = entry,
                        via = hosts?.find(entry.tunnel.hostId)?.label ?: entry.tunnel.hostId,
                        mono = mono,
                        onToggle = onToggle,
                        onEdit = onEditRow,
                        onRemove = onRemoveRow,
                    )
                }
            }
        }
        MobileNewTunnelButton(onClick = onNew)
        Spacer(Modifier.height(30.dp))
    }
}

/**
 * Карточка сохранённого туннеля: бейдж типа + «via host» + тумблер on/off, строка source→dest.
 * Long-press → меню Edit/Remove (видимых якорей правки/удаления в макете нет — прячем в контекстное
 * меню, как на desktop/мобильном Files).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveTunnelCard(
    entry: TunnelEntry,
    via: String,
    mono: FontFamily,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember(entry.id) { mutableStateOf(false) }
    val t = entry.tunnel
    val (bg, fg) = tunnelBadgeColors(t.direction)
    val dim = entry.status !is TunnelStatus.Active
    val port = (entry.status as? TunnelStatus.Active)?.boundPort ?: t.bindPort
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(TunnelCardBg)
                .border(1.dp, D.cyan08, RoundedCornerShape(14.dp))
                .combinedClickable(onClick = onEdit, onLongClick = { menuOpen = true })
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(directionBadge(t.direction), bg = bg, fg = fg, radius = 4, size = 9.5.sp)
                Txt("via $via", color = D.dim, size = 11.sp, font = mono, modifier = Modifier.weight(1f))
                TunnelStatusControl(entry, onToggle)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Txt("${t.bindHost}:$port", color = if (dim) D.dim else D.text, size = 12.5.sp, font = mono)
                Sym(mobileTunnelArrow(t.direction), size = 16.sp, color = D.faint)
                Txt(mobileTunnelDest(t), color = if (dim) D.faint else D.textBright, size = 12.5.sp, font = mono)
            }
            (entry.status as? TunnelStatus.Failed)?.let {
                Spacer(Modifier.height(6.dp))
                Txt(it.message, color = D.sunset, size = 11.sp, font = mono)
            }
        }
        if (menuOpen) {
            Popup(alignment = Alignment.TopEnd, onDismissRequest = { menuOpen = false }) {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(D.surface2)
                        .border(1.dp, D.cyan14, RoundedCornerShape(8.dp))
                        .padding(4.dp),
                ) {
                    PortMenuItem("Edit", D.text) { menuOpen = false; onEdit() }
                    PortMenuItem("Remove", D.sunset) { menuOpen = false; onRemove() }
                }
            }
        }
    }
}

/** Правый контрол карточки по статусу: активный — тумблер вкл, подъём — часы, иначе — тумблер выкл. */
@Composable
private fun TunnelStatusControl(entry: TunnelEntry, onToggle: () -> Unit) {
    when (entry.status) {
        is TunnelStatus.Active -> Toggle(on = true, onToggle = onToggle)
        TunnelStatus.Connecting -> Sym("hourglass_top", size = 18.sp, color = D.amber)
        else -> Toggle(on = false, onToggle = onToggle)
    }
}

@Composable
private fun PortMenuItem(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Txt(label, color = color, size = 13.sp)
    }
}

/** Пустое состояние (туннелей ещё нет) — добавляются кнопкой New tunnel ниже. */
@Composable
private fun MobileEmptyTunnels() {
    Box(Modifier.fillMaxWidth().padding(top = 50.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym("lan", size = 28.sp, color = D.faint)
            Txt("No tunnels yet", color = D.text, size = 14.sp, weight = FontWeight.Medium)
            Txt("Add a tunnel below", color = D.faint, size = 12.sp)
        }
    }
}

// ──────────────────────────────────────── Tunnel editor (лист) ────────────────────────────────────────

/**
 * Нижний лист создания/правки туннеля в идиоме листа New connection (sheet): имя, тип, via-host (пикер
 * сохранённых хостов), bind и dest. Save собирает [buildTunnelDraft] и пишет через [TunnelManager];
 * для существующего показан Remove. Поля сидируются из [existing] при открытии.
 */
@Composable
private fun MobileTunnelEditorSheet(
    manager: TunnelManager,
    hosts: HostManagerController?,
    mono: FontFamily,
    existing: TunnelEntry?,
    onDismiss: () -> Unit,
) {
    val editingId = existing?.id
    val seed = existing?.tunnel
    // Ключ = editingId: поля — изолированный буфер правки, заполняется один раз на выбранный туннель.
    // Мутации entry.tunnel в обход (save из другого места для того же id) сюда намеренно НЕ долетают.
    var label by remember(editingId) { mutableStateOf(seed?.label ?: "") }
    var direction by remember(editingId) { mutableStateOf(seed?.direction ?: TunnelDirection.Local) }
    var hostId by remember(editingId) { mutableStateOf(seed?.hostId) }
    var bindHost by remember(editingId) { mutableStateOf(seed?.bindHost ?: "127.0.0.1") }
    var bindPort by remember(editingId) { mutableStateOf(seed?.bindPort?.toString() ?: "") }
    var destHost by remember(editingId) { mutableStateOf(seed?.destHost ?: "") }
    var destPort by remember(editingId) { mutableStateOf(seed?.destPort?.toString() ?: "") }

    val isDynamic = direction == TunnelDirection.Dynamic
    val draft = buildTunnelDraft(editingId, label, hostId, direction, bindHost, bindPort, destHost, destPort)
    val (badgeBg, badgeFg) = tunnelBadgeColors(direction)
    val hostList = hosts?.hosts ?: emptyList()
    val hostName = hostId?.let { id -> hostList.firstOrNull { it.id == id }?.label } ?: "Select host…"

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x8C04080C))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(SheetPanel)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 30.dp),
        ) {
            Box(
                Modifier
                    .padding(bottom = 16.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 38.dp, height = 5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0x2EFFFFFF)),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Badge(directionBadge(direction), bg = badgeBg, fg = badgeFg, radius = 4, size = 9.5.sp)
                    Txt(if (existing == null) "New tunnel" else "Edit tunnel", color = D.text, size = 20.sp, weight = FontWeight.Bold)
                }
                Sym(
                    "close",
                    size = 24.sp,
                    color = D.dim,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
                )
            }
            Spacer(Modifier.height(18.dp))
            PortField("Name") { PortInput(label, { label = it }, "web tunnel", mono) }
            Spacer(Modifier.height(14.dp))
            PortField("Type") { PortTypeSelect(direction) { direction = direction.next() } }
            Spacer(Modifier.height(14.dp))
            PortField("Via host") { MobileHostPicker(hostName, hostList.map { it.id to it.label }) { hostId = it } }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PortField("Bind address", Modifier.weight(1f)) { PortInput(bindHost, { bindHost = it }, "127.0.0.1", mono) }
                PortField("Port", Modifier.width(96.dp)) { PortInput(bindPort, { bindPort = it }, "8080", mono, KeyboardType.Number) }
            }
            if (!isDynamic) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PortField("Destination", Modifier.weight(1f)) { PortInput(destHost, { destHost = it }, "10.0.0.5", mono) }
                    PortField("Port", Modifier.width(96.dp)) { PortInput(destPort, { destPort = it }, "80", mono, KeyboardType.Number) }
                }
            } else {
                Spacer(Modifier.height(10.dp))
                Txt(
                    "SOCKS5 proxy — destination is chosen per connection by each client.",
                    color = D.faint,
                    size = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            if (existing != null && existing.status is TunnelStatus.Active) {
                Spacer(Modifier.height(16.dp))
                PortField("Live throughput") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MobileThroughputRow("arrow_upward", D.cyanBright, rateFraction(existing.upRate), humanRate(existing.upRate), mono)
                        MobileThroughputRow("arrow_downward", D.moss, rateFraction(existing.downRate), humanRate(existing.downRate), mono)
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Правка активного туннеля сохраняется, но проброс уже поднят — новые параметры
                // подхватятся при следующем включении (save не перезапускает соединение).
                Txt("Changes apply after the tunnel is restarted.", color = D.faint, size = 12.sp, lineHeight = 16.sp)
            }
            Spacer(Modifier.height(22.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (draft != null) D.cyan else D.cyan.copy(alpha = 0.4f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {
                        draft?.let { manager.save(it); onDismiss() }
                    })
                    .padding(15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt("Save tunnel", color = Color(0xFF0A1A26), size = 16.sp, weight = FontWeight.Bold)
            }
            if (existing != null) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, D.sunset.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {
                            manager.delete(existing.id); onDismiss()
                        })
                        .padding(15.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt("Remove tunnel", color = D.sunset, size = 16.sp, weight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Пикер хоста в листе: тап раскрывает список сохранённых хостов поверх (Popup). */
@Composable
private fun MobileHostPicker(current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { open = !open }).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(11.dp)).padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Txt(current, color = D.text, size = 15.sp)
            Sym("expand_more", size = 20.sp, color = D.faint)
        }
        if (open) {
            Popup(alignment = Alignment.TopStart, onDismissRequest = { open = false }) {
                Column(
                    Modifier.width(260.dp).clip(RoundedCornerShape(11.dp)).background(D.surface2).border(1.dp, D.cyan14, RoundedCornerShape(11.dp)).heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                ) {
                    if (options.isEmpty()) {
                        Txt("No saved hosts", color = D.faint, size = 13.sp, modifier = Modifier.padding(14.dp))
                    } else {
                        options.forEach { (id, name) ->
                            Txt(name, color = D.text, size = 15.sp, modifier = Modifier.fillMaxWidth().clickable { onPick(id); open = false }.padding(horizontal = 14.dp, vertical = 11.dp))
                        }
                    }
                }
            }
        }
    }
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

private fun directionBadge(direction: TunnelDirection): String = when (direction) {
    TunnelDirection.Local -> "LOCAL"
    TunnelDirection.Remote -> "REMOTE"
    TunnelDirection.Dynamic -> "SOCKS"
}

/** Подпись поля (капс) + содержимое — идиома полей листа New connection. */
@Composable
private fun PortField(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Txt(label.uppercase(), color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
private fun PortInput(value: String, onValueChange: (String) -> Unit, placeholder: String, mono: FontFamily, keyboardType: KeyboardType = KeyboardType.Text) {
    val textStyle = remember(mono) { TextStyle(color = D.text, fontSize = 15.sp, fontFamily = mono) }
    // Рамка — в decorationBox, чтобы клик по всей площади поля ставил каретку.
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
                Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(11.dp)).padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 15.sp, font = mono)
                inner()
            }
        },
    )
}

/** Кликабельный селект типа: тап циклически меняет `-L`→`-R`→`-D` (на телефоне дропдаун ни к чему). */
@Composable
private fun PortTypeSelect(direction: TunnelDirection, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(11.dp)).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(directionDisplay(direction), color = D.text, size = 15.sp)
        Sym("expand_more", size = 20.sp, color = D.faint)
    }
}

// ──────────────────────────────────────── New tunnel button ────────────────────────────────────────

/** Дашед-кнопка «New tunnel» макета (cyan-рамка, иконка add). */
@Composable
private fun MobileNewTunnelButton(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .border(1.dp, D.cyan20, RoundedCornerShape(13.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        Sym("add", size = 19.sp, color = D.cyanBright)
        Txt("New tunnel", color = D.cyanBright, size = 14.sp, weight = FontWeight.Medium)
    }
}

/** Строка скорости в листе-редакторе (стрелка + бар + текст) — мобильный аналог desktop-ThroughputRow. */
@Composable
private fun MobileThroughputRow(icon: String, color: Color, fraction: Float, value: String, mono: FontFamily) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Sym(icon, size = 16.sp, color = color)
        MeterBar(fraction, color, Modifier.weight(1f))
        Box(Modifier.width(72.dp), contentAlignment = Alignment.CenterEnd) {
            Txt(value, color = D.dim, size = 12.sp, font = mono)
        }
    }
}

private fun tunnelBadgeColors(direction: TunnelDirection): Pair<Color, Color> = when (direction) {
    TunnelDirection.Local -> D.cyan.copy(alpha = 0.12f) to D.cyanBright
    TunnelDirection.Remote -> D.amber.copy(alpha = 0.14f) to D.amber
    TunnelDirection.Dynamic -> D.moss.copy(alpha = 0.14f) to D.moss
}

// ──────────────────────────────────────── Mock (preview/офскрин) ────────────────────────────────────────

private data class MockTunnel(val type: String, val bg: Color, val fg: Color, val via: String, val source: String, val arrow: String, val dest: String, val destDim: Boolean, val on: Boolean)

/** Статичные туннели ровно из макета PORTS — для офскрин-сверки 1:1. */
private val MOCK_TUNNELS = listOf(
    MockTunnel("LOCAL", D.cyan.copy(alpha = 0.12f), D.cyanBright, "via prod-web-01", "127.0.0.1:8080", "arrow_forward", "10.0.0.5:80", false, true),
    MockTunnel("REMOTE", D.amber.copy(alpha = 0.14f), D.amber, "via homelab-pi", "0.0.0.0:9000", "arrow_forward", "localhost:3000", false, true),
    MockTunnel("SOCKS", D.moss.copy(alpha = 0.14f), D.moss, "via db-master", "127.0.0.1:1080", "all_inclusive", "dynamic proxy", true, false),
)

@Composable
private fun MockMobilePortsBody(mono: FontFamily) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MOCK_TUNNELS.forEach { MockTunnelCard(it, mono) }
        MobileNewTunnelButton(onClick = {})
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun MockTunnelCard(t: MockTunnel, mono: FontFamily) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(TunnelCardBg)
            .border(1.dp, D.cyan08, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge(t.type, bg = t.bg, fg = t.fg, radius = 4, size = 9.5.sp)
            Txt(t.via, color = D.dim, size = 11.sp, font = mono, modifier = Modifier.weight(1f))
            Toggle(on = t.on, onToggle = {})
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Txt(t.source, color = D.text, size = 12.5.sp, font = mono)
            Sym(t.arrow, size = 16.sp, color = D.faint)
            Txt(t.dest, color = if (t.destDim) D.dim else D.textBright, size = 12.5.sp, font = mono)
        }
    }
}
