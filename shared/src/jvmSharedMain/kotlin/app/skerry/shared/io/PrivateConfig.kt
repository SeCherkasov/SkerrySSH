package app.skerry.shared.io

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Помощники для приватных конфиг-файлов Skerry: каталог получает права 0700, файл — 0600, чтобы
 * локальные данные (инлайн-креды сниппетов, профили хостов, known-hosts) не были мир-читаемыми под
 * общим домашним каталогом. На системах без POSIX-атрибутов (Windows) права не выставляются —
 * доступ там ограничен ACL пользовательского профиля. Установка прав — best-effort: её отказ
 * (не-POSIX FS) не должен валить запись.
 */
object PrivateConfig {

    private val DIR_PERMS_SET = PosixFilePermissions.fromString("rwx------")
    private val DIR_PERMS = PosixFilePermissions.asFileAttribute(DIR_PERMS_SET)
    private val FILE_PERMS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

    /**
     * Гарантировать каталог [dir] с правами 0700. Если каталога нет — создаётся вместе с предками
     * (атрибут применяется только к создаваемым уровням). Если каталог уже есть — права всё равно
     * подтягиваются к 0700: апгрейд со старой установки, где каталог мог остаться 0755 от umask.
     */
    fun ensureDir(dir: Path) {
        if (Files.exists(dir)) {
            runCatching { Files.setPosixFilePermissions(dir, DIR_PERMS_SET) }
            return
        }
        // file-attribute не поддержан на не-POSIX FS — тогда создаём без него (права по umask).
        runCatching { Files.createDirectories(dir, DIR_PERMS) }
            .onFailure { runCatching { Files.createDirectories(dir) } }
    }

    /** Выставить файлу права 0600 (best-effort; на не-POSIX FS — no-op). */
    fun harden(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, FILE_PERMS) }
    }

    /**
     * Атомарно записать [bytes] в [path] приватным файлом (0600) в каталоге 0700: запись идёт в
     * уникальный временный файл рядом (на POSIX он сразу 0600 — `createTempFile`), затем он
     * перемещается на место ([ATOMIC_MOVE], при неподдержке — [REPLACE_EXISTING]). Уникальное имя
     * (а не фиксированное `.tmp`) исключает гонку между двумя процессами за один и тот же tmp. Сбой
     * пробрасывается — потеря данных не должна быть тихой; недописанный tmp подчищается.
     */
    fun atomicWrite(path: Path, bytes: ByteArray) {
        val parent = path.parent
        if (parent != null) ensureDir(parent)
        val tmp = if (parent != null) {
            Files.createTempFile(parent, "${path.fileName}.", ".tmp")
        } else {
            path.resolveSibling("${path.fileName}.tmp").also { Files.deleteIfExists(it) }
        }
        try {
            Files.write(tmp, bytes)
            harden(tmp) // на не-POSIX FS createTempFile не даёт 0600 — добиваем явно (no-op на POSIX)
            runCatching { Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE) }
                .onFailure { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING) }
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw t
        }
    }
}
