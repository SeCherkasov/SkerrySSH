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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.connection.shortCipher
import kotlin.math.roundToInt
import app.skerry.ui.host.HostFolder
import app.skerry.ui.host.groupHostsByFolder
import app.skerry.ui.metrics.HostMetrics
import app.skerry.ui.metrics.formatUptime
import app.skerry.ui.session.SessionsController
import app.skerry.ui.terminal.TerminalScreen

/** Терминальный view: hosts-sidebar + main (toolbar, панели, AI-bar) + info-panel. */
@Composable
fun TerminalView(state: DesktopDesignState) {
    Row(Modifier.fillMaxSize()) {
        HostsSidebar(state)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            SessionToolbar(state)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                TerminalPane(state, Modifier.weight(1f))
                if (state.split) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(D.cyan14))
                    // Живой split (за гейтом vault) — вторая сессия с пикером; иначе мок-демо макета.
                    val sessions = LocalSessions.current
                    if (sessions != null) LiveSplitPane(sessions, Modifier.weight(1f)) else SplitPane(Modifier.weight(1f))
                }
                if (state.infoPanel) InfoPanel()
            }
            // AI-бар — фича MVP2 за фича-флагом; в MVP1 (дефолт) терминал без него.
            if (LocalFeatures.current.ai) AiBar()
        }
    }
}

// ──────────────────────────────── hosts sidebar ────────────────────────────────

@Composable
private fun HostsSidebar(state: DesktopDesignState) {
    val mono = LocalFonts.current.mono
    Column(Modifier.width(262.dp).fillMaxHeight().background(D.surface2)) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0x08FFFFFF))
                    .border(1.dp, D.line, RoundedCornerShape(7.dp))
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Sym("search", size = 16.sp, color = D.faint)
                Txt("Search hosts, tags, IPs…", color = D.faint, size = 12.5.sp, modifier = Modifier.weight(1f))
                Box(Modifier.border(1.dp, D.cyan14, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                    Txt("⌘K", color = D.faint, size = 10.sp, font = mono)
                }
            }
            Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Chip("All", active = true)
                Chip("#prod")
                Chip("#docker")
                Chip("#db")
            }
        }
        HLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt("HOSTS", color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
                Sym("create_new_folder", size = 14.sp, color = D.faint)
            }
            // Живой каталог из HostManagerController, если подан (за гейтом vault); иначе мок-данные
            // макета (путь офскрин-рендера/превью).
            val liveHosts = LocalHosts.current
            if (liveHosts != null) {
                val folders = remember(liveHosts.hosts) { groupHostsByFolder(liveHosts.hosts) }
                folders.forEach { folder -> LiveHostFolder(folder, state, mono) }
            } else {
                HOST_GROUPS.forEach { group -> HostGroupBlock(group, state, mono) }
            }
            Txt(
                "RECENT",
                color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 4.dp),
            )
            Row(
                Modifier.padding(start = 16.dp).padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Sym("history", size = 14.sp, color = D.faint)
                Txt("user@vps.example.com", color = D.dim, size = 11.5.sp, font = mono)
            }
        }
        HLine()
        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            PrimaryButton("New connection", onClick = state::openModal, icon = "add_link", modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Заголовок папки хостов: стрелка + иконка + имя + счётчик. */
@Composable
private fun FolderHeader(name: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Sym("expand_more", size = 16.sp, color = D.faint)
        Sym("folder_open", size = 15.sp, color = D.cyanBright)
        Txt(name, color = D.dim, size = 12.5.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x0AFFFFFF)).padding(horizontal = 6.dp, vertical = 1.dp)) {
            Txt(count.toString(), color = D.faint, size = 10.sp)
        }
    }
}

@Composable
private fun HostGroupBlock(group: HostGroup, state: DesktopDesignState, mono: FontFamily) {
    Column(Modifier.padding(bottom = 2.dp)) {
        FolderHeader(group.name, group.hosts.size)
        Column(Modifier.padding(start = 22.dp)) {
            group.hosts.forEach { host -> HostRow(host, state, mono) }
        }
    }
}

/**
 * Живая папка каталога: те же визуалы, но из [HostFolder] поверх [HostManagerController]. Клик по
 * хосту запускает подключение ([LocalConnectHost]); точка статуса и подсветка берутся из живых
 * сессий ([LocalSessions]) — хост подсвечен, если он у активной вкладки, цвет точки = состояние
 * соединения свежайшей его сессии.
 */
