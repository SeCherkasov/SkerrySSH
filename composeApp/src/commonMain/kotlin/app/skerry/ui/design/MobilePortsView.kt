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
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.forward.ForwardDirection
import app.skerry.ui.forward.ForwardEntry
import app.skerry.ui.forward.ForwardStatus
import app.skerry.ui.forward.PortForwardController
import app.skerry.ui.forward.forwardSourceText
import app.skerry.ui.forward.forwardTypeLabel
import app.skerry.ui.forward.parseBindPort
import app.skerry.ui.forward.parseForwardInput

/** Фон карточки туннеля (белый 3%) макета PORTS. */
private val TunnelCardBg = Color(0x08FFFFFF)

/** Панель нижнего листа (`#0E1B26`) — та же, что лист New connection. */
private val SheetPanel = Color(0xFF0E1B26)

/**
 * Push-экран Port forwarding мобильного макета `docs/new/Skerry Mobile.html` (слайс 5): шапка-назад +
 * карточки туннелей + кнопка New tunnel. Поверх живого [PortForwardController] активной подключённой
 * сессии ([LocalSessions]) — реальные пробросы, тумблер карточки = pause/resume, long-press → Remove,
 * New tunnel открывает лист добавления (`-L`/`-R`/`-D`). Без сессии — notice, превью/офскрин — мок макета.
 *
 * Режим выбирается [mobilePortsMode] (как [mobileFilesMode] для Files). «via host» во всех карточках —
 * хост активной сессии (на телефоне один коннект за раз), честная проекция статичного «via …» макета.
 */
