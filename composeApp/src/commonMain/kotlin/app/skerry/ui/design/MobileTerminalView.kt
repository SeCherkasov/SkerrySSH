package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ai.AiPolicyDecision
import app.skerry.shared.ai.CommandRisk
import app.skerry.shared.host.Host
import app.skerry.ui.ai.AiAssistantController
import app.skerry.ui.ai.TerminalAiController
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.secure.SecureScreen
import app.skerry.ui.terminal.TerminalScreen
import app.skerry.ui.terminal.TerminalScreenState

/** Фон клавишной панели терминала (`#0E1A24` из мока). Клавиши — белый 6%, моноширинные. */
private val KeybarBg = Color(0xFF0E1A24)
private val KeyCapBg = Color(0x0FFFFFFFF)
private val KeyCapFg = Color(0xFFC9D6DE)

/** ESC (0x1B) — префикс CSI-последовательностей стрелок и сама клавиша esc. */
private const val ESC = "\u001b"

/**
 * Полноэкранный push-экран терминала мобильного макета `Skerry Mobile.html` (живая SSH-сессия
 * поверх готового PTY-ядра). Шапка с именем хоста и статусом → тело по состоянию соединения активной
 * сессии ([LocalSessions]) → клавишная панель спецклавиш. Тело подключённой сессии рендерит реальную
 * сетку через общий [TerminalScreen] в IME-режиме (как desktop-`LiveTerminalPane`).
 *
 * Сессию открывает Connect на [MobileHostDetailScreen] (через `LocalConnectHost`); back-стрелка лишь
 * возвращает на список (сессия остаётся живой), а Disconnect в меню `more_horiz` рвёт её и закрывает
 * экран. AI-bar/AI-карточки макета спрятаны за [FeatureFlags.ai] (Phase 2). Split-режим на телефоне
 * не нужен (решение пользователя 2026-06-22) — иконка `splitscreen` из шапки убрана.
 */
