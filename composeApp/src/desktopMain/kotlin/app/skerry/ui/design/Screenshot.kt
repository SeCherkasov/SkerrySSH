package app.skerry.ui.design

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.skerry.ui.theme.SkerryTheme
import java.io.File

/**
 * Офскрин-рендер десктопного дизайна в PNG для визуальной проверки без окна/композитора.
 * Управляется системным свойством `skerry.screenshot.*`: out — путь PNG, view/overlay — что показать.
 * Не часть приложения; запускается Gradle-задачей `screenshotDesign`.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val out = System.getProperty("skerry.screenshot.out", "/tmp/skerry_design.png")
    val viewName = System.getProperty("skerry.screenshot.view", "Terminal")
    val overlay = System.getProperty("skerry.screenshot.overlay", "")

    val state = DesktopDesignState()
    runCatching { state.showView(DesktopView.valueOf(viewName)) }
    when (overlay) {
        "lock" -> state.lock()
        "modal" -> state.openModal()
        "settings" -> state.openSettings()
    }

    val scene = ImageComposeScene(width = 1280, height = 820, density = Density(1f)) {
        SkerryTheme { DesktopDesignApp(state) }
    }
    // Пампим кадры с реальной паузой, чтобы compose-resources успели подгрузить шрифты (async IO).
    var img = scene.render(0)
    for (i in 1..80) {
        img = scene.render(i * 16_000_000L)
        Thread.sleep(16)
    }
    val data = img.encodeToData() ?: error("encode failed")
    File(out).writeBytes(data.bytes)
    scene.close()
    println("screenshot → $out (${File(out).length()} bytes)")
}