@Composable
private fun LiveHostFolder(folder: HostFolder, state: DesktopDesignState, mono: FontFamily) {
    val sessions = LocalSessions.current
    val connect = LocalConnectHost.current
    Column(Modifier.padding(bottom = 2.dp)) {
        FolderHeader(folder.name, folder.hosts.size)
        Column(Modifier.padding(start = 22.dp)) {
            // key(host.id): позиционная идентичность строк фиксируется на хосте — открытое меню/состояние
            // строки не «переезжает» на соседа при переупорядочивании каталога после правки.
            folder.hosts.forEach { host ->
                key(host.id) {
                    // Лямбды стабилизируем по (host, …): иначе каждая рекомпозиция папки пересоздавала бы
                    // их и заставляла строку (nullable-функции нестабильны) перерисовываться впустую.
                    val onClick = remember(host, connect) { { connect(host) } }
                    val onEdit = remember(host, state) { { state.openEditModal(host) } }
                    val onDelete = remember(host, state) { { state.requestDeleteHost(host) } }
                    HostEntryRow(
                        label = host.label,
                        selected = sessions?.active?.hostId == host.id,
                        dot = sessionDotColor(sessions?.statusFor(host.id)),
                        badge = null,
                        onClick = onClick,
                        mono = mono,
                        // Правка/удаление профиля — через контекстное меню (right-click/long-press),
                        // как в шаблоне без отдельных кнопок/⋮.
                        onEdit = onEdit,
                        onDelete = onDelete,
                    )
                }
            }
        }
    }
}

@Composable
private fun HostRow(host: MockHost, state: DesktopDesignState, mono: FontFamily) {
    HostEntryRow(
        label = host.name,
        selected = state.selectedHost == host.name,
        dot = host.status.color,
        badge = host.badge,
        onClick = { state.selectHost(host.name) },
        mono = mono,
    )
}

/**
 * Общая строка хоста в сайдбаре (мок и живой каталог): точка статуса + имя + опц. бейдж. Клик по
 * строке — подключение ([onClick]). Когда переданы [onEdit]/[onDelete] (живой каталог), в конце
 * строки появляется кнопка «⋮», открывающая выпадающее меню Edit/Delete; её собственный клик
 * перехватывается раньше [onClick], поэтому открытие меню не запускает подключение.
 */
