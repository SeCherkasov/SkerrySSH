package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.snippet.Snippet
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.snippet.SnippetDraft
import app.skerry.ui.snippet.SnippetEntry
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.snippet.matches
import app.skerry.ui.snippet.snippetTagSuggestions
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_add_tag
import app.skerry.ui.generated.resources.lib_snippets_delete
import app.skerry.ui.generated.resources.lib_snippets_edit
import app.skerry.ui.generated.resources.lib_snippets_empty_mobile
import app.skerry.ui.generated.resources.lib_snippets_field_command
import app.skerry.ui.generated.resources.lib_snippets_field_name
import app.skerry.ui.generated.resources.lib_snippets_field_tags
import app.skerry.ui.generated.resources.lib_snippets_new
import app.skerry.ui.generated.resources.lib_snippets_no_matches
import app.skerry.ui.generated.resources.lib_snippets_ph_name
import app.skerry.ui.generated.resources.lib_snippets_run_empty
import app.skerry.ui.generated.resources.lib_snippets_run_in_terminal
import app.skerry.ui.generated.resources.lib_snippets_run_title
import app.skerry.ui.generated.resources.lib_snippets_save_snippet
import app.skerry.ui.generated.resources.lib_snippets_screen_title
import app.skerry.ui.generated.resources.lib_snippets_search
import app.skerry.ui.generated.resources.lib_snippets_untitled
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.D
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt

private data class MockMobileSnippet(val icon: String, val title: String, val cmd: String)

private val MOCK_MOBILE_SNIPPETS = listOf(
    MockMobileSnippet("monitoring", "Disk usage report", "df -h | sort -k5 -r | head"),
    MockMobileSnippet("memory", "Top memory procs", "ps aux --sort=-%mem | head"),
    MockMobileSnippet("restart_alt", "Restart nginx", "sudo systemctl reload nginx"),
    MockMobileSnippet("cleaning_services", "Clear docker cache", "docker system prune -af"),
)

/**
 * Корневой таб Snippets: библиотека сохранённых команд + FAB добавления. Тап по карточке открывает
 * лист-редактор (Name/Command/Tags с type-ahead, Run/Save/Delete). Сниппет самостоятелен
 * (plain-конфиг, секретов не содержит).
 *
 * Живой путь ([LocalSnippets] != null, за гейтом vault) — реальная библиотека из [SnippetManager];
 * превью/офскрин без менеджера — статичные карточки-заглушки.
 */
@Composable
fun MobileSnippetsScreen(state: MobileDesignState) {
    when (val manager = LocalSnippets.current) {
        null -> MobileSnippetsMock()
        else -> MobileSnippetsLive(state, manager)
    }
}

@Composable
private fun MobileSnippetsLive(state: MobileDesignState, manager: SnippetManager) {
    val mono = LocalFonts.current.mono
    val sessions = LocalSessions.current
    // Запуск сниппета бьёт в активную подключённую сессию; нет живого терминала — кнопки Run в редакторе нет.
    val activeTerminal = (sessions?.active?.controller?.uiState as? ConnectionUiState.Connected)?.terminal

    var editing by remember { mutableStateOf<SnippetEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    val sheetOpen = adding || editing != null

    // Открытый лист-редактор прячет таб-бар (иначе он плавает над полями ввода у клавиатуры).
    LaunchedEffect(sheetOpen) { state.modalOverlay(sheetOpen) }
    DisposableEffect(Unit) { onDispose { state.modalOverlay(false) } }

    val snippets = manager.snippets

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(D.bg).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 10.dp)) {
                Txt(stringResource(Res.string.lib_snippets_screen_title), color = D.text, size = 28.sp, weight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            }
            if (snippets.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 30.dp)) {
                    Txt(stringResource(Res.string.lib_snippets_empty_mobile), color = D.faint, size = 13.sp)
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    snippets.forEach { entry ->
                        key(entry.id) {
                            val onClick = remember(entry.id) { { editing = entry; adding = false } }
                            MobileSnippetCard(entry.snippet, mono, onClick)
                        }
                    }
                }
            }
            Spacer(Modifier.height(96.dp))
        }

        // FAB добавления (правый-нижний, над таб-баром). Скрыт, пока открыт лист-редактор.
        if (!sheetOpen) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 22.dp, bottom = 104.dp)
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(D.cyan)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { adding = true; editing = null },
                contentAlignment = Alignment.Center,
            ) {
                Sym("add", size = 28.sp, color = Color(0xFF0A1A26))
            }
        }

        if (sheetOpen) {
            val target = editing
            MobileSnippetEditSheet(
                entry = target,
                allSnippets = snippets.map { it.snippet },
                mono = mono,
                canRun = target != null && activeTerminal != null,
                onDismiss = { adding = false; editing = null },
                onSave = { draft ->
                    manager.save(draft)
                    adding = false; editing = null
                },
                onDelete = target?.let { e -> { manager.delete(e.id); adding = false; editing = null } },
                onRun = run@{
                    val e = target ?: return@run
                    manager.run(e.id) { text -> activeTerminal?.send(text) }
                    adding = false; editing = null
                    sessions?.active?.let { state.push(MobileRoute.Terminal) }
                },
            )
        }
    }
}