@Composable
fun MobileTerminalScreen(state: MobileDesignState) {
    val sessions = LocalSessions.current
    val active = sessions?.active
    // Стабильная лямбда Disconnect (пересоздаётся только при смене сессии): рвёт соединение и
    // возвращает на список — back-стрелка сессию оставляет живой, Disconnect её закрывает.
    val onDisconnect = remember(active?.id, sessions) {
        active?.let { s -> { sessions.close(s.id); state.pop() } }
    }
    // Штатный выход shell (`exit`) на телефоне: закрываем сессию и возвращаемся на список хостов —
    // полноэкранному push-терминалу незачем висеть застывшим (в отличие от desktop, где остаётся
    // плашка «Session closed»). Обрыв транспорта сюда не попадает (cleanExit=false) — там экран живёт.
    val cleanlyExited = (active?.controller?.uiState as? ConnectionUiState.Disconnected)?.cleanExit == true
    LaunchedEffect(active?.id, cleanlyExited) {
        if (cleanlyExited) {
            sessions.close(active.id)
            state.pop()
        }
    }
    // sticky-ctrl поднят на уровень экрана, чтобы армирование клавишной панели влияло И на ввод с
    // софт-клавиатуры (IME-путь идёт мимо панели). Сбрасывается при смене сессии.
    var ctrlArmed by remember(active?.id) { mutableStateOf(false) }
    // Колбэки стабилизированы remember'ом (ключ — сессия), иначе свежая лямбда на каждый PTY-чанк
    // перерисовывала бы клавишную панель/терминал зря. `ctrlArmed` — compose-state, поэтому тело
    // лямбды видит его живое значение даже сквозь remember.
    val setCtrlArmed = remember(active?.id) { { v: Boolean -> ctrlArmed = v } }
    val imeTransform = remember(active?.id) {
        { raw: String ->
            // Армированный ctrl применяется к первому символу с софт-клавиатуры и тут же снимается
            // (raw здесь всегда непуст — TerminalScreen зовёт imeTransform только на реальном вводе).
            val out = applyStickyCtrl(ctrlArmed, raw)
            if (ctrlArmed) ctrlArmed = false
            out
        }
    }
    // Палитра запуска сниппета (иконка `bolt` в шапке) живёт на верхнем уровне Box, НЕ внутри шапки —
    // иначе инлайновый лист участвовал бы в раскладке Row и ломал её. Доступна только в коннекте и
    // когда подключена библиотека сниппетов.
    var paletteOpen by remember(active?.id) { mutableStateOf(false) }
    val snippets = LocalSnippets.current
    val activeTerminal = (active?.controller?.uiState as? ConnectionUiState.Connected)?.terminal
    val canRunSnippet = snippets != null && activeTerminal != null

    Box(Modifier.fillMaxSize().background(D.terminalBg)) {
        Column(Modifier.fillMaxSize()) {
            MobileTerminalHeader(
                title = active?.displayTitle ?: "Terminal",
                status = active?.controller?.uiState,
                controller = active?.controller,
                onBack = state::pop,
                onDisconnect = onDisconnect,
                onSnippets = if (canRunSnippet) ({ paletteOpen = true }) else null,
            )
            when (val st = active?.controller?.uiState) {
                null, ConnectionUiState.Form ->
                    MobileTerminalNotice("terminal", "No active session", "Open a host and tap Connect.")
                ConnectionUiState.Connecting ->
                    MobileTerminalNotice("sync", "Connecting…", active.subtitle)
                is ConnectionUiState.Connected -> {
                    // AI-контроллер (или null): общий на транзиент-оверлей и строку ввода; key() пересоздаёт
                    // при смене хоста/политики. Транзиент рисуется поверх низа терминала, чтобы его появление
                    // НЕ ресайзило терминал (иначе reflow-«дёрг» при вставке/выполнении).
                    val liveAi = LocalAi.current
                    val aiPolicy = active?.hostId?.let { LocalHosts.current?.find(it)?.aiPolicy } ?: AiPolicy.Strict
                    val aiController = key(liveAi, aiPolicy) {
                        remember {
                            if (liveAi != null && AiPolicyDecision.of(aiPolicy).aiEnabled) liveAi.terminalController(aiPolicy) else null
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        TerminalScreen(
                            st.terminal,
                            Modifier.fillMaxSize(),
                            imeInput = true,
                            imeTransform = imeTransform,
                        )
                    }
                    // Всегда-присутствующая строка бара — команда/статус внутри неё (нет «дёрга»).
                    if (aiController != null) MobileAiBarInput(aiController, st.terminal)
                    MobileKeybar(st.terminal, ctrlArmed, onCtrlArmedChange = setCtrlArmed)
                }
                is ConnectionUiState.Error ->
                    MobileTerminalNotice("error", "Connection failed", st.message, color = D.sunset)
                // Обрыв: застывший экран на момент потери, без keybar (канал мёртв). Статус в шапке —
                // «disconnected» красным. Детальный мобильный паритет (авто-реконнект) — отдельной задачей.
                is ConnectionUiState.Disconnected ->
                    TerminalScreen(st.terminal, Modifier.weight(1f).fillMaxWidth())
            }
        }
        if (paletteOpen && snippets != null && activeTerminal != null) {
            MobileSnippetRunSheet(
                manager = snippets,
                onRun = { entry -> snippets.run(entry.id) { text -> activeTerminal.send(text) }; paletteOpen = false },
                onDismiss = { paletteOpen = false },
            )
        }
    }
}

/**
 * Единственная форма мобильного AI-бара (паритет desktop) — постоянная высота, терминал не ресайзится,
 * ничего не перекрывается. В одной строке: ввод, «Thinking…», blocked/error, а для предложения — команда
 * + инлайн-пояснение (None: что делает; Warn/Danger: причина риска цветом) + кнопки. Деструктивная —
 * красная со знаком «block». Автозапуска нет: Run = подтверждение; для [CommandRisk.Danger] второй тап.
 */
@Composable
private fun MobileAiBarInput(controller: TerminalAiController, terminal: TerminalScreenState) {
    val mono = LocalFonts.current.mono
    var prompt by remember { mutableStateOf("") }
    val submit = {
        val text = prompt.trim()
        if (text.isNotEmpty()) { controller.ask(text); prompt = "" }
    }
    val pending = controller.pending
    val risk = controller.pendingRisk?.risk ?: CommandRisk.None
    val danger = risk == CommandRisk.Danger
    // Красным — любую деструктивную команду (удаление/перезапись), даже Warn.
    val severe = danger || controller.pendingRisk?.destructive == true
    val accent = if (severe) D.sunset else D.moss
    var armed by remember(pending) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(if (pending != null) accent.copy(alpha = 0.08f) else D.surface2)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym(
                if (pending != null) (if (severe) "block" else "terminal") else "auto_awesome",
                size = 16.sp, color = if (pending != null) accent else D.amber,
            )
            Box(Modifier.weight(1f)) {
                when {
                    pending != null -> {
                        val infoColor = if (severe) D.sunset else if (risk == CommandRisk.Warn) D.amber else D.dim
                        val info = when (risk) {
                            CommandRisk.None -> controller.pendingInfo
                            else -> controller.pendingRisk?.reason
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Txt(pending, color = if (severe) D.sunset else D.text, size = 12.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).alignByBaseline())
                            if (info != null) Txt(info, color = infoColor, size = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).alignByBaseline())
                        }
                    }
                    controller.busy -> Txt("Thinking…", color = D.dim, size = 13.sp)
                    controller.blocked != null -> Txt(controller.blocked!!, color = D.amber, size = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    controller.error != null -> Txt(controller.error!!, color = D.sunset, size = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else -> {
                        if (prompt.isEmpty()) Txt("Ask Skerry…", color = D.dim, size = 13.sp)
                        BasicTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            singleLine = true,
                            textStyle = TextStyle(color = D.text, fontSize = 13.sp),
                            cursorBrush = SolidColor(D.cyan),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submit() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            when {
                pending != null -> {
                    MobileAiChip(when { !danger -> "Run"; !armed -> "Run anyway"; else -> "Confirm" }, accent) {
                        if (danger && !armed) armed = true
                        else controller.confirm()?.let { terminal.send(it + "\r") }
                    }
                    MobileAiChip("Dismiss", D.faint) { controller.dismiss() }
                }
                controller.blocked != null || controller.error != null ->
                    MobileAiChip("Dismiss", D.faint) { controller.dismiss() }
                else -> {
                    Txt(controller.policy.name.uppercase(), color = D.faint, size = 10.sp, font = mono)
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(D.cyan)
                            .clickable(enabled = !controller.busy) { submit() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Sym("arrow_upward", size = 16.sp, color = Color(0xFF0A1A26))
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileAiChip(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Txt(label, color = color, size = 11.5.sp, weight = FontWeight.Medium)
    }
}

/**
 * Шапка терминала по моку (`#0B1A26` + нижняя cyan-линия): back-шеврон, имя хоста + статус-строка с
 * живыми метриками (RTT/throughput), иконка `more_horiz` (меню с Disconnect). [onDisconnect]==null —
 * нет активной сессии, пункт Disconnect скрыт. Split-иконка макета на телефоне убрана (split не нужен).
 *
 * Метрики берутся из [controller] теми же поллерами, что desktop-статусбар: RTT-пинг ([openPing]) и
 * скорость канала ([openThroughput]). remember безусловный — ключи (controller + флаг connected)
 * пересоздают его при смене сессии/подключения; оба метода идемпотентны (кэш в контроллере). До
 * первого замера/вне коннекта метрика — «—»; узкая строка уезжает за край горизонтальным скроллом.
 */
@Composable
private fun MobileTerminalHeader(
    title: String,
    status: ConnectionUiState?,
    controller: ConnectionController?,
    onBack: () -> Unit,
    onDisconnect: (() -> Unit)?,
    onSnippets: (() -> Unit)?,
) {
    val mono = LocalFonts.current.mono
    var menuOpen by remember { mutableStateOf(false) }
    val connected = status is ConnectionUiState.Connected
    val throughput = remember(controller, connected) {
        if (connected && controller != null) controller.openThroughput() else null
    }
    val ping = remember(controller, connected) {
        if (connected && controller != null) controller.openPing() else null
    }
    Column(Modifier.fillMaxWidth().background(D.surface2)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Sym(
                "chevron_left",
                size = 24.sp,
                color = D.cyanBright,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBack,
                ),
            )
            Column(Modifier.weight(1f)) {
                Txt(title, color = D.text, size = 14.sp, weight = FontWeight.SemiBold, font = mono)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Dot(sessionDotColor(status))
                        Txt(mobileTerminalStatusText(status), color = sessionDotColor(status), size = 10.5.sp)
                    }
                    // Живые метрики активной сессии (паритет desktop-статусбара) — только в коннекте.
                    if (connected) {
                        MobileTerminalMetric("network_ping", mobileRttLabel(ping?.rttMs), mono)
                        MobileTerminalMetric("arrow_upward", mobileRateLabel(throughput?.upRate), mono)
                        MobileTerminalMetric("arrow_downward", mobileRateLabel(throughput?.downRate), mono)
                    }
                }
            }
            if (onSnippets != null) {
                Sym(
                    "bolt",
                    size = 21.sp,
                    color = D.dim,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSnippets,
                    ),
                )
            }
            Sym(
                "more_horiz",
                size = 21.sp,
                color = D.dim,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { menuOpen = !menuOpen },
                ),
            )
            if (menuOpen && onDisconnect != null) {
                MobileActionSheet(
                    title = title,
                    actions = listOf(
                        MobileSheetAction("Disconnect", onClick = onDisconnect, icon = "power_settings_new", danger = true),
                    ),
                    onDismiss = { menuOpen = false },
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(D.cyan08))
    }
}

/** Одна метрика статус-строки шапки: иконка + моноширинное значение (RTT/throughput). */
@Composable
private fun MobileTerminalMetric(icon: String, text: String, mono: FontFamily) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Sym(icon, size = 11.sp, color = D.faint)
        Txt(text, color = D.faint, size = 10.5.sp, font = mono)
    }
}

/** Центрированное сообщение на фоне терминала (нет сессии / подключение / ошибка). */
@Composable
private fun MobileTerminalNotice(icon: String, title: String, subtitle: String, color: Color = D.dim) {
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

/**
 * Клавишная панель спецклавиш (`#0E1A24`, горизонтальный скролл) — сердце мобильного SSH-UX из мока:
 * esc, tab, ctrl (sticky-модификатор), /, |, -, ~, стрелки. Управляющие последовательности уходят в
 * PTY через [TerminalScreenState.send]. `ctrl` армируется тапом (подсветка cyan): [ctrlArmed] поднят
 * в [MobileTerminalScreen], поэтому применяется и к символьным клавишам панели ([controlByte]), и к
 * вводу с софт-клавиатуры ([applyStickyCtrl] в IME-пути). Стрелки кодируются с учётом DECCKM-режима
 * сессии ([arrowSequence]): CSI в норме, SS3 в application-cursor (vim/less).
 */
@Composable
private fun MobileKeybar(
    terminal: TerminalScreenState,
    ctrlArmed: Boolean,
    onCtrlArmedChange: (Boolean) -> Unit,
) {
    val plain = { seq: String -> terminal.send(seq); onCtrlArmedChange(false) }
    val char = { c: String ->
        if (ctrlArmed && c.length == 1) {
            terminal.send(controlByte(c[0]))
            onCtrlArmedChange(false)
        } else {
            terminal.send(c)
        }
    }
    val arrow = { key: ArrowKey -> plain(arrowSequence(key, terminal.applicationCursorKeys)) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(KeybarBg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyCap("esc") { plain(ESC) }
        // Tab при наличии подсказки автодополнения — принять её; иначе обычный таб в PTY.
        KeyCap("tab") {
            if (terminal.suggestionTail != null) { terminal.acceptSuggestion(); onCtrlArmedChange(false) } else plain("\t")
        }
        // ctrl — спец-клавиша макета (всегда cyan); армирование заливает её сплошным cyan.
        KeyCap("ctrl", accent = true, active = ctrlArmed) { onCtrlArmedChange(!ctrlArmed) }
        KeyCap("/") { char("/") }
        KeyCap("|") { char("|") }
        KeyCap("-") { char("-") }
        KeyCap("~") { char("~") }
        KeyCapIcon("keyboard_arrow_up") { arrow(ArrowKey.Up) }
        KeyCapIcon("keyboard_arrow_down") { arrow(ArrowKey.Down) }
        KeyCapIcon("keyboard_arrow_left") { arrow(ArrowKey.Left) }
        KeyCapIcon("keyboard_arrow_right") { arrow(ArrowKey.Right) }
    }
}

/**
 * Текстовая клавиша панели. [accent] — спец-клавиша макета (бирюзовый покой, как `ctrl`); [active] —
 * sticky-армирование (залитая бирюза + тёмный текст).
 */
@Composable
private fun KeyCap(label: String, accent: Boolean = false, active: Boolean = false, onClick: () -> Unit) {
    val bg = when {
        active -> D.cyan
        accent -> D.cyan14
        else -> KeyCapBg
    }
    val fg = when {
        active -> Color(0xFF0A1A26)
        accent -> D.cyanBright
        else -> KeyCapFg
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Txt(label, color = fg, size = 12.5.sp, font = LocalFonts.current.mono)
    }
}

/** Иконочная клавиша панели (стрелки). */
@Composable
private fun KeyCapIcon(icon: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(KeyCapBg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Sym(icon, size = 16.sp, color = KeyCapFg)
    }
}

/**
 * Нижний лист запроса пароля при Connect к хосту без привязанной identity (в стиле листа
 * `New connection`). Пароль уходит в [onConnect] как строку и тут же используется в `SshAuth.Password`;
 * буфер живёт только в этом composable. Тап мимо панели — [onDismiss].
 */
@Composable
fun MobilePasswordSheet(host: Host, onDismiss: () -> Unit, onConnect: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    val submit = { if (password.isNotEmpty()) onConnect(password) }
    // Защита ввода SSH-пароля при коннекте от снимков экрана/превью в Recent Apps (Android; desktop — no-op).
    SecureScreen()
    MobileBottomSheet(
        onDismiss = onDismiss,
        panelModifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 30.dp),
    ) {
        Txt(host.label, color = D.text, size = 20.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Txt("${host.username}@${host.address}:${host.port}", color = D.dim, size = 12.5.sp, font = LocalFonts.current.mono)
            Spacer(Modifier.height(18.dp))
            Txt("PASSWORD", color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(D.bg)
                    .border(1.dp, D.cyan14, RoundedCornerShape(11.dp))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (password.isEmpty()) Txt("••••••••", color = D.faint, size = 15.sp)
                BasicTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    textStyle = TextStyle(color = D.text, fontSize = 15.sp, fontFamily = LocalFonts.current.ui),
                    cursorBrush = SolidColor(D.cyan),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }, onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (password.isNotEmpty()) D.cyan else D.cyan.copy(alpha = 0.4f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = submit)
                    .padding(15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt("Connect", color = Color(0xFF0A1A26), size = 16.sp, weight = FontWeight.Bold)
            }
        }
}