@Composable
private fun HostEntryRow(
    label: String,
    selected: Boolean,
    dot: Color,
    badge: String?,
    onClick: () -> Unit,
    mono: FontFamily,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val hasMenu = onEdit != null || onDelete != null
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) D.cyan10 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 2.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(dot)
        Txt(label, color = if (selected) D.cyanBright else D.dim, size = 11.5.sp, font = mono, modifier = Modifier.weight(1f))
        if (badge != null) {
            val strict = badge == "STRICT"
            Badge(badge, bg = if (strict) D.strictBg else D.devBg, fg = if (strict) D.strictFg else D.moss)
        }
        if (hasMenu) {
            Box {
                IconBtn("more_vert", onClick = { menuOpen = !menuOpen }, box = 22, icon = 16.sp, tint = D.faint)
                if (menuOpen) {
                    Popup(alignment = Alignment.TopEnd, onDismissRequest = { menuOpen = false }) {
                        Column(
                            Modifier.clip(RoundedCornerShape(7.dp)).background(D.surface2).border(1.dp, D.lineStrong, RoundedCornerShape(7.dp)).padding(4.dp),
                        ) {
                            onEdit?.let { edit ->
                                HostMenuItem("Edit", D.text) { menuOpen = false; edit() }
                            }
                            onDelete?.let { delete ->
                                HostMenuItem("Delete", D.sunset) { menuOpen = false; delete() }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Пункт контекстного меню строки хоста. */
@Composable
private fun HostMenuItem(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(5.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Txt(label, color = color, size = 12.sp)
    }
}

// ──────────────────────────────── session toolbar ────────────────────────────────

@Composable
private fun SessionToolbar(state: DesktopDesignState) {
    val mono = LocalFonts.current.mono
    val sessions = LocalSessions.current
    val active = sessions?.active
    Column {
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (active != null) {
                    // Живой заголовок: ярлык хоста + user@addr:port + точка состояния соединения.
                    Txt(active.title, color = D.text, size = 12.sp, weight = FontWeight.Medium, font = mono)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Txt(active.subtitle, color = D.dim, size = 11.5.sp, font = mono)
                        Dot(sessionDotColor(active.controller.uiState))
                    }
                } else if (sessions != null) {
                    // Живой режим без активной сессии: честное пустое состояние, без фейкового хоста.
                    Txt("No active session", color = D.faint, size = 12.sp, font = mono)
                } else {
                    // Мок/превью (офскрин-рендер без LocalSessions): статичный заголовок макета.
                    Txt("root@prod-web-01", color = D.text, size = 12.sp, weight = FontWeight.Medium, font = mono)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt("192.168.1.45:22", color = D.dim, size = 11.5.sp)
                        Txt(" · ", color = D.faint, size = 11.5.sp)
                        Txt("●", color = D.moss, size = 11.5.sp)
                        Txt(" 04:12:45", color = D.faint, size = 11.5.sp)
                    }
                    Txt("SSHv2 · aes256-gcm · ed25519", color = D.faint, size = 11.5.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconBtn("splitscreen_right", onClick = state::toggleSplit)
                IconBtn("folder", onClick = { state.showView(DesktopView.Sftp) })
                IconBtn("lan", onClick = { state.showView(DesktopView.Ports) })
                IconBtn("info", onClick = state::toggleInfo)
                // Power: рвёт активную сессию (живой путь); в мок-режиме — no-op заглушка.
                IconBtn("power_settings_new", onClick = { if (active != null) sessions.close(active.id) }, tint = D.sunset)
            }
        }
        HLine()
    }
}

// ──────────────────────────────── terminal pane ────────────────────────────────

/**
 * Терминальная область: живая ([LocalSessions] подан, за гейтом vault) или мок-демо макета.
 * Живой путь рендерит реальную сетку активной сессии через готовый [TerminalScreen] (VT-эмулятор
 * + ввод в PTV) или экран-плейсхолдер для прочих состояний соединения.
 */
@Composable
private fun TerminalPane(state: DesktopDesignState, modifier: Modifier = Modifier) {
    val sessions = LocalSessions.current
    if (sessions != null) LiveTerminalPane(sessions, modifier) else MockTerminalPane(state, modifier)
}

/** Живой терминал активной вкладки: рендер по состоянию её [ConnectionUiState]. */
@Composable
private fun LiveTerminalPane(sessions: SessionsController, modifier: Modifier = Modifier) {
    val active = sessions.active
    Box(modifier.fillMaxHeight().fillMaxWidth().background(D.terminalBg)) {
        when (val st = active?.controller?.uiState) {
            null, ConnectionUiState.Form -> TerminalNotice("terminal", "No active session", "Pick a host from the sidebar to connect.")
            ConnectionUiState.Connecting -> TerminalNotice("sync", "Connecting…", active.subtitle)
            is ConnectionUiState.Connected -> TerminalScreen(st.terminal, Modifier.fillMaxSize())
            is ConnectionUiState.Error -> TerminalNotice("error", "Connection failed", st.message, color = D.sunset)
        }
    }
}

/** Центрированное сообщение на фоне терминала (нет сессии / подключение / ошибка). */
@Composable
private fun TerminalNotice(icon: String, title: String, subtitle: String, color: Color = D.dim) {
    val mono = LocalFonts.current.mono
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Sym(icon, size = 30.sp, color = color)
        Txt(title, color = D.text, size = 14.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        Txt(subtitle, color = D.faint, size = 12.sp, font = mono)
    }
}

/** Демо-терминал макета (мок-путь без живых сессий: офскрин-рендер/превью). */
@Composable
private fun MockTerminalPane(state: DesktopDesignState, modifier: Modifier = Modifier) {
    val mono = LocalFonts.current.mono
    Column(modifier.fillMaxHeight().background(D.terminalBg)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
            Txt("Last login: Sat Jun 21 14:22:10 2026 from 10.0.0.15", color = D.faint, size = 13.sp, font = mono, lineHeight = 20.sp)
            Prompt(mono, "df -h")
            TermOut("Filesystem      Size  Used Avail Use% Mounted on", mono)
            Row {
                Txt("/dev/sda1        50G   42G  ", color = D.textMid, size = 13.sp, font = mono, lineHeight = 20.sp)
                Txt("5.2G   87%", color = D.sunset, size = 13.sp, font = mono, lineHeight = 20.sp)
                Txt(" /", color = D.textMid, size = 13.sp, font = mono, lineHeight = 20.sp)
            }
            TermOut("/dev/sda2       200G  120G   75G  62% /var", mono)
            TermOut("tmpfs           4.0G  1.2M  4.0G   1% /tmp", mono)
            Prompt(mono, "tail -f /var/log/nginx/access.log")
            LogLine(mono, "127.0.0.1 - - [21/Jun/2026:14:25:01] \"GET /api/v1/status HTTP/1.1\" ", "200", " 154", D.moss)
            LogLine(mono, "127.0.0.1 - - [21/Jun/2026:14:25:05] \"POST /api/v1/telemetry HTTP/1.1\" ", "201", " 12", D.moss)
            TermOut("127.0.0.1 - - [21/Jun/2026:14:25:12] \"GET /admin/config HTTP/1.1\" 403 89", mono, color = D.sunset)
            LogLine(mono, "127.0.0.1 - - [21/Jun/2026:14:25:15] \"GET /assets/main.css HTTP/1.1\" ", "200", " 4521", D.moss)
            TermOut("127.0.0.1 - - [21/Jun/2026:14:25:22] \"POST /api/auth HTTP/1.1\" 500 245", mono, color = D.storm)

            // AI-карточка — фича MVP2 за фича-флагом; в MVP1 (дефолт) её в выводе нет.
            if (LocalFeatures.current.ai) AiSuggestionCard()

            state.termLines.forEach { line ->
                if (line.isCmd) Prompt(mono, line.text) else TermOut(line.text, mono, color = line.color)
            }

            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                PromptLabel(mono)
                BasicTextField(
                    value = state.cmd,
                    onValueChange = state::onCmd,
                    singleLine = true,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    textStyle = TextStyle(color = D.white, fontSize = 13.sp, fontFamily = mono),
                    cursorBrush = SolidColor(D.cyan),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { state.runCmd() }),
                    decorationBox = { inner ->
                        if (state.cmd.isEmpty()) {
                            Txt("type a command — try: ls, df -h, help", color = D.faint, size = 13.sp, font = mono)
                        }
                        inner()
                    },
                )
            }
        }
    }
}

@Composable
private fun PromptLabel(mono: FontFamily) {
    Row {
        Txt("root@prod-web-01", color = D.cyan, size = 13.sp, weight = FontWeight.SemiBold, font = mono)
        Txt(":", color = D.cyanBright, size = 13.sp, font = mono)
        Txt("~", color = D.moss, size = 13.sp, font = mono)
        Txt("#", color = D.cyanBright, size = 13.sp, font = mono)
    }
}

@Composable
private fun Prompt(mono: FontFamily, cmd: String) {
    Row {
        PromptLabel(mono)
        Txt(" $cmd", color = D.white, size = 13.sp, font = mono, lineHeight = 20.sp)
    }
}

@Composable
private fun TermOut(text: String, mono: FontFamily, color: Color = D.textMid) {
    Txt(text, color = color, size = 13.sp, font = mono, lineHeight = 20.sp)
}

@Composable
private fun LogLine(mono: FontFamily, head: String, code: String, tail: String, codeColor: Color) {
    Row {
        Txt(head, color = D.textMid, size = 13.sp, font = mono, lineHeight = 20.sp)
        Txt(code, color = codeColor, size = 13.sp, font = mono, lineHeight = 20.sp)
        Txt(tail, color = D.textMid, size = 13.sp, font = mono, lineHeight = 20.sp)
    }
}

// ──────────────────────────────── AI suggestion card ────────────────────────────────

@Composable
private fun AiSuggestionCard() {
    val mono = LocalFonts.current.mono
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 14.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(D.sunset.copy(alpha = 0.04f))
            .border(1.dp, D.amber.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym("auto_awesome", size = 15.sp, color = D.sunset)
            Txt("AI SUGGESTION (UNTRUSTED LOG SOURCE)", color = D.sunset, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.weight(1f))
            Txt("Qwen 2.5 Coder · local", color = D.faint, size = 10.sp, font = mono)
        }
        HLine(D.sunset.copy(alpha = 0.2f))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Txt("I see a 500 error on POST /api/auth and disk is at 87%. To investigate the auth error, check the application error log:", color = D.dim, size = 12.sp, lineHeight = 18.sp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0x4D000000))
                    .border(1.dp, D.cyan14, RoundedCornerShape(5.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Txt("tail -n 50 /var/log/nginx/error.log | grep -A 5 \"auth\"", color = D.text, size = 12.5.sp, font = mono)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(D.sunset.copy(alpha = 0.08f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Sym("warning", size = 14.sp, color = D.sunset)
                Txt("This analysis used untrusted log content. Inspect the command carefully — log entries can be crafted to manipulate AI output.", color = D.sunset, size = 11.5.sp, lineHeight = 16.sp)
            }
        }
        HLine(D.amber.copy(alpha = 0.15f))
        Row(
            Modifier.fillMaxWidth().background(Color(0x26000000)).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.clip(RoundedCornerShape(5.dp)).background(D.amber).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Txt("Insert into terminal", color = Color(0xFF0A1A26), size = 11.5.sp, weight = FontWeight.SemiBold)
            }
            Box(Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, D.cyan14, RoundedCornerShape(5.dp)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Txt("Explain", color = D.text, size = 11.5.sp, weight = FontWeight.Medium)
            }
            Box(Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, D.cyan14, RoundedCornerShape(5.dp)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Txt("Dismiss", color = D.dim, size = 11.5.sp, weight = FontWeight.Medium)
            }
        }
    }
}

