package app.skerry.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.hack_bold
import app.skerry.ui.generated.resources.hack_regular
import app.skerry.ui.generated.resources.jetbrainsmono_bold
import app.skerry.ui.generated.resources.jetbrainsmono_regular
import org.jetbrains.compose.resources.Font

/**
 * Шрифт терминала, выбираемый в настройках (Appearance → Font). Оба варианта рендерятся БЕЗ
 * программных лигатур ([NO_LIGATURES]): `->`, `=>`, `!=` остаются раздельными глифами — обязательное
 * условие посимвольной сетки терминала. Hack лигатур не имеет by design; JetBrains Mono их имеет,
 * поэтому для него выключение существенно. [id] — стабильный ключ для персиста (см. desktop `main`).
 */
enum class TerminalFont(val displayName: String, val id: String) {
    /** Hack — моноширинный без лигатур, дефолт терминала. */
    Hack("Hack", "hack"),

    /** JetBrains Mono — дизайн-токен Skerry (тот же, что в остальном UI); лигатуры гасим принудительно. */
    JetBrainsMono("JetBrains Mono", "jetbrains-mono");

    companion object {
        val DEFAULT = Hack

        /** Разобрать сохранённый [id] обратно в значение; неизвестный/`null` → [DEFAULT]. */
        fun fromId(id: String?): TerminalFont = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Размер шрифта терминала по умолчанию. Применяется как `.sp` (см. [TerminalAppearance.fontSizeSp]);
 * в выпадающем списке подписан «px» — на масштабе шрифта 1.0 это одно и то же число.
 */
const val DEFAULT_TERMINAL_FONT_SIZE = 13

/** Допустимые размеры шрифта терминала (px) для выпадающего списка Appearance → Font size. */
val TERMINAL_FONT_SIZES: List<Int> = (11..18).toList()

/**
 * Отношение высоты строки к кеглю (13px → 18px в макете). Высота строки = кегль × это значение,
 * чтобы межстрочный интервал терминала масштабировался вместе с выбранным размером шрифта.
 */
const val TERMINAL_LINE_HEIGHT_RATIO = 18f / 13f

/**
 * Выключение программных лигатур в `TextStyle.fontFeatureSettings`: гасит `liga`/`calt`/`dlig`,
 * чтобы пары вроде `->` не склеивались в один глиф. Применяется к шрифту терминала независимо от
 * выбора (для Hack безвредно, для JetBrains Mono — необходимо).
 */
const val NO_LIGATURES = "\"liga\" 0, \"calt\" 0, \"dlig\" 0"

/** Внешний вид терминала, проводимый в [TerminalScreen] через [LocalTerminalAppearance]. */
@Immutable
data class TerminalAppearance(
    val font: TerminalFont = TerminalFont.DEFAULT,
    val fontSizeSp: Int = DEFAULT_TERMINAL_FONT_SIZE,
)

/**
 * Внешний вид терминала для всего дерева. Дефолт ([TerminalAppearance] по умолчанию = Hack 13px)
 * действует там, где провайдер не выставлен (мобильный таргет, превью, экран подключения), сохраняя
 * прежнее поведение. Desktop-корень ([app.skerry.ui.design.DesktopDesignApp]) подменяет его выбором
 * пользователя из настроек.
 */
val LocalTerminalAppearance = staticCompositionLocalOf { TerminalAppearance() }

/**
 * Семейство шрифта терминала по выбору [font] (regular + bold из compose-resources). `remember` внутри
 * не нужен: `Font(...)` сам @Composable и кэширует данные ресурса в слоте композиции (как
 * [app.skerry.ui.design.rememberMono] и соседи). Префикс `remember` сохранён ради единого стиля
 * со шрифтовыми фабриками проекта.
 */
@Composable
fun rememberTerminalFontFamily(font: TerminalFont): FontFamily = when (font) {
    TerminalFont.Hack -> FontFamily(
        Font(Res.font.hack_regular, weight = FontWeight.Normal),
        Font(Res.font.hack_bold, weight = FontWeight.Bold),
    )
    TerminalFont.JetBrainsMono -> FontFamily(
        Font(Res.font.jetbrainsmono_regular, weight = FontWeight.Normal),
        Font(Res.font.jetbrainsmono_bold, weight = FontWeight.Bold),
    )
}
