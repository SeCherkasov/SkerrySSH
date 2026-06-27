package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.snippet.SnippetDraft
import app.skerry.ui.snippet.SnippetEntry
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.snippet.SnippetShortcut

private data class MockSnippet(val icon: String, val title: String, val cmd: String, val selected: Boolean = false)

private val MOCK_SNIPPETS = listOf(
    MockSnippet("monitoring", "Disk usage report", "df -h | sort -k5 -r", selected = true),
    MockSnippet("memory", "Top memory procs", "ps aux --sort=-%mem | head"),
    MockSnippet("restart_alt", "Restart nginx", "sudo systemctl reload nginx"),
    MockSnippet("cleaning_services", "Clear docker cache", "docker system prune -af"),
)

/**
 * Snippets — ГЛОБАЛЬНЫЙ раздел (модель Termius): библиотека сохранённых команд (sidebar) + редактор
 * (main). Сниппет самостоятелен (plain-конфиг, секретов не содержит); редактор — чистая форма, а
 * запуск делается из палитры терминала, хоткеем или пунктом «Run snippet…» в контекстном меню хоста.
 * Когда менеджер подан ([LocalSnippets]) — живой список; без него (офскрин-рендер/превью) показывается
 * статичный мок ([MOCK_SNIPPETS]).
 */
@Composable
fun SnippetsView(state: DesktopDesignState) {
    val mono = LocalFonts.current.mono
    val manager = LocalSnippets.current
    if (manager == null) {
        MockSnippetsView(mono)
        return
    }
    LiveSnippetsView(manager, mono)
}

// Живой путь: библиотека сниппетов + редактор справа.

@Composable
private fun LiveSnippetsView(manager: SnippetManager, mono: FontFamily) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val all = manager.snippets
    val filtered = if (query.isBlank()) all else all.filter { it.matches(query) }
    // Выбранный сниппет: явный selectedId, иначе первый в списке (если не в режиме создания).
    val selected = if (adding) null else (manager.find(selectedId) ?: all.firstOrNull())

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(262.dp).fillMaxHeight().background(D.surface2)) {
            Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
                SnipSearchField(query, { query = it }, mono)
            }
            HLine()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 8.dp)) {
                Txt("LIBRARY", color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 4.dp))
                if (filtered.isEmpty()) {
                    Txt(
                        if (all.isEmpty()) "No snippets yet" else "No matches",
                        color = D.faint, size = 11.5.sp, font = mono,
                        modifier = Modifier.padding(start = 10.dp, top = 6.dp),
                    )
                } else {
                    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        filtered.forEach { entry ->
                            // Стабильный onClick на id: иначе клик по любой строке (меняет selectedId
                            // → рекомпозиция списка) пересоздавал бы лямбды у всех строк и перерисовывал их.
                            val onClick = remember(entry.id) { { selectedId = entry.id; adding = false } }
                            LiveSnippetRow(
                                entry = entry,
                                selected = !adding && entry.id == selected?.id,
                                mono = mono,
                                onClick = onClick,
                            )
                        }
                    }
                }
            }
            HLine()
            Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                PrimaryButton("New snippet", onClick = { adding = true; selectedId = null }, icon = "add", modifier = Modifier.fillMaxWidth())
            }
        }
        VLine(D.line)
        Box(Modifier.weight(1f).fillMaxHeight().background(D.bg)) {
            if (!adding && selected == null) {
                EmptyEditorHint()
            } else {
                // key по идентичности правимого сниппета: при смене выбора/входе в «создать» поля
                // редактора пересоздаются с нуля, а не тянут значения предыдущего.
                key(selected?.id, adding) {
                    SnippetEditor(
                        entry = selected,
                        manager = manager,
                        mono = mono,
                        onSaved = { id -> selectedId = id; adding = false },
                        onDeleted = { selectedId = null; adding = false },
                    )
                }
            }
        }
    }
}

internal fun SnippetEntry.matches(query: String): Boolean {
    val q = query.trim().lowercase()
    return snippet.label.lowercase().contains(q) ||
        snippet.command.lowercase().contains(q) ||
        snippet.tags.any { it.lowercase().contains(q) }
}