// ──────────────────────────────── split pane ────────────────────────────────

/**
 * Живая split-панель: показывает рядом вторую сессию ([SessionsController.split]). Заголовок —
 * пикер: клик раскрывает список открытых сессий ([Popup]), выбор назначает её в панель
 * ([SessionsController.setSplit]). Тело рендерит терминал выбранной сессии по её состоянию
 * соединения — тем же приёмом, что [LiveTerminalPane]; пока сессия не выбрана — подсказка.
 */
@Composable
private fun LiveSplitPane(sessions: SessionsController, modifier: Modifier = Modifier) {
    val mono = LocalFonts.current.mono
    var pickerOpen by remember { mutableStateOf(false) }
    val split = sessions.split
    Column(modifier.fillMaxHeight().background(D.terminalBg)) {
        Box(Modifier.fillMaxWidth().background(D.panel)) {
            Row(
                Modifier.fillMaxWidth().clickable { pickerOpen = !pickerOpen }.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Dot(sessionDotColor(split?.controller?.uiState))
                Txt(
                    split?.subtitle ?: "Select a session…",
                    color = if (split != null) D.dim else D.faint,
                    size = 11.sp, font = mono, modifier = Modifier.weight(1f),
                )
                Sym(if (pickerOpen) "expand_less" else "expand_more", size = 16.sp, color = D.faint)
            }
            if (pickerOpen) {
                Popup(alignment = Alignment.BottomStart, onDismissRequest = { pickerOpen = false }) {
                    SplitSessionPicker(sessions, split?.id) { id -> sessions.setSplit(id); pickerOpen = false }
                }
            }
        }
        HLine()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val st = split?.controller?.uiState) {
                null -> TerminalNotice("splitscreen_right", "No session selected", "Pick a session to show it side by side.")
                ConnectionUiState.Form -> TerminalNotice("terminal", "Session closed", split.subtitle)
                ConnectionUiState.Connecting -> TerminalNotice("sync", "Connecting…", split.subtitle)
                is ConnectionUiState.Connected -> TerminalScreen(st.terminal, Modifier.fillMaxSize())
                is ConnectionUiState.Error -> TerminalNotice("error", "Connection failed", st.message, color = D.sunset)
            }
        }
    }
}