@Composable
fun MobilePortsScreen(state: MobileDesignState) {
    val mono = LocalFonts.current.mono
    val sessions = LocalSessions.current
    val active = sessions?.active
    val controller = active?.controller
    val connected = controller?.uiState is ConnectionUiState.Connected
    // Состояние листа добавления держим на уровне экрана: лист — полноэкранный оверлей в корневом Box
    // (как лист New connection в MobileChrome). Внутри прокручиваемого Column он не получил бы места.
    var adding by remember(controller) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(D.bg)) {
        Column(Modifier.fillMaxSize()) {
            MobilePortsHeader(onBack = state::pop)
            when (mobilePortsMode(hasSessions = sessions != null, connected = connected)) {
                MobilePortsMode.Preview -> MockMobilePortsBody(mono)
                // active?.let вместо !! : active — производный геттер над snapshot-полями, при гонке закрытия
                // сессии мог бы стать null даже при connected (как в MobileFilesScreen).
                MobilePortsMode.Live -> active?.let { LiveMobilePortsBody(it.controller, it.title, mono, onNewTunnel = { adding = true }) }
                MobilePortsMode.NoSession -> MobilePortsNotice()
            }
        }
        if (adding && connected && controller != null) {
            // openPortForwards() кэшируется на соединении — тот же контроллер, что в LiveMobilePortsBody.
            MobileAddTunnelSheet(controller.openPortForwards(), mono, onDismiss = { adding = false })
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

/**
 * Живое тело экрана поверх кэшированного [PortForwardController] сессии (открывается один раз, живёт на
 * scope сессии — переключение видов список не сбрасывает; все пробросы снимает disconnect). Карточки —
 * реальные пробросы; New tunnel открывает лист добавления.
 */
@Composable
private fun LiveMobilePortsBody(controller: ConnectionController, host: String, mono: FontFamily, onNewTunnel: () -> Unit) {
    val forwards = remember(controller) { controller.openPortForwards() }
    val rules = forwards.forwards

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (rules.isEmpty()) {
            MobileEmptyTunnels()
        } else {
            rules.forEach { entry ->
                // key по id: forEach переиспользует слоты позиционно — без ключа открытое контекстное
                // меню «переехало» бы на соседнюю карточку при добавлении/снятии проброса.
                key(entry.id) {
                    LiveTunnelCard(
                        entry = entry,
                        host = host,
                        mono = mono,
                        onToggle = { if (entry.paused) forwards.resume(entry) else forwards.pause(entry) },
                        onRemove = { forwards.remove(entry) },
                    )
                }
            }
        }
        MobileNewTunnelButton(onClick = onNewTunnel)
        Spacer(Modifier.height(30.dp))
    }
}

/**
 * Карточка живого туннеля по макету: бейдж типа + «via host» + тумблер (Active → pause/resume; Starting/
 * Failed → статус-иконка, как desktop-`ActiveCell`), строка source→dest. Long-press → меню Remove
 * (видимого якоря удаления в макете нет — прячем в контекстное меню, как на desktop/мобильном Files).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveTunnelCard(
    entry: ForwardEntry,
    host: String,
    mono: FontFamily,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember(entry.id) { mutableStateOf(false) }
    val (bg, fg) = tunnelBadgeColors(entry.direction)
    val dim = entry.paused
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(TunnelCardBg)
                .border(1.dp, D.cyan08, RoundedCornerShape(14.dp))
                .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(forwardTypeLabel(entry.direction), bg = bg, fg = fg, radius = 4, size = 9.5.sp)
                Txt("via $host", color = D.dim, size = 11.sp, font = mono, modifier = Modifier.weight(1f))
                TunnelStatusControl(entry, onToggle)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Txt(forwardSourceText(entry), color = if (dim) D.dim else D.text, size = 12.5.sp, font = mono)
                Sym(mobileTunnelArrow(entry.direction), size = 16.sp, color = D.faint)
                Txt(mobileTunnelDest(entry), color = if (dim) D.faint else D.textBright, size = 12.5.sp, font = mono)
            }
            (entry.status as? ForwardStatus.Failed)?.let {
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
                    PortMenuItem("Remove", D.sunset) { menuOpen = false; onRemove() }
                }
            }
        }
    }
}

/** Правый контрол карточки по статусу проброса (как desktop-`ActiveCell`). */
@Composable
private fun TunnelStatusControl(entry: ForwardEntry, onToggle: () -> Unit) {
    when (entry.status) {
        is ForwardStatus.Active -> Toggle(on = !entry.paused, onToggle = onToggle)
        ForwardStatus.Starting -> Sym("hourglass_top", size = 18.sp, color = D.amber)
        is ForwardStatus.Failed -> Sym("error", size = 18.sp, color = D.sunset)
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

/** Пустое состояние живого экрана (туннелей ещё нет) — добавляются кнопкой New tunnel ниже. */
@Composable
private fun MobileEmptyTunnels() {
    Box(Modifier.fillMaxWidth().padding(top = 50.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym("lan", size = 28.sp, color = D.faint)
            Txt("No tunnels yet", color = D.text, size = 14.sp, weight = FontWeight.Medium)
            Txt("Add a forward below", color = D.faint, size = 12.sp)
        }
    }
}

// ──────────────────────────────────────── New tunnel (лист добавления) ────────────────────────────────────────

/**
 * Нижний лист добавления туннеля. Кнопка New tunnel есть в макете, но самой формы макет не показывает
 * (на desktop она в боковой панели) — форму воспроизводим в идиоме листа New connection (sheet),
 * чтобы добавление `-L`/`-R`/`-D` было доступно и на телефоне (паритет с desktop, MVP Phase 1).
 */
@Composable
private fun MobileAddTunnelSheet(controller: PortForwardController, mono: FontFamily, onDismiss: () -> Unit) {
    var direction by remember { mutableStateOf(ForwardDirection.Local) }
    var bindHost by remember { mutableStateOf("127.0.0.1") }
    var bindPort by remember { mutableStateOf("") }
    var destHost by remember { mutableStateOf("") }
    var destPort by remember { mutableStateOf("") }

    val isDynamic = direction == ForwardDirection.Dynamic
    val request = if (isDynamic) null else parseForwardInput(bindPort, destHost, destPort)
    val bind = parseBindPort(bindPort)
    val canAdd = if (isDynamic) bind != null else request != null

    val (badgeBg, badgeFg) = tunnelBadgeColors(direction)
    // Стабилизируем по полям ввода (а не по производным request/bind — это свежие объекты каждой
    // рекомпозиции): фабрика remember перевыполняется ровно при изменении ввода, захватывая актуальные
    // canAdd/request/bind, и кнопка Add не пересоздаёт onClick на каждом кадре.
    val onAdd = remember(direction, bindHost, bindPort, destHost, destPort) {
        {
            if (canAdd) {
                val hostBind = bindHost.trim().ifEmpty { "127.0.0.1" }
                when (direction) {
                    ForwardDirection.Local -> request?.let { controller.addLocal(it.bindPort, it.destHost, it.destPort, hostBind) }
                    ForwardDirection.Remote -> request?.let { controller.addRemote(it.bindPort, it.destHost, it.destPort, hostBind) }
                    ForwardDirection.Dynamic -> bind?.let { controller.addDynamic(it, hostBind) }
                }
                onDismiss()
            }
        }
    }

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
                    Badge(forwardTypeLabel(direction), bg = badgeBg, fg = badgeFg, radius = 4, size = 9.5.sp)
                    Txt("New tunnel", color = D.text, size = 20.sp, weight = FontWeight.Bold)
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
            PortField("Type") { PortTypeSelect(direction) { direction = direction.next() } }
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
            Spacer(Modifier.height(22.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (canAdd) D.cyan else D.cyan.copy(alpha = 0.4f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onAdd)
                    .padding(15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt("Add tunnel", color = Color(0xFF0A1A26), size = 16.sp, weight = FontWeight.Bold)
            }
        }
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
private fun PortTypeSelect(direction: ForwardDirection, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(11.dp)).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(directionDisplay(direction), color = D.text, size = 15.sp)
        Sym("expand_more", size = 20.sp, color = D.faint)
    }
}

// ──────────────────────────────────────── New tunnel button / notices ────────────────────────────────────────

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

/** Менеджер сессий есть, но активная не подключена: пробросы строятся на живом соединении. */
@Composable
private fun MobilePortsNotice() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym("lan", size = 30.sp, color = D.faint)
            Txt("No active session", color = D.text, size = 14.sp, weight = FontWeight.Medium)
            Txt("Connect a host to set up port forwarding", color = D.faint, size = 12.sp)
        }
    }
}

private fun tunnelBadgeColors(direction: ForwardDirection): Pair<Color, Color> = when (direction) {
    ForwardDirection.Local -> D.cyan.copy(alpha = 0.12f) to D.cyanBright
    ForwardDirection.Remote -> D.amber.copy(alpha = 0.14f) to D.amber
    ForwardDirection.Dynamic -> D.moss.copy(alpha = 0.14f) to D.moss
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
