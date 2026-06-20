package app.skerry.ui.forward

/**
 * Разобранные и проверенные параметры формы проброса (без направления — оно выбирается отдельно).
 * Получается только через [parseForwardInput], поэтому всегда содержит валидные значения.
 */
data class ForwardRequest(
    val bindPort: Int,
    val destHost: String,
    val destPort: Int,
)

/**
 * Разобрать и проверить сырой ввод формы проброса. Возвращает [ForwardRequest] при корректных
 * данных или `null`, если поля неполны/некорректны (кнопку «Поднять» в этом случае держим неактивной).
 *
 * Правила: порт слушателя `0..65535` (`0` = «выберет ОС/сервер»), хост назначения непуст,
 * порт назначения `1..65535` (`0` бессмыслен — туда некуда подключаться).
 *
 * Общий источник правды для desktop ([PortForwardScreen]) и мобильной формы — чтобы валидация не
 * разъезжалась между платформами.
 */
fun parseForwardInput(bindPort: String, destHost: String, destPort: String): ForwardRequest? {
    val bind = parseBindPort(bindPort) ?: return null
    val host = destHost.trim().ifEmpty { return null }
    val dest = destPort.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return ForwardRequest(bind, host, dest)
}

/**
 * Проверить порт слушателя в отрыве от адреса назначения — для динамического проброса (`-D`),
 * где назначения нет. `0..65535` (`0` = «выберет ОС»); `null`, если значение некорректно (кнопку
 * «Поднять» держим неактивной). Общий источник правды для desktop и мобильной формы.
 */
fun parseBindPort(bindPort: String): Int? =
    bindPort.trim().toIntOrNull()?.takeIf { it in 0..65535 }

/** Короткий бейдж направления (`-L`/`-R`/`-D`) для списков и переключателей. */
internal fun directionShort(direction: ForwardDirection): String = when (direction) {
    ForwardDirection.Local -> "-L"
    ForwardDirection.Remote -> "-R"
    ForwardDirection.Dynamic -> "-D"
}
