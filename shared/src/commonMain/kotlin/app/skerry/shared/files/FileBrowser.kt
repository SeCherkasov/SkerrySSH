package app.skerry.shared.files

/** Тип объекта в файловой панели; «прочее» — устройства, сокеты, FIFO и т.п. */
enum class FileItemType { File, Directory, Symlink, Other }

/**
 * Метаданные объекта в файловой панели — нейтральны к источнику (локальная ФС или удалённый SFTP),
 * чтобы один и тот же UI-список и контроллер обслуживали обе панели Total-Commander-режима.
 * [path] — абсолютный путь в пространстве своего источника; [size] в байтах; [modifiedEpochSeconds]
 * — mtime (Unix-секунды, `0`, если источник его не сообщил). Для симлинка атрибуты — самого линка.
 */
data class FileItem(
    val name: String,
    val path: String,
    val type: FileItemType,
    val size: Long,
    val modifiedEpochSeconds: Long,
)

/**
 * Навигация и операции над каталогами одного файлового источника — общий контракт локальной ФС
 * ([LocalFileBrowser]) и удалённого SFTP (адаптер поверх [app.skerry.shared.sftp.SftpClient]).
 * Передачу файлов контракт НЕ описывает: в двухпанельном режиме она всегда идёт между локальной ФС
 * и SFTP и выражается готовыми `SftpClient.download`/`upload`, поэтому живёт в координаторе экрана,
 * а не в браузере. Все методы suspend: I/O уводится с вызывающего потока. Пути — абсолютные,
 * POSIX-семантика (разделитель `/`).
 */
interface FileBrowser {

    /** Короткая метка источника для заголовка панели («This Mac», имя хоста). */
    val label: String

    /**
     * Канонический абсолютный путь для [path] (разворачивает `.`, `..`). Передать `.` — получить
     * стартовый каталог источника (домашний локально, рабочий каталог сессии у SFTP). Разворот
     * симлинков НЕ гарантируется и зависит от источника: SFTP разрешает их на сервере
     * (`SSH_FXP_REALPATH`), локальная реализация нормализует путь лишь лексически.
     * @throws FileBrowserException путь не разрешается
     */
    suspend fun realpath(path: String): String

    /**
     * Содержимое каталога [path] без `.` и `..`; порядок не гарантируется (панель сортирует сама).
     * @throws FileBrowserException путь не существует, это не каталог или нет прав
     */
    suspend fun list(path: String): List<FileItem>

    /**
     * Создать каталог [path]. Родитель должен существовать (без `-p`).
     * @throws FileBrowserException путь занят или нет прав
     */
    suspend fun mkdir(path: String)

    /**
     * Удалить [item]. Каталог удаляется РЕКУРСИВНО (вместе с содержимым); файл/симлинк — как сам
     * объект (по симлинку в цель не заходим). Подтверждение — на стороне UI.
     * @throws FileBrowserException пути нет или нет прав
     */
    suspend fun delete(item: FileItem)

    /**
     * Переименовать/переместить [from] в [to] в пределах источника.
     * @throws FileBrowserException источника нет, цель занята или нет прав
     */
    suspend fun rename(from: String, to: String)
}

/** Ошибка операции файлового браузера: нет пути/прав, неверный тип объекта или обрыв источника. */
class FileBrowserException(message: String, cause: Throwable? = null) : Exception(message, cause)