@Composable
private fun MobileSnippetCard(snippet: Snippet, mono: FontFamily, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0x08FFFFFF))
            .border(1.dp, D.cyan08, RoundedCornerShape(13.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Sym("code_blocks", size = 18.sp, color = D.cyanBright)
            Txt(snippet.label.ifBlank { stringResource(Res.string.lib_snippets_untitled) }, color = D.text, size = 14.5.sp, weight = FontWeight.SemiBold)
        }
        if (snippet.command.isNotBlank()) {
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(D.terminalBg).padding(horizontal = 11.dp, vertical = 9.dp),
            ) {
                Txt(snippet.command, color = D.dim, size = 11.5.sp, font = mono)
            }
        }
        if (snippet.tags.isNotEmpty()) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                snippet.tags.forEach { tag -> key(tag) { SnippetTagChip(tag) } }
            }
        }
    }
}

@Composable
private fun SnippetTagChip(tag: String) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(D.cyan.copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 2.dp),
    ) {
        Txt("#$tag", color = D.cyanBright, size = 11.sp)
    }
}

/**
 * Палитра запуска сниппета из шапки терминала (иконка `bolt`): список сохранённых команд, тап
 * запускает выбранный сниппет в активной сессии через [onRun].
 */
@Composable
internal fun MobileSnippetRunSheet(manager: SnippetManager, onRun: (SnippetEntry) -> Unit, onDismiss: () -> Unit) {
    val mono = LocalFonts.current.mono
    var query by remember { mutableStateOf("") }
    val all = manager.snippets
    val filtered = if (query.isBlank()) all else all.filter { it.matches(query) }
    // Инлайновый лист (как листы Vault/New connection) — рендерится на верхнем уровне Box экрана, НЕ
    // через Popup: focusable-Popup менял инсеты окна и слегка сдвигал шапку терминала.
    MobileBottomSheet(onDismiss = onDismiss, panelModifier = Modifier.imePadding(), maxHeightFraction = 0.7f) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(stringResource(Res.string.lib_snippets_run_title), color = D.text, size = 18.sp, weight = FontWeight.Bold)
            SnippetSheetInput(query, { query = it }, stringResource(Res.string.lib_snippets_search))
            if (filtered.isEmpty()) {
                Txt(if (all.isEmpty()) stringResource(Res.string.lib_snippets_run_empty) else stringResource(Res.string.lib_snippets_no_matches), color = D.faint, size = 13.sp)
            } else {
                filtered.forEach { entry ->
                    key(entry.id) {
                        val onClick = remember(entry.id) { { onRun(entry) } }
                        MobileSnippetCard(entry.snippet, mono, onClick)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

// --- Лист-редактор ---

/**
 * Лист создания/правки сниппета. [entry] == null — создание.
 * [canRun]/[onRun] есть только когда правится существующий сниппет и есть живой терминал.
 */
@Composable
private fun MobileSnippetEditSheet(
    entry: SnippetEntry?,
    allSnippets: List<Snippet>,
    mono: FontFamily,
    canRun: Boolean,
    onDismiss: () -> Unit,
    onSave: (SnippetDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onRun: () -> Unit,
) {
    var label by remember { mutableStateOf(entry?.snippet?.label ?: "") }
    var command by remember { mutableStateOf(entry?.snippet?.command ?: "") }
    var tags by remember { mutableStateOf(entry?.snippet?.tags ?: emptyList()) }
    var tagDraft by remember { mutableStateOf("") }
    val canSave = label.isNotBlank() && command.isNotBlank()

    fun addTags(raw: String) {
        tags = (tags + parseMobileSnippetTags(raw)).distinct()
        tagDraft = ""
    }

    MobileBottomSheet(onDismiss = onDismiss, panelModifier = Modifier.imePadding(), maxHeightFraction = 0.9f) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Txt(if (entry == null) stringResource(Res.string.lib_snippets_new) else stringResource(Res.string.lib_snippets_edit), color = D.text, size = 18.sp, weight = FontWeight.Bold)
            SnippetSheetField(stringResource(Res.string.lib_snippets_field_name)) {
                SnippetSheetInput(label, { label = it }, stringResource(Res.string.lib_snippets_ph_name))
            }
            SnippetSheetField(stringResource(Res.string.lib_snippets_field_command)) {
                SnippetSheetInput(command, { command = it }, "df -h | sort -k5 -r", mono = true, singleLine = false, minHeightDp = 88)
            }
            SnippetSheetField(stringResource(Res.string.lib_snippets_field_tags)) {
                SnippetSheetTags(
                    tags = tags,
                    onRemove = { tag -> tags = tags - tag },
                    draft = tagDraft,
                    onDraftChange = { v -> if (v.contains(',')) addTags(v) else tagDraft = v },
                    onCommit = { addTags(tagDraft) },
                    allSnippets = allSnippets,
                    selfId = entry?.id,
                    onPick = { tag -> tags = (tags + tag).distinct(); tagDraft = "" },
                )
            }
            if (canRun) {
                MobileSheetButton(stringResource(Res.string.lib_snippets_run_in_terminal), onClick = onRun, icon = "bolt", filled = false, modifier = Modifier.fillMaxWidth())
            }
            MobileSheetButton(
                stringResource(Res.string.lib_snippets_save_snippet),
                onClick = {
                    if (canSave) {
                        // Дослать недозафиксированный черновик тега (юзер набрал тег и сразу жмёт Save,
                        // не нажав Enter/запятую) — иначе тег терялся бы.
                        val finalTags = (tags + parseMobileSnippetTags(tagDraft)).distinct()
                        onSave(SnippetDraft(id = entry?.id, label = label.trim(), command = command, tags = finalTags, shortcut = entry?.snippet?.shortcut))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (onDelete != null) {
                MobileSheetButton(stringResource(Res.string.lib_snippets_delete), onClick = onDelete, filled = false, danger = true, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** Парсинг строки тегов (запятая/пробел/перевод строки), снимаем ведущий `#` — как desktop. */
private fun parseMobileSnippetTags(text: String): List<String> =
    text.split(',', ' ', '\n', '\t')
        .map { it.trim().removePrefix("#") }
        .filter { it.isNotEmpty() }
        .distinct()

@Composable
private fun SnippetSheetField(label: String, content: @Composable () -> Unit) {
    Column {
        Txt(label.uppercase(), color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
private fun SnippetSheetInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    mono: Boolean = false,
    singleLine: Boolean = true,
    minHeightDp: Int? = null,
) {
    val fonts = LocalFonts.current
    val family = if (mono) fonts.mono else fonts.ui
    val fontSize = if (mono) 12.5.sp else 15.sp
    val textStyle = remember(family, fontSize) { TextStyle(color = D.text, fontSize = fontSize, fontFamily = family, lineHeight = if (mono) 17.sp else 20.sp) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = textStyle,
        cursorBrush = SolidColor(D.cyan),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (minHeightDp != null) Modifier.heightIn(min = minHeightDp.dp) else Modifier)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (mono) D.terminalBg else D.bg)
                    .border(1.dp, D.cyan14, RoundedCornerShape(11.dp))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (value.isEmpty()) Txt(placeholder, color = D.faint, size = fontSize, font = if (mono) fonts.mono else null)
                inner()
            }
        },
    )
}

/**
 * Редактор тегов листа с type-ahead: пилюли `#tag` + инлайн-ввод; при фокусе под полем раскрывается
 * список подсказок [snippetTagSuggestions] (теги других сниппетов, ещё не добавленные). Меню через
 * [AnchoredDropdown] с `focusable = false`, чтобы не отнимать фокус у поля.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnippetSheetTags(
    tags: List<String>,
    onRemove: (String) -> Unit,
    draft: String,
    onDraftChange: (String) -> Unit,
    onCommit: () -> Unit,
    allSnippets: List<Snippet>,
    selfId: String?,
    onPick: (String) -> Unit,
) {
    val fonts = LocalFonts.current
    val textStyle = remember(fonts.ui) { TextStyle(color = D.text, fontSize = 14.sp, fontFamily = fonts.ui) }
    var focused by remember { mutableStateOf(false) }
    // Свои теги исключаем через selected; подсказки берём из всех сниппетов (включая правимый —
    // его собственные теги уже в selected, так что не предложатся повторно).
    val others = remember(allSnippets, selfId) { allSnippets.filter { it.id != selfId } }
    val suggestions = remember(others, tags, draft) { snippetTagSuggestions(others, tags, draft) }
    AnchoredDropdown(
        expanded = focused && suggestions.isNotEmpty(),
        onDismiss = { focused = false },
        focusable = false,
        trigger = {
            FlowRow(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(11.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                tags.forEach { tag ->
                    key(tag) {
                        Row(
                            Modifier.clip(RoundedCornerShape(20.dp)).background(D.cyan.copy(alpha = 0.12f)).padding(start = 10.dp, end = 5.dp, top = 3.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Txt("#$tag", color = D.cyanBright, size = 12.5.sp)
                            Box(
                                Modifier.clip(CircleShape).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onRemove(tag) }.padding(2.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Sym("close", size = 14.sp, color = D.cyanBright)
                            }
                        }
                    }
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    singleLine = true,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(D.cyan),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCommit() }),
                    modifier = Modifier.widthIn(min = 90.dp).onFocusChanged { focused = it.isFocused },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (draft.isEmpty()) Txt(stringResource(Res.string.lib_snippets_add_tag), color = D.faint, size = 14.sp)
                            inner()
                        }
                    },
                )
            }
        },
        menu = { width ->
            Column(
                Modifier
                    .width(width)
                    .clip(RoundedCornerShape(11.dp))
                    .background(D.surface2)
                    .border(1.dp, D.cyan14, RoundedCornerShape(11.dp))
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                suggestions.forEach { tag ->
                    key(tag) {
                        Box(
                            Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPick(tag) }.padding(horizontal = 14.dp, vertical = 11.dp),
                        ) {
                            Txt("#$tag", color = D.cyanBright, size = 14.sp)
                        }
                    }
                }
            }
        },
    )
}

// --- Статичный мок (превью/офскрин без менеджера) ---

@Composable
private fun MobileSnippetsMock() {
    val mono = LocalFonts.current.mono
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(D.bg).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 10.dp)) {
                Txt(stringResource(Res.string.lib_snippets_screen_title), color = D.text, size = 28.sp, weight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MOCK_MOBILE_SNIPPETS.forEach { s ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Color(0x08FFFFFF)).border(1.dp, D.cyan08, RoundedCornerShape(13.dp)).padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Sym(s.icon, size = 18.sp, color = D.cyanBright)
                            Txt(s.title, color = D.text, size = 14.5.sp, weight = FontWeight.SemiBold)
                        }
                        Box(
                            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(D.terminalBg).padding(horizontal = 11.dp, vertical = 9.dp),
                        ) {
                            Txt(s.cmd, color = D.dim, size = 11.5.sp, font = mono)
                        }
                    }
                }
            }
            Spacer(Modifier.height(96.dp))
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 104.dp).size(56.dp).clip(RoundedCornerShape(18.dp)).background(D.cyan),
            contentAlignment = Alignment.Center,
        ) {
            Sym("add", size = 28.sp, color = Color(0xFF0A1A26))
        }
    }
}
