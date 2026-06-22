package app.skerry.ui.terminal

/** В Android нет PRIMARY-выделения — вставка средней кнопкой откатывается на обычный буфер обмена. */
internal actual fun readPrimarySelectionText(): String? = null

/** В Android нет PRIMARY-выделения — запись не нужна (no-op). */
internal actual fun writePrimarySelectionText(text: String) = Unit
