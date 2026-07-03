package app.skerry.ui.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Регрессия бага «смена темы терминала перекрашивает не всё до переключения вкладки»: рендерим
 * живой [TerminalScreen] офскрин, меняем [LocalTerminalTheme] на лету (как это делает Appearance
 * через [app.skerry.ui.desktop.DesktopDesignApp]) и проверяем ПИКСЕЛИ обоих путей отрисовки:
 * заливку фона ячейки (SGR 44 → drawRect) и сами глифы (SGR 32 + U+2588 FULL BLOCK → drawText).
 * Глифовый путь исторически стейлился: кэш [androidx.compose.ui.text.TextMeasurer] сравнивает
 * стили ТОЛЬКО по layout-атрибутам (цвет не входит), а перегрузка `drawText(measurer, …)` красит
 * цветом, вшитым в закэшированный параграф, — после смены темы глифы оставались в старой палитре,
 * пока пересоздание экрана (переключение вкладки) не сбрасывало кэш измерителя.
 */
@OptIn(ExperimentalComposeUiApi::class)
class TerminalThemeSwitchRenderTest {

    /** Фейковая PTY-сессия: только вывод, ввод/resize — no-op. */
    private class FakeSession : TerminalSession {
        private val _state = MutableStateFlow<TerminalState>(TerminalState.Open)
        override val state: StateFlow<TerminalState> = _state.asStateFlow()
        private val _output = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val output: Flow<ByteArray> = _output.asSharedFlow()
        override suspend fun send(data: ByteArray) {}
        override suspend fun resize(size: PtySize) {}
        override suspend fun close() {}
        fun emit(text: String) {
            check(_output.tryEmit(text.encodeToByteArray())) { "output buffer overflow" }
        }
    }

    @Test
    fun themeSwitchRecolorsOpenTerminalWithoutTabSwitch() {
        // Unconfined: feed/resize обрабатываются синхронно в точке эмита — кадры теста детерминированы.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = FakeSession()
        val state = TerminalScreenState(session, scope)
        val theme = mutableStateOf(TerminalThemes.NightSea)
        try {
            ImageComposeScene(width = 400, height = 260, density = Density(1f)).use { scene ->
                scene.setContent {
                    CompositionLocalProvider(LocalTerminalTheme provides theme.value) {
                        TerminalScreen(state, Modifier.fillMaxSize())
                    }
                }
                var timeNanos = 0L
                fun frame(): PixelMap {
                    Snapshot.sendApplyNotifications()
                    timeNanos += 16_666_667L
                    return scene.render(timeNanos).toComposeImageBitmap().toPixelMap()
                }

                // Первые кадры: layout → LaunchedEffect(resize) → sized=true. Эмитим вывод только
                // ПОСЛЕ подгонки сетки: при усадке 80×24 → фактическая сетка верхние строки уходят в
                // scrollback и прячутся за автоскроллом — пробные строки должны остаться на экране.
                repeat(3) { frame() }
                // Строка 1: SGR 44 — заливка фона (drawRect). Строка 2: SGR 32 + 3×U+2588 FULL BLOCK —
                // сплошные глифы (drawText). РОВНО ТРИ блока: кэш TextMeasurer держит 8 записей, и как у
                // пользователя (несколько коротких ранов) все ключи должны УМЕЩАТЬСЯ в кэш — иначе LRU
                // вытеснит записи и замаскирует стейл старого цвета.
                session.emit("\u001b[44m          \u001b[0m\r\n\u001b[32m███\u001b[0m")

                // Ждём, пока отрисуются ОБА пути старой палитры (кадры на эффекты/публикацию снимка).
                var pixels = frame()
                var attempts = 0
                while ((!pixels.hasColor(NIGHT_SEA_ANSI_BLUE) || !pixels.hasColor(NIGHT_SEA_ANSI_GREEN)) && attempts < 30) {
                    pixels = frame()
                    attempts++
                }
                assertTrue(pixels.hasColor(NIGHT_SEA_ANSI_BLUE), "не дождались заливки SGR 44 в палитре Night Sea")
                assertTrue(pixels.hasColor(NIGHT_SEA_ANSI_GREEN), "не дождались глифов SGR 32 в палитре Night Sea")

                // Смена темы на лету — как клик по карточке в Appearance.
                theme.value = TerminalThemes.SolarizedLight
                pixels = frame()
                pixels = frame() // второй кадр — на случай отложенной инвалидации

                assertTrue(pixels.hasColor(SOLARIZED_BG), "фон терминала должен перекраситься в Solarized Light")
                assertTrue(pixels.hasColor(SOLARIZED_ANSI_BLUE), "заливка SGR 44 должна перекраситься в синий Solarized")
                if (pixels.hasColor(NIGHT_SEA_ANSI_BLUE)) {
                    fail("заливка SGR 44 осталась синей Night Sea — фоновый слой не перерисован после смены темы")
                }
                if (pixels.hasColor(NIGHT_SEA_ANSI_GREEN)) {
                    fail("глифы SGR 32 остались зелёными Night Sea — drawText рисует старым цветом из кэша TextMeasurer")
                }
                assertTrue(pixels.hasColor(SOLARIZED_ANSI_GREEN), "глифы SGR 32 должны перекраситься в зелёный Solarized")
            }
        } finally {
            scope.cancel()
        }
    }

    /** Есть ли в кадре хоть один пиксель точного цвета [argb] (скан с шагом 1 — сцена маленькая). */
    private fun PixelMap.hasColor(argb: Int): Boolean {
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (this[x, y].toArgb() == argb) return true
            }
        }
        return false
    }

    private companion object {
        val NIGHT_SEA_ANSI_BLUE = 0xFF4A9EDB.toInt()
        val NIGHT_SEA_ANSI_GREEN = 0xFF5DCE9E.toInt()
        val SOLARIZED_BG = 0xFFFDF6E3.toInt()
        val SOLARIZED_ANSI_BLUE = 0xFF268BD2.toInt()
        val SOLARIZED_ANSI_GREEN = 0xFF859900.toInt()
    }
}
