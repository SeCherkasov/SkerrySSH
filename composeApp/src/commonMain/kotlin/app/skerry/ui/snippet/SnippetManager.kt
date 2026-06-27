package app.skerry.ui.snippet

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.snippet.Snippet
import app.skerry.shared.snippet.SnippetStore

/**
 * Редактируемые поля сниппета без [Snippet.id]: форма создания/правки оперирует черновиком, а
 * идентичность присваивает [SnippetManager]. [id] == null — создаётся новый сниппет.
 */
data class SnippetDraft(
    val id: String? = null,
    val label: String,
    val command: String,
    val tags: List<String> = emptyList(),
    val shortcut: String? = null,
)

/** Одна строка списка сниппетов: сохранённый [snippet], обновляется через [SnippetManager.save]. */
@Stable
class SnippetEntry internal constructor(snippet: Snippet) {
    var snippet: Snippet by mutableStateOf(snippet)
        internal set

    val id: String get() = snippet.id
}

/**
 * Менеджер сохранённых сниппетов (привычная модель SSH-клиентов): сниппет — самостоятельный объект в [SnippetStore],
 * а не часть открытой сессии. Чистый CRUD над библиотекой плюс [run] — формирование командной строки
 * для отправки в активный терминал. Терминал менеджеру не известен: вызывающий передаёт [send],
 * чтобы менеджер тестировался без живой сессии (как [app.skerry.ui.tunnel.TunnelManager] с `resolve`).
 */
@Stable
class SnippetManager(
    private val store: SnippetStore,
    private val newId: () -> String,
) {
    var snippets: List<SnippetEntry> by mutableStateOf(store.all().map { SnippetEntry(it) })
        private set

    fun find(id: String?): SnippetEntry? = id?.let { wanted -> snippets.firstOrNull { it.id == wanted } }

    /**
     * Сниппет с заданной горячей клавишей [shortcut] (каноничная форма, см. [Snippet.shortcut]) или
     * `null`. Используется глобальным обработчиком хоткеев. Пустой/`null` запрос — всегда `null`
     * (не матчим сниппеты без назначенного хоткея). При коллизии берём первый — UI не даёт назначить
     * один хоткей дважды, но на чтении не полагаемся на это.
     */
    fun forShortcut(shortcut: String?): SnippetEntry? {
        if (shortcut.isNullOrBlank()) return null
        return snippets.firstOrNull { it.snippet.shortcut == shortcut }
    }

    /**
     * Создать (если [SnippetDraft.id] == null) или обновить сниппет и записать в стор. Возвращает
     * назначенный id. Правка существующего обновляет строку на месте.
     */
    fun save(draft: SnippetDraft): String {
        val id = draft.id ?: newId()
        val snippet = Snippet(
            id = id,
            label = draft.label,
            command = draft.command,
            tags = draft.tags,
            shortcut = draft.shortcut?.takeIf { it.isNotBlank() },
        )
        store.put(snippet)
        val existing = find(id)
        if (existing != null) existing.snippet = snippet else snippets = snippets + SnippetEntry(snippet)
        return id
    }

    /** Удалить сниппет: убрать из стора и списка. */
    fun delete(id: String) {
        store.remove(id)
        snippets = snippets.filterNot { it.id == id }
    }

    /**
     * Запустить сниппет: отправить его команду с переводом строки в [send] (вызывающий привязывает
     * [send] к активному терминалу). Неизвестный id — no-op. Команда исполняется как есть, без
     * экранирования — это сохранённый пользователем текст, а не недоверенный ввод.
     */
    fun run(id: String, send: (String) -> Unit) {
        val snippet = find(id)?.snippet ?: return
        send(snippet.command + "\n")
    }
}
