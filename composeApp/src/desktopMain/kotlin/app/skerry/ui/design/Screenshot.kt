package app.skerry.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.skerry.ui.theme.SkerryTheme
import java.io.File

/**
 * Офскрин-рендер десктопного дизайна в PNG для визуальной проверки без окна/композитора.
 * Управляется системным свойством `skerry.screenshot.*`: out — путь PNG, view/overlay — что показать.
 * Не часть приложения; запускается Gradle-задачей `screenshotDesign`.
 *
 * Оверлеи `create`/`unlock` рендерят живые экраны гейта мастер-пароля ([DesktopCreateScreen]/
 * [DesktopUnlockScreen]) standalone (без `VaultGateController`/lifecycle) — проверка визуала; их
 * проводка к [app.skerry.ui.vault.VaultGate] покрыта тестами контроллера и компиляцией.
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

    val content: @Composable () -> Unit = when (overlay) {
        "create" -> { { GateScreenPreview { DesktopCreateScreen(error = null) { _, _ -> } } } }
        "unlock" -> { { GateScreenPreview { DesktopUnlockScreen(error = null, canUseBiometric = true, onUnlock = {}, onBiometric = {}) } } }
        else -> { { DesktopDesignApp(state) } }
    }

    val scene = ImageComposeScene(width = 1280, height = 820, density = Density(1f)) {
        SkerryTheme { content() }
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

/** Поставщик дизайн-шрифтов для standalone-рендера экранов, минуя [DesktopDesignApp]. */
@Composable
private fun GateScreenPreview(body: @Composable () -> Unit) {
    val fonts = DesignFonts(
        ui = rememberSpaceGrotesk(),
        mono = rememberMono(),
        symbols = rememberMaterialSymbols(),
    )
    CompositionLocalProvider(LocalFonts provides fonts) { body() }
}