@Composable
private fun LiveSnippetRow(entry: SnippetEntry, selected: Boolean, mono: FontFamily, onClick: () -> Unit) {
    val s = entry.snippet
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) D.cyan10 else Color.Transparent)
            .border(1.dp, if (selected) D.cyan.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Sym("code_blocks", size = 15.sp, color = if (selected) D.cyanBright else D.dim)
            Txt(s.label.ifBlank { "Untitled" }, color = if (selected) D.cyanBright else D.textBright, size = 12.5.sp, weight = FontWeight.Medium)
        }
        Txt(s.command, color = if (selected) D.dim else D.faint, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SnippetEditor(
    entry: SnippetEntry?,
    manager: SnippetManager,
    mono: FontFamily,
    onSaved: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    var label by remember { mutableStateOf(entry?.snippet?.label ?: "") }
    var command by remember { mutableStateOf(entry?.snippet?.command ?: "") }
    var tags by remember { mutableStateOf(entry?.snippet?.tags ?: emptyList()) }
    var tagDraft by remember { mutableStateOf("") }
    var shortcut by remember { mutableStateOf(entry?.snippet?.shortcut) }

    val canSave = label.isNotBlank() && command.isNotBlank()

    fun addTags(raw: String) {
        tags = (tags + parseSnippetTags(raw)).distinct()
        tagDraft = ""
    }

    // Редактор — чистая форма: запуск делается из палитры терминала (активная сессия), хоткеем или
    // из контекстного меню хоста («Run snippet…»), а не отсюда.
    fun persist(): String = manager.save(
        SnippetDraft(
            id = entry?.id,
            label = label.trim(),
            command = command,
            tags = tags,
            shortcut = shortcut,
        ),
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Sym("code_blocks", size = 20.sp, color = D.cyanBright)
                Txt(label.ifBlank { "New snippet" }, color = D.text, size = 17.sp, weight = FontWeight.SemiBold)
            }
            if (tags.isNotEmpty()) {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { Chip("#$it") }
                }
            }
        }
        HLine()
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            FieldLabelSnip("Name")
            SnipEditField(label, { label = it }, "Disk usage report", mono)
            Column(Modifier.padding(top = 20.dp)) {
                FieldLabelSnip("Command")
                SnipCommandField(command, { command = it }, "df -h | sort -k5 -r", mono)
            }
            Column(Modifier.padding(top = 20.dp)) {
                FieldLabelSnip("Tags")
                SnipTagsField(
                    tags = tags,
                    draft = tagDraft,
                    onDraftChange = { v -> if (v.contains(',')) addTags(v) else tagDraft = v },
                    onCommit = { addTags(tagDraft) },
                    onRemove = { tag -> tags = tags - tag },
                )
            }
            Column(Modifier.padding(top = 20.dp).width(220.dp)) {
                FieldLabelSnip("Shortcut")
                ShortcutField(shortcut, mono) { shortcut = it }
            }
            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    "Save",
                    onClick = { if (canSave) onSaved(persist()) },
                    enabled = canSave,
                    bg = if (canSave) D.cyan else D.cyan.copy(alpha = 0.3f),
                )
                if (entry != null) {
                    GhostButton("Delete", onClick = { manager.delete(entry.id); onDeleted() }, fg = D.storm, border = D.storm.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun EmptyEditorHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Txt("Select a snippet or create a new one.", color = D.faint, size = 13.sp)
    }
}

/** Парсинг строки тегов в список: разделители — запятая/пробел/перевод строки, ведущий `#` снимаем. */
private fun parseSnippetTags(text: String): List<String> =
    text.split(',', ' ', '\n', '\t')
        .map { it.trim().removePrefix("#") }
        .filter { it.isNotEmpty() }
        .distinct()

// --- Теги чипсами (как в New connection) ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnipTagsField(
    tags: List<String>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onCommit: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val mono = LocalFonts.current.mono
    FlowRow(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tags.forEach { tag -> key(tag) { SnipTagPill(tag) { onRemove(tag) } } }
        val textStyle = remember(mono) { TextStyle(color = D.text, fontSize = 12.5.sp, fontFamily = mono) }
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(D.cyan),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit() }),
            modifier = Modifier.widthIn(min = 72.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (draft.isEmpty()) Txt("add tag…", color = D.faint, size = 12.5.sp, font = mono)
                    inner()
                }
            },
        )
    }
}

@Composable
private fun SnipTagPill(tag: String, onRemove: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(D.cyan.copy(alpha = 0.12f)).padding(start = 9.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Txt("#$tag", color = D.cyanBright, size = 11.sp)
        Box(Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(2.dp), contentAlignment = Alignment.Center) {
            Sym("close", size = 12.sp, color = D.cyanBright)
        }
    }
}

// --- Захват горячей клавиши сниппета ---

@Composable
private fun ShortcutField(value: String?, mono: FontFamily, onCapture: (String?) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(D.bg)
            .border(1.dp, if (focused) D.cyan else D.cyan14, RoundedCornerShape(7.dp))
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    // Очистка хоткея: Esc/Backspace/Delete снимают назначение.
                    Key.Escape, Key.Backspace, Key.Delete -> { onCapture(null); true }
                    else -> {
                        val s = SnippetShortcut.format(
                            event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.isMetaPressed, event.key,
                        )
                        if (s != null) { onCapture(s); true } else false
                    }
                }
            }
            .clickable { requester.requestFocus() }
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        val text = value ?: if (focused) "Press keys…" else "—"
        Txt(text, color = if (value != null) D.text else D.faint, size = 13.sp, font = mono)
    }
}

