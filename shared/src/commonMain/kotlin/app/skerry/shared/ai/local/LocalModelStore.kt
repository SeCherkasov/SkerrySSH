package app.skerry.shared.ai.local

import okio.FileSystem
import okio.Path

/**
 * Дисковое хранилище скачанных GGUF-моделей: раскладка файлов в каталоге [dir] и проверка
 * установленности. Незавершённая закачка живёт рядом с целью как `<файл>.part` (докачивается
 * по Range, см. [ModelDownloader]); установленной модель считается только при точном совпадении
 * размера с каталогом — обрубленный/чужой файл не пройдёт (целостность по sha256 гарантирует
 * загрузчик до финального rename).
 *
 * Модели — публичные веса, не секреты: права 0600/harden не нужны (каталог и так приватный:
 * `filesDir` на Android, `~/.local/share/skerry` на desktop).
 */
class LocalModelStore(
    private val fileSystem: FileSystem,
    private val dir: Path,
) {
    /** Путь установленной модели. */
    fun path(model: LocalModel): Path = dir.resolve(model.fileName)

    /** Путь незавершённой закачки (докачивается с этого места). */
    fun partPath(model: LocalModel): Path = dir.resolve("${model.fileName}.part")

    /** Установлена ли модель: файл на месте и размер совпадает с каталожным байт-в-байт. */
    fun isInstalled(model: LocalModel): Boolean =
        fileSystem.metadataOrNull(path(model))?.size == model.sizeBytes

    /** Сколько байт уже скачано в part-файл; 0 — закачка не начиналась. */
    fun downloadedBytes(model: LocalModel): Long =
        fileSystem.metadataOrNull(partPath(model))?.size ?: 0L

    /** Удалить модель и её незавершённую закачку (идемпотентно). */
    fun delete(model: LocalModel) {
        fileSystem.delete(path(model), mustExist = false)
        fileSystem.delete(partPath(model), mustExist = false)
    }
}