/** Выпадающий список сессий для split-пикера: каждая строка назначает её в панель; «Clear» снимает. */
@Composable
private fun SplitSessionPicker(sessions: SessionsController, selectedId: String?, onPick: (String?) -> Unit) {
    val mono = LocalFonts.current.mono
    Column(
        Modifier
            .width(240.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(D.surface2)
            .border(1.dp, D.cyan14, RoundedCornerShape(7.dp))
            .padding(4.dp),
    ) {
        sessions.sessions.forEach { s ->
            val selected = s.id == selectedId
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (selected) D.cyan10 else Color.Transparent)
                    .clickable { onPick(s.id) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Dot(sessionDotColor(s.controller.uiState))
                Txt(s.title, color = if (selected) D.cyanBright else D.dim, size = 11.5.sp, font = mono, modifier = Modifier.weight(1f))
            }
        }
        if (selectedId != null) {
            HLine()
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)).clickable { onPick(null) }.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Sym("close", size = 14.sp, color = D.faint)
                Txt("Clear split", color = D.faint, size = 11.5.sp)
            }
        }
    }
}

@Composable
private fun SplitPane(modifier: Modifier = Modifier) {
    val mono = LocalFonts.current.mono
    Column(modifier.fillMaxHeight().background(D.terminalBg)) {
        Box(Modifier.fillMaxWidth().background(D.panel).padding(horizontal = 14.dp, vertical = 6.dp)) {
            Txt("root@db-master · 192.168.1.50", color = D.dim, size = 11.sp, font = mono)
        }
        HLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row {
                Txt("root@db-master", color = D.cyan, size = 13.sp, weight = FontWeight.SemiBold, font = mono)
                Txt(":", color = D.cyanBright, size = 13.sp, font = mono)
                Txt("~", color = D.moss, size = 13.sp, font = mono)
                Txt("# ", color = D.cyanBright, size = 13.sp, font = mono)
                Txt("systemctl status postgresql", color = D.white, size = 13.sp, font = mono)
            }
            TermOut("● postgresql.service - PostgreSQL RDBMS", mono, color = D.moss)
            TermOut("   Loaded: loaded (/lib/systemd/system/postgresql.service)", mono)
            Row {
                Txt("   Active: ", color = D.textMid, size = 13.sp, font = mono)
                Txt("active (running)", color = D.moss, size = 13.sp, font = mono)
                Txt(" since Fri 2026-06-20", color = D.textMid, size = 13.sp, font = mono)
            }
            TermOut("   Memory: 412.0M", mono)
            TermOut("      CPU: 2h 14min 03s", mono)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("root@db-master", color = D.cyan, size = 13.sp, weight = FontWeight.SemiBold, font = mono)
                Txt(":", color = D.cyanBright, size = 13.sp, font = mono)
                Txt("~", color = D.moss, size = 13.sp, font = mono)
                Txt("# ", color = D.cyanBright, size = 13.sp, font = mono)
                Box(Modifier.padding(start = 2.dp).size(width = 7.dp, height = 14.dp).background(D.cyan))
            }
        }
    }
}

