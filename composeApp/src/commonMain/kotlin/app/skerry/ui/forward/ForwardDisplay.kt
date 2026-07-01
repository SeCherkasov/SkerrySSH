package app.skerry.ui.forward

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ptail_type_local
import app.skerry.ui.generated.resources.ptail_type_remote
import app.skerry.ui.generated.resources.ptail_type_socks
import org.jetbrains.compose.resources.stringResource

/**
 * Чистые хелперы представления одного проброса в виде колонок таблицы туннелей нового дизайна
 * (`TunnelsView`): таблица desktop показывает source и destination раздельными ячейками. Общий
 * источник правды — чтобы формат не разъезжался между представлениями.
 */

/** Метка типа проброса для бейджа таблицы: `-L`→LOCAL, `-R`→REMOTE, `-D`→SOCKS. */
@Composable
fun forwardTypeLabel(direction: ForwardDirection): String = when (direction) {
    ForwardDirection.Local -> stringResource(Res.string.ptail_type_local)
    ForwardDirection.Remote -> stringResource(Res.string.ptail_type_remote)
    ForwardDirection.Dynamic -> stringResource(Res.string.ptail_type_socks)
}

/**
 * Порт слушателя: фактический ([ForwardStatus.Active.boundPort]) после поднятия, иначе запрошенный
 * ([ForwardEntry.requestedPort]) — пока статус Starting/Failed (реального порта ещё нет).
 */
fun forwardListenPort(entry: ForwardEntry): Int =
    (entry.status as? ForwardStatus.Active)?.boundPort ?: entry.requestedPort

/**
 * Адрес-источник (сторона слушателя). Для `-L`/`-D` слушатель на этой машине ([ForwardEntry.bindHost]);
 * для `-R` слушатель поднимает сервер, поэтому хост показываем как `server`.
 */
fun forwardSourceText(entry: ForwardEntry): String {
    val port = forwardListenPort(entry)
    return when (entry.direction) {
        ForwardDirection.Remote -> "server:$port"
        else -> "${entry.bindHost}:$port"
    }
}

/**
 * Адрес назначения для ячейки DESTINATION, или `null` для динамического (`-D`) проброса — у него
 * назначение задаёт клиент SOCKS в момент соединения, фиксированного destination нет.
 */
fun forwardDestText(entry: ForwardEntry): String? = when (entry.direction) {
    ForwardDirection.Dynamic -> null
    else -> "${entry.destHost}:${entry.destPort}"
}

/**
 * Человекочитаемая скорость для метки throughput: `B/s` < 1 KiB, целые `KB/s` < 1 MiB, иначе `MB/s`
 * с одной десятичной. База — 1024 (как принято для сетевых индикаторов в этом UI).
 */
fun humanRate(bytesPerSec: Long): String {
    if (bytesPerSec < 1024) return "$bytesPerSec B/s"
    val kb = bytesPerSec / 1024
    if (kb < 1024) return "$kb KB/s"
    val mbTenths = bytesPerSec * 10 / (1024 * 1024)
    return "${mbTenths / 10}.${mbTenths % 10} MB/s"
}

/**
 * Доля заполнения throughput-метра (0..1) по скорости: линейная шкала с насыщением на 1 MiB/с —
 * этого достаточно как визуальный индикатор интенсивности (точное значение даёт [humanRate]).
 */
fun rateFraction(bytesPerSec: Long): Float =
    (bytesPerSec.toFloat() / (1024f * 1024f)).coerceIn(0f, 1f)
