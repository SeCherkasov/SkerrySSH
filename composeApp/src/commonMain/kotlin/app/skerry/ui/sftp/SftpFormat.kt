package app.skerry.ui.sftp

import app.skerry.shared.sftp.SftpEntry
import app.skerry.shared.sftp.SftpEntryType
import kotlin.math.roundToLong

/** Двоичные единицы размера (1 KB = 1024 B), как привычно для файловых менеджеров. */
private val SIZE_UNITS = listOf("KB", "MB", "GB", "TB", "PB")

/**
 * Человекочитаемый размер: ниже 1 КиБ — сырые байты («96 B»), выше — одна десятичная и двоичная
 * единица («1.5 KB», «418.0 MB»). Без `String.format` (нет в commonMain) — десятая часть руками.
 */
fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < SIZE_UNITS.lastIndex) {
        value /= 1024
        unit++
    }
    var tenths = (value * 10).roundToLong()
    // Округление может «дотянуть» до 1024.0 текущей единицы (напр. 1048575 B → 1024.0 KB); тогда
    // переносим в следующую единицу, чтобы показать «1.0 MB», а не «1024.0 KB».
    if (tenths >= 10_240 && unit < SIZE_UNITS.lastIndex) {
        unit++
        tenths = 10
    }
    return "${tenths / 10}.${tenths % 10} ${SIZE_UNITS[unit]}"
}

/**
 * Строка прав в стиле `ls -l`: символ типа (`d`/`l`/`-`/`?`) + три триплета rwx из младших 9 бит
 * [permissions] (POSIX mode). Биты читаются от owner-read (0o400) к other-execute (0o001).
 */
fun sftpPermissionString(type: SftpEntryType, permissions: Int): String {
    val typeChar = when (type) {
        SftpEntryType.Directory -> 'd'
        SftpEntryType.Symlink -> 'l'
        SftpEntryType.File -> '-'
        SftpEntryType.Other -> '?'
    }
    val rwx = "rwxrwxrwx"
    val sb = StringBuilder(10).append(typeChar)
    for (i in 0..8) {
        val bit = 1 shl (8 - i)
        sb.append(if (permissions and bit != 0) rwx[i] else '-')
    }
    return sb.toString()
}

/** Мета-колонка строки: размер для файлов, строка прав для каталогов/ссылок/прочего. */
fun sftpEntryMeta(entry: SftpEntry): String =
    if (entry.type == SftpEntryType.File) humanSize(entry.size)
    else sftpPermissionString(entry.type, entry.permissions)

/** Имя Material-иконки (лигатура [app.skerry.ui.design.Sym]) по типу объекта. */
fun sftpEntryIcon(type: SftpEntryType): String = when (type) {
    SftpEntryType.Directory -> "folder"
    SftpEntryType.Symlink -> "link"
    SftpEntryType.File, SftpEntryType.Other -> "description"
}
