package app.skerry.ui.terminal

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

/** Android: текст оборачиваем в [ClipData] (нативный носитель [ClipEntry] на Android). Метка пустая
 *  (`""`, не `null`) — безопаснее для приложений, читающих историю буфера и не ждущих null-label. */
internal actual fun plainTextClipEntry(text: String): ClipEntry =
    ClipEntry(ClipData.newPlainText("", text))

/** Android: берём текст первого элемента [ClipData]; `null`, если буфер пуст или элемент без текста. */
internal actual fun ClipEntry.readPlainText(): String? =
    clipData.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

/** Android: прямого (не-Compose) пути к буферу нет — всегда штатный Compose-буфер. */
internal actual fun readSystemClipboardDirect(): String? = null

/** Android: прямого (не-Compose) пути к буферу нет — всегда штатный Compose-буфер. */
internal actual fun writeSystemClipboardDirect(text: String): Boolean = false

/** Android: прямого пути нет — чтение всегда через штатный Compose-буфер. */
internal actual fun systemClipboardDirectHandlesReads(): Boolean = false
