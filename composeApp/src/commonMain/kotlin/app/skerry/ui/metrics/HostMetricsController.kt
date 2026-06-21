package app.skerry.ui.metrics

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.ExecResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Периодически опрашивает ресурсы хоста через [exec] (один exec-канал на цикл) и публикует свежий
 * [HostMetrics] в [metrics] для info-панели. Опрос гоняется на [scope] сессии (как
 * [app.skerry.ui.sftp.SftpController]) — переживает переключение вкладок/панелей и останавливается
 * вместе с сессией ([stop] из [app.skerry.ui.connection.ConnectionController.disconnect]).
 *
 * Сбой одного опроса (обрыв канала, не-Linux вывод) не роняет цикл и не сбрасывает последний снимок:
 * [metrics] просто не обновляется до следующего удачного цикла.
 */
@Stable
class HostMetricsController(
    private val exec: suspend (String) -> ExecResult,
    private val scope: CoroutineScope,
    // Пауза между опросами ПОСЛЕ round-trip (не включает время exec, в котором ещё ~0.4s sleep на
    // CPU-выборку) — реальный период ≈ intervalMs + длительность exec.
    private val intervalMs: Long = 3_000,
) {
    var metrics: HostMetrics? by mutableStateOf(null)
        private set

    private var job: Job? = null

    /** Запустить периодический опрос (идемпотентно: повторный вызов не плодит второй цикл). */
    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                runCatching { exec(METRICS_COMMAND) }
                    .onFailure { if (it is CancellationException) throw it } // отмену не глотаем
                    .getOrNull()
                    ?.let { parseHostMetrics(it.stdout) }
                    ?.let { metrics = it }
                delay(intervalMs)
            }
        }
    }

    /** Остановить опрос. */
    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /**
         * Одна команда — один round-trip: две выборки /proc/stat для CPU по дельте, затем память,
         * диск и факты хоста (аптайм, load average, ОС, ядро, число CPU). Маркеры
         * `@MEM`/`@DISK`/`@UPTIME`/`@LOAD`/`@OS`/`@KERNEL`/`@CPU` разделяют секции для
         * [parseHostMetrics]. Факты дешёвы (всё из /proc + один файл + uname/nproc), поэтому
         * опрашиваются тем же циклом; статичные (ОС/ядро/CPU) парсер просто перечитывает.
         * Рассчитано на POSIX-шелл (`;`-цепочка команд) и Linux (/proc, free -b, df -Pk); на иных
         * системах отсутствующие секции просто дают `null`-поля (см. [parseHostMetrics]).
         */
        const val METRICS_COMMAND: String =
            "grep '^cpu ' /proc/stat; sleep 0.4; grep '^cpu ' /proc/stat; " +
                "echo '@MEM'; free -b; echo '@DISK'; df -Pk /; " +
                "echo '@UPTIME'; cat /proc/uptime; echo '@LOAD'; cat /proc/loadavg; " +
                "echo '@OS'; grep '^PRETTY_NAME=' /etc/os-release 2>/dev/null; " +
                "echo '@KERNEL'; uname -s -r -m; echo '@CPU'; nproc"
    }
}
