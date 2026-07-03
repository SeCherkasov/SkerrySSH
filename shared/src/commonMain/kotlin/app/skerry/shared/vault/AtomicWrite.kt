package app.skerry.shared.vault

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Атомарная перезапись UTF-8 файла, общая для [FileVault], [FileSecurityLog] и
 * [FileBioArtifactStore]: текст пишется во временный файл рядом с целью, затем
 * [FileSystem.atomicMove] подменяет цель (okio: NIO — `ATOMIC_MOVE+REPLACE_EXISTING`;
 * legacy/native POSIX `rename(2)`; `FakeFileSystem`), поэтому отдельного «move с перезаписью»
 * не нужно. Если запись или move упали — исключение всплывает к вызывающему (его state не
 * тронут: коммит полей идёт после persist), а tmp подчищается, чтобы не оставлять на диске
 * осиротевшую копию секретов.
 *
 * [harden] зовётся на tmp ДО move: права (0600 на POSIX) получает сам файл секретов ещё до
 * подмены цели — у готового файла нет окна с правами по umask. По умолчанию no-op (тесты на
 * `FakeFileSystem`, Android — filesDir приватен для UID); desktop передаёт `PrivateConfig.harden`.
 */
internal fun atomicWriteUtf8(
    fileSystem: FileSystem,
    path: Path,
    text: String,
    harden: (Path) -> Unit = {},
) {
    path.parent?.let { fileSystem.createDirectories(it) }
    val tmp = path.parent?.resolve("${path.name}.tmp") ?: "${path.name}.tmp".toPath()
    try {
        fileSystem.write(tmp) { writeUtf8(text) }
        harden(tmp)
        fileSystem.atomicMove(tmp, path)
    } catch (e: Throwable) {
        runCatching { fileSystem.delete(tmp, mustExist = false) } // не оставлять осиротевший tmp
        throw e
    }
}
