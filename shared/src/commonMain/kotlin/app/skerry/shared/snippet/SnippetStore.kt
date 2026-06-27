package app.skerry.shared.snippet

/**
 * Персистентное хранилище сохранённых сниппетов. Платформенная реализация — файловая (jvmShared),
 * как у [app.skerry.shared.tunnel.TunnelStore]. Контракт синхронный: мутации редки и инициируются из
 * UI. Реализации обязаны быть потокобезопасными.
 */
interface SnippetStore {
    /** Все сниппеты в порядке вставки/обновления. */
    fun all(): List<Snippet>

    /** Создать новую запись или заменить существующую с тем же [Snippet.id] (upsert). */
    fun put(snippet: Snippet)

    /** Удалить запись по id; отсутствующий id — no-op. */
    fun remove(id: String)
}
