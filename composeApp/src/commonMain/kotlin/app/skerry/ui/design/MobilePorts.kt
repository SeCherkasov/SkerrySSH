package app.skerry.ui.design

import app.skerry.ui.forward.ForwardDirection
import app.skerry.ui.forward.ForwardEntry
import app.skerry.ui.forward.ForwardStatus
import app.skerry.ui.forward.forwardDestText

/**
 * Режим мобильного экрана Port forwarding (`docs/new/Skerry Mobile.html`, секция PORTS). Зеркалит
 * ветвление desktop-`TunnelsView`/мобильного `MobileFilesMode`:
 * - [Preview] — нет менеджера сессий (офскрин-рендер/превью без бэкенда) → статичный мок макета;
 * - [Live] — есть активная подключённая сессия → живые пробросы поверх [app.skerry.ui.forward.PortForwardController];
 * - [NoSession] — менеджер есть, но активная сессия не подключена → уведомление «нет сессии».
 */
enum class MobilePortsMode { Preview, NoSession, Live }

/** Выбрать режим экрана Ports по наличию менеджера сессий и факту подключения активной сессии. */
fun mobilePortsMode(hasSessions: Boolean, connected: Boolean): MobilePortsMode = when {
    !hasSessions -> MobilePortsMode.Preview
    connected -> MobilePortsMode.Live
    else -> MobilePortsMode.NoSession
}

/** Иконка-стрелка карточки туннеля: динамический (`-D`) → `all_inclusive`, иначе `arrow_forward`. */
fun mobileTunnelArrow(direction: ForwardDirection): String =
    if (direction == ForwardDirection.Dynamic) "all_inclusive" else "arrow_forward"

/**
 * Текст назначения карточки: явный `host:port` ([forwardDestText]) либо `dynamic proxy` для `-D`
 * (у SOCKS фиксированного назначения нет — его задаёт клиент), как в макете.
 */
fun mobileTunnelDest(entry: ForwardEntry): String = forwardDestText(entry) ?: "dynamic proxy"

/** Число живых активных туннелей (не на паузе) — для подзаголовка строки Port forwarding в хабе More. */
fun mobileActiveTunnelCount(forwards: List<ForwardEntry>): Int =
    forwards.count { it.status is ForwardStatus.Active && !it.paused }

/**
 * Подзаголовок строки Port forwarding в More: число активных туннелей подключённой сессии, либо
 * пустая строка, если активной сессии нет ([count]=null) — нечего считать (честная проекция,
 * в отличие от статичного «2 active» макета).
 */
fun mobileMorePortsSubtitle(count: Int?): String = if (count == null) "" else "$count active"

/** Подзаголовок строки Known hosts в More: число незакрытых смен ключа, либо «All verified», если их нет. */
fun mobileMoreKnownSubtitle(changed: Int): String =
    if (changed == 0) "All verified" else "$changed changed"