// ──────────────────────────────── info panel ────────────────────────────────

@Composable
private fun InfoPanel() {
    val mono = LocalFonts.current.mono
    // Живой контекст активной сессии (если есть): профиль хоста из каталога + состояние соединения.
    val sessions = LocalSessions.current
    val hosts = LocalHosts.current
    val active = sessions?.active
    val host = active?.hostId?.let { id -> hosts?.find(id) }
    val live = sessions != null
    val connected = active?.controller?.uiState is ConnectionUiState.Connected
    // Контроллер live-метрик активной сессии (когда подключена). remember безусловный — ключи
    // (id сессии + флаг connected) пересоздают его при смене сессии/подключения, без условного
    // вызова remember. openMetrics идемпотентен (кэш в ConnectionController).
    val liveMetrics = remember(active, connected) {
        if (connected && active != null) active.controller.openMetrics() else null
    }?.metrics
    Column(Modifier.width(268.dp).fillMaxHeight().background(D.surface2).verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Txt("CONNECTION", color = D.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val dot = if (live) sessionDotColor(active?.controller?.uiState) else D.moss
                val label = if (!live) "LIVE" else if (connected) "LIVE" else "—"
                Dot(dot)
                Txt(label, color = dot, size = 10.sp)
            }
        }
        HLine()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            // Host/Address/User — из живого профиля активной сессии; cipher — из транспорта,
            // uptime — из live-метрик (до первого опроса «…»); в мок-режиме всё из макета.
            InfoRow("Host", if (live) (host?.label ?: active?.title ?: "—") else "prod-web-01", mono)
            InfoRow("Address", if (live) (host?.let { "${it.address}:${it.port}" } ?: "—") else "192.168.1.45:22", mono)
            InfoRow("User", if (live) (host?.username ?: "—") else "root", mono)
            InfoRow("Auth", if (live) (host?.identityId?.let { "identity" } ?: "password") else "id_ed25519", mono)
            InfoRow("Cipher", if (live) (shortCipher(active?.controller?.cipher) ?: "…") else "aes256-gcm", mono)
            InfoRow("Uptime", if (live) (liveMetrics?.uptimeSeconds?.let { formatUptime(it) } ?: "…") else "04:12:45", mono)
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
            Txt("LIVE METRICS", color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(vertical = 8.dp))
            if (!live) {
                // Мок-путь (превью/офскрин): статичные значения макета.
                Meter("CPU", "34%", 0.34f, D.cyan, D.textBright, mono)
                Meter("Memory", "2.1 / 4 GB", 0.52f, D.moss, D.textBright, mono)
                Meter("Disk /", "87%", 0.87f, D.sunset, D.sunset, mono)
            } else {
                // Живой опрос ресурсов сессии (контроллер поднят выше). До первого удачного
                // опроса (или на не-Linux хосте) — «…».
                val m = liveMetrics
                val cpu = m?.cpuPercent
                Meter("CPU", cpu?.let { "$it%" } ?: "…", m?.cpuFraction ?: 0f, D.cyan, if ((cpu ?: 0) > 85) D.sunset else D.textBright, mono)
                Meter("Memory", m?.let { "${gb(it.memUsedBytes)} / ${gb(it.memTotalBytes)} GB" } ?: "…", m?.memFraction ?: 0f, D.moss, D.textBright, mono)
                val disk = m?.diskPercent
                Meter("Disk /", disk?.let { "$it%" } ?: "…", m?.diskFraction ?: 0f, if ((disk ?: 0) > 85) D.sunset else D.cyan, if ((disk ?: 0) > 85) D.sunset else D.textBright, mono)
            }
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Txt("SYSTEM", color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(vertical = 8.dp))
            // Живой блок собирается из фактов хоста (ОС / ядро / CPU+load); до первого опроса — «…».
            // В мок-режиме (превью/офскрин) — статичный текст макета.
            val systemText = if (live) liveSystemSummary(liveMetrics) else MOCK_SYSTEM
            Txt(systemText, color = D.dim, size = 10.5.sp, font = mono, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: FontFamily) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Txt(label, color = D.faint, size = 11.5.sp)
        Txt(value, color = D.textBright, size = 11.5.sp, font = mono)
    }
}

