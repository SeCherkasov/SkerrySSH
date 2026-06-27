package app.skerry.ui.vault

/**
 * Копирование пароля в системный буфер обмена с минимизацией утечки. На Android помечаем клип как
 * sensitive (API 33+: `ClipDescription.EXTRA_IS_SENSITIVE` — прячет содержимое из истории буфера и
 * превью-уведомления) и через [CLIPBOARD_CLEAR_SECONDS] с автоматически очищаем буфер, если в нём
 * всё ещё лежит наш пароль (поведение менеджеров паролей). Публичный материал (открытый ключ,
 * сертификат) не чувствителен — для него остаётся обычный `LocalClipboardManager`.
 *
 * На desktop пометить буфер нечем и истории буфера на уровне ОС нет — просто кладём текст в буфер.
 */
expect fun copyPasswordToClipboard(password: String)

/**
 * Копирование не-секретного текста (открытый ключ, сертификат, отпечаток) в системный буфер — без
 * пометки sensitive и без автоочистки. Замена прежнего `LocalClipboardManager` для публичного
 * материала: единый платформенный путь и без deprecated Compose-API.
 */
expect fun copyTextToClipboard(text: String)

/** Через сколько секунд после Copy password буфер автоматически очищается (Android). */
const val CLIPBOARD_CLEAR_SECONDS: Int = 30
