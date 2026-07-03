package app.skerry.ui.files

/**
 * Небезопасное имя объекта из листинга — защита от path traversal со стороны недоверенного
 * сервера: пустое имя, разделители пути `/` и `\` (последний — сепаратор на Windows-таргете)
 * или `.`/`..`. Пути детей всегда собираются от каталога-родителя + имени, прошедшего этот
 * предикат ([childPath]); server-controlled `item.path` для записи/удаления не используется —
 * иначе вредоносный листинг увёл бы операцию в чужой каталог.
 */
internal fun isUnsafeListingName(name: String): Boolean =
    name.isEmpty() || "/" in name || "\\" in name || name == "." || name == ".."

/** Путь дочернего объекта [name] в каталоге [dir] (без двойного `/` в корне). */
internal fun childPath(dir: String, name: String): String = if (dir == "/") "/$name" else "$dir/$name"