@Composable
private fun Meter(label: String, value: String, fraction: Float, bar: Color, valueColor: Color, mono: FontFamily) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Txt(label, color = D.dim, size = 11.sp)
            Txt(value, color = valueColor, size = 11.sp, font = mono)
        }
        MeterBar(fraction, bar)
    }
}

/** Байты → строка гигабайт с одним знаком после запятой (десятичные ГБ, как в free -h). */
private fun gb(bytes: Long): String {
    val rounded = (bytes / 1_000_000_000.0 * 10).roundToInt() / 10.0
    return rounded.toString()
}

/** Статичный SYSTEM-блок для мок/офскрин-режима (нет живой сессии). */
private const val MOCK_SYSTEM = "Ubuntu 22.04.4 LTS\nLinux 5.15.0-105 x86_64\n4 vCPU · load 0.42 0.51 0.48"

/**
 * Собирает SYSTEM-блок info-панели из живых фактов хоста: ОС, ядро, строка «N vCPU · load …».
 * Пропускает поля, которых ещё нет (опрос не дошёл / не-Linux). Пусто всё — «…».
 */
private fun liveSystemSummary(m: HostMetrics?): String {
    val cpuLoad = buildString {
        m?.cpuCount?.let { append("$it vCPU") }
        m?.loadAverage?.let {
            if (isNotEmpty()) append(" · ")
            append("load $it")
        }
    }
    val lines = listOfNotNull(
        m?.osName,
        m?.kernel,
        cpuLoad.takeIf { it.isNotEmpty() },
    )
    return if (lines.isEmpty()) "…" else lines.joinToString("\n")
}

// ──────────────────────────────── AI bar ────────────────────────────────

@Composable
private fun AiBar() {
    val mono = LocalFonts.current.mono
    Column {
        HLine()
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(D.amber.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Sym("auto_awesome", size = 16.sp, color = D.amber)
            }
            Txt("Ask Skerry: 'find files larger than 100MB'   ·   Ctrl / to focus", color = D.dim, size = 13.sp, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiBarTag("lock", "Local · Qwen 2.5", mono)
                AiBarTag("verified_user", "STRICT", mono)
            }
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(D.cyan), contentAlignment = Alignment.Center) {
                Sym("arrow_upward", size = 16.sp, color = Color(0xFF0A1A26))
            }
        }
    }
}

@Composable
private fun AiBarTag(icon: String, text: String, mono: FontFamily) {
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x0AFFFFFF))
            .border(1.dp, D.line, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Sym(icon, size = 12.sp, color = D.faint)
        Txt(text, color = D.faint, size = 10.5.sp, font = mono)
    }
}