// --- Статичный мок (офскрин-рендер/превью без менеджера) ---

@Composable
private fun MockSnippetsView(mono: FontFamily) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(262.dp).fillMaxHeight().background(D.surface2)) {
            Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Color(0x08FFFFFF)).border(1.dp, D.line, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Sym("search", size = 16.sp, color = D.faint)
                    Txt("Search snippets…", color = D.faint, size = 12.5.sp)
                }
            }
            HLine()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 8.dp)) {
                Txt("LIBRARY", color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 4.dp))
                Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MOCK_SNIPPETS.forEach { MockSnippetRow(it, mono) }
                }
            }
            HLine()
            Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                PrimaryButton("New snippet", onClick = {}, icon = "add", modifier = Modifier.fillMaxWidth())
            }
        }
        VLine(D.line)
        Column(Modifier.weight(1f).fillMaxHeight().background(D.bg).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Sym("monitoring", size = 20.sp, color = D.cyanBright)
                    Txt("Disk usage report", color = D.text, size = 17.sp, weight = FontWeight.SemiBold)
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("#monitoring")
                    Chip("#disk")
                    Chip("+ tag")
                }
            }
            HLine()
            Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                FieldLabelSnip("Command")
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(D.terminalBg).border(1.dp, D.cyan14, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Txt("df", color = D.moss, size = 13.sp, font = mono)
                    Txt(" -h | ", color = D.textBright, size = 13.sp, font = mono)
                    Txt("sort", color = D.moss, size = 13.sp, font = mono)
                    Txt(" -k5 -r | ", color = D.textBright, size = 13.sp, font = mono)
                    Txt("head", color = D.moss, size = 13.sp, font = mono)
                    Txt(" -n 10", color = D.textBright, size = 13.sp, font = mono)
                }
                Column(Modifier.padding(top = 20.dp).width(220.dp)) {
                    FieldLabelSnip("Shortcut")
                    SnipInput("⌘⇧D", mono)
                }
                Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Save", onClick = {})
                }
            }
        }
    }
}

@Composable
private fun MockSnippetRow(snippet: MockSnippet, mono: FontFamily) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(if (snippet.selected) D.cyan10 else Color.Transparent)
            .border(1.dp, if (snippet.selected) D.cyan.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(7.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Sym(snippet.icon, size = 15.sp, color = if (snippet.selected) D.cyanBright else D.dim)
            Txt(snippet.title, color = if (snippet.selected) D.cyanBright else D.textBright, size = 12.5.sp, weight = FontWeight.Medium)
        }
        Txt(snippet.cmd, color = if (snippet.selected) D.dim else D.faint, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 4.dp))
    }
}

// --- Поля редактора ---

@Composable
private fun FieldLabelSnip(text: String) {
    Txt(text.uppercase(), color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(bottom = 8.dp))
}

/** Однострочное редактируемое поле (стиль [SnipInput] + плейсхолдер + ввод). */
@Composable
private fun SnipEditField(value: String, onValueChange: (String) -> Unit, placeholder: String, mono: FontFamily) {
    val textStyle = remember(mono) { TextStyle(color = D.text, fontSize = 13.sp, fontFamily = mono) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(D.cyan),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 11.dp, vertical = 9.dp)) {
                if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 13.sp, font = mono)
                inner()
            }
        },
    )
}

/** Многострочное поле команды (моноширинное, тёмный фон терминала как в макете). */
@Composable
private fun SnipCommandField(value: String, onValueChange: (String) -> Unit, placeholder: String, mono: FontFamily) {
    val textStyle = remember(mono) { TextStyle(color = D.textBright, fontSize = 13.sp, fontFamily = mono) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = textStyle,
        cursorBrush = SolidColor(D.cyan),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp)).background(D.terminalBg).border(1.dp, D.cyan14, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 14.dp)) {
                if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 13.sp, font = mono)
                inner()
            }
        },
    )
}

/** Поле поиска по библиотеке (стиль строки поиска макета + ввод). */
@Composable
private fun SnipSearchField(value: String, onValueChange: (String) -> Unit, mono: FontFamily) {
    val textStyle = remember(mono) { TextStyle(color = D.text, fontSize = 12.5.sp, fontFamily = mono) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(D.cyan),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Color(0x08FFFFFF)).border(1.dp, D.line, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Sym("search", size = 16.sp, color = D.faint)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Txt("Search snippets…", color = D.faint, size = 12.5.sp, font = mono)
                    inner()
                }
            }
        },
    )
}

@Composable
private fun SnipInput(value: String, mono: FontFamily) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 11.dp, vertical = 9.dp)) {
        Txt(value, color = D.text, size = 13.sp, font = mono)
    }
}
