package app.skerry.ui.terminal

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.asAwtTransferable
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/** Desktop: текст оборачиваем в AWT [StringSelection] (нативный носитель [ClipEntry] на desktop). */
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainTextClipEntry(text: String): ClipEntry = ClipEntry(StringSelection(text))

/**
 * Desktop: тянем у AWT-[java.awt.datatransfer.Transferable] ровно `stringFlavor` — без перебора прочих
 * форматов. Любой сбой (формат не поддержан, IO, недоступность буфера) гасим в `null`, чтобы вставка
 * молча откатилась. (Замечание: диагностический stack-trace при чужих сериализованных flavor'ах печатает
 * сам JDK внутри `getContents`/перечисления форматов — он безвреден и от нашего выбора flavor не зависит.)
 */
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun ClipEntry.readPlainText(): String? = try {
    asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) as? String
} catch (_: Exception) {
    null
}
