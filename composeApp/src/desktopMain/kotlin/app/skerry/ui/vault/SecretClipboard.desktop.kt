package app.skerry.ui.vault

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Desktop: пометить системный буфер sensitive нечем и истории буфера на уровне ОС нет, поэтому просто
 * кладём пароль в буфер (паритет с прежним Copy через `LocalClipboardManager`). Автоочистку на desktop
 * не делаем — она удивила бы пользователя, привыкшего к стабильному содержимому буфера.
 */
actual fun copyPasswordToClipboard(password: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(password), null)
}
