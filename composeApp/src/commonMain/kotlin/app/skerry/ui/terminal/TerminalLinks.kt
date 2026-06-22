package app.skerry.ui.terminal

/**
 * Безопасна ли OSC 8-гиперссылка для открытия по Ctrl+клику. URI задаёт НЕДОВЕРЕННЫЙ ssh-сервер,
 * поэтому гейт жёсткий:
 *  - отклоняем любые управляющие байты (C0/DEL): `\r`/`\n` и пр. могли бы испортить диспетч URI
 *    на платформе (Intent/Desktop.browse);
 *  - пускаем лишь веб-схемы с authority (`http(s)://`, `ftp://`) либо `mailto:`. file:, javascript:,
 *    data: и degenerate-формы вроде `http:` (без `://`) не проходят.
 *
 * Чистая функция (вынесена из Composable ради юнит-тестов модели угроз).
 */
internal fun isSafeLinkUri(uri: String): Boolean {
    if (uri.any { it.code < 0x20 || it.code == 0x7F }) return false
    return uri.startsWith("https://", ignoreCase = true) ||
        uri.startsWith("http://", ignoreCase = true) ||
        uri.startsWith("ftp://", ignoreCase = true) ||
        uri.startsWith("mailto:", ignoreCase = true)
}
