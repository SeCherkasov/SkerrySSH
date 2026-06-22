package app.skerry.ui.terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Считает скорость терминального канала по дельте накопленных счётчиков байт за период опроса и
 * публикует её в [upRate]/[downRate] (байт/с) для статус-бара. Источник — сэмплеры [sampleUp]/
 * [sampleDown] поверх счётчиков [app.skerry.shared.ssh.ShellChannel.bytesUp]/`bytesDown`.
 *
 * Гоняется на [scope] сессии (как [app.skerry.ui.metrics.HostMetricsController]): переживает
 * переключение вкладок и останавливается вместе с сессией ([stop] из
 * [app.skerry.ui.connection.ConnectionController.disconnect]). Формула скорости — та же, что у
 * [app.skerry.ui.forward.PortForwardController]: `дельта * 1000 / период`.
 */
@Stable
class ThroughputController(
    private val sampleUp: () -> Long,
    private val sampleDown: () -> Long,
    private val scope: CoroutineScope,
    private val pollIntervalMillis: Long = 1000,
) {
    var upRate: Long by mutableStateOf(0)
        private set

    var downRate: Long by mutableStateOf(0)
        private set

    private var prevUp = 0L
    private var prevDown = 0L
    private var job: Job? = null

    /**
     * Запустить периодический опрос (идемпотентно). Базис снимаем сразу: уже накопленные к моменту
     * старта байты (баннер шелла и т.п.) не должны сосчитаться как скорость первого периода.
     */
    fun start() {
        if (job != null) return
        prevUp = sampleUp()
        prevDown = sampleDown()
        job = scope.launch {
            while (isActive) {
                delay(pollIntervalMillis)
                poll()
            }
        }
    }

    /** Один замер: скорость = прирост байт за период, приведённый к секунде; счётчик не убывает. */
    internal fun poll() {
        val up = sampleUp()
        val down = sampleDown()
        upRate = ((up - prevUp) * 1000 / pollIntervalMillis).coerceAtLeast(0)
        downRate = ((down - prevDown) * 1000 / pollIntervalMillis).coerceAtLeast(0)
        prevUp = up
        prevDown = down
    }

    /** Остановить опрос. */
    fun stop() {
        job?.cancel()
        job = null
    }
}
