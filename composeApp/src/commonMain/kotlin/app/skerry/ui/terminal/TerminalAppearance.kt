package app.skerry.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.skerry.shared.terminal.CursorShape
import app.skerry.shared.terminal.DEFAULT_MAX_SCROLLBACK
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.hack_bold
import app.skerry.ui.generated.resources.hack_regular
import app.skerry.ui.generated.resources.jetbrainsmono_bold
import app.skerry.ui.generated.resources.jetbrainsmono_regular
import kotlin.math.round
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
 * в слайдере подписан «px» — на масштабе шрифта 1.0 это одно и то же число.
 */
const val DEFAULT_TERMINAL_FONT_SIZE = 13

/**
 * Границы кегля шрифта терминала (px), которые пользователь крутит слайдером Appearance → Font size.
 * Диапазон широкий (8..40) — «крутить как хочешь», в отличие от прежнего фиксированного списка 11..18.
 */
const val TERMINAL_FONT_SIZE_MIN = 8
const val TERMINAL_FONT_SIZE_MAX = 40
val TERMINAL_FONT_SIZE_RANGE: IntRange = TERMINAL_FONT_SIZE_MIN..TERMINAL_FONT_SIZE_MAX

/**
 * Отношение высоты строки к кеглю по умолчанию (13px → 18px в макете). Высота строки = кегль × это
 * значение, чтобы межстрочный интервал масштабировался вместе с кеглем. Пользователь может изменить
 * его слайдером Appearance → Line height в пределах [TERMINAL_LINE_HEIGHT_MIN]..[TERMINAL_LINE_HEIGHT_MAX].
 */
const val DEFAULT_TERMINAL_LINE_HEIGHT = 18f / 13f
const val TERMINAL_LINE_HEIGHT_MIN = 1.0f
const val TERMINAL_LINE_HEIGHT_MAX = 2.0f

/**
 * Межбуквенный интервал терминала (sp/px на масштабе 1.0), слайдер Appearance → Letter spacing. По
 * умолчанию 0 (натуральный advance шрифта); отрицательное сжимает, положительное растягивает. Сетка
 * терминала остаётся согласованной: ширина ячейки меряется тем же [TextStyle] и учитывает интервал.
 */
const val DEFAULT_TERMINAL_LETTER_SPACING = 0f
const val TERMINAL_LETTER_SPACING_MIN = -1.5f
const val TERMINAL_LETTER_SPACING_MAX = 4.0f

/** Округление дробного значения до 2 знаков — чтобы слайдер не плодил «1.4000001» в состоянии/персисте. */
private fun round2(value: Float): Float = round(value * 100f) / 100f

/** Привести высоту строки к допустимому диапазону и шагу (clamp + округление). */
fun clampTerminalLineHeight(value: Float): Float =
    round2(value.coerceIn(TERMINAL_LINE_HEIGHT_MIN, TERMINAL_LINE_HEIGHT_MAX))

/** Привести межбуквенный интервал к допустимому диапазону и шагу (clamp + округление). */
fun clampTerminalLetterSpacing(value: Float): Float =
    round2(value.coerceIn(TERMINAL_LETTER_SPACING_MIN, TERMINAL_LETTER_SPACING_MAX))

/**
 * Выключение программных лигатур в `TextStyle.fontFeatureSettings`: гасит `liga`/`calt`/`dlig`,
 * чтобы пары вроде `->` не склеивались в один глиф. Применяется к шрифту терминала независимо от
 * выбора (для Hack безвредно, для JetBrains Mono — необходимо).
 */
const val NO_LIGATURES = "\"liga\" 0, \"calt\" 0, \"dlig\" 0"

/**
 * Стиль курсора терминала (Settings → Терминал → «Стиль курсора»): комбинация формы (DECSCUSR
 * блок/подчёркивание/черта) и мигания. Действует как ДЕФОЛТ для новой сессии — приложение внутри
 * может переопределить форму через DECSCUSR (`CSI Ps SP q`); RIS вернёт именно к выбранному стилю.
 * [id] — стабильный ключ персиста (см. desktop `main`); порядок [entries] = порядок в дропдауне.
 */
enum class TerminalCursorStyle(val shape: CursorShape, val blink: Boolean, val id: String) {
    BlockBlink(CursorShape.Block, true, "block-blink"),
    BlockSteady(CursorShape.Block, false, "block-steady"),
    UnderlineBlink(CursorShape.Underline, true, "underline-blink"),
    UnderlineSteady(CursorShape.Underline, false, "underline-steady"),
    BarBlink(CursorShape.Bar, true, "bar-blink"),
    BarSteady(CursorShape.Bar, false, "bar-steady");

    companion object {
        /** Дефолт — мигающий блок (как у xterm и как показывал прежний мок настроек). */
        val DEFAULT = BlockBlink

        /** Разобрать сохранённый [id] обратно в значение; неизвестный/`null` → [DEFAULT]. */
        fun fromId(id: String?): TerminalCursorStyle = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Глубина scrollback по умолчанию (Settings → Терминал → «Буфер прокрутки»), строк. */
const val DEFAULT_TERMINAL_SCROLLBACK = 10_000

/** Пресеты глубины scrollback (строк) для выпадающего списка «Буфер прокрутки». */
val TERMINAL_SCROLLBACK_OPTIONS: List<Int> = listOf(1_000, 5_000, 10_000, 50_000)

/**
 * Настройки терминала, применяемые к НОВОЙ сессии при её создании ([app.skerry.ui.connection.ConnectionController]):
 * глубина scrollback и стиль курсора по умолчанию. Читаются провайдером в момент connect, поэтому
 * смена настроек влияет на последующие сессии; уже открытые сессии сохраняют свой эмулятор.
 */
@Immutable
data class TerminalSessionPrefs(
    val scrollback: Int = DEFAULT_TERMINAL_SCROLLBACK,
    val cursorStyle: TerminalCursorStyle = TerminalCursorStyle.DEFAULT,
) {
    /** Эффективная глубина scrollback: пресет валидируется, иначе — дефолт эмулятора (страховка). */
    val effectiveScrollback: Int get() = scrollback.takeIf { it > 0 } ?: DEFAULT_MAX_SCROLLBACK
}

/** Внешний вид терминала, проводимый в [TerminalScreen] через [LocalTerminalAppearance]. */
@Immutable
data class TerminalAppearance(
    val font: TerminalFont = TerminalFont.DEFAULT,
    val fontSizeSp: Int = DEFAULT_TERMINAL_FONT_SIZE,
    /** Множитель высоты строки (см. [DEFAULT_TERMINAL_LINE_HEIGHT]): lineHeight = кегль × это значение. */
    val lineHeight: Float = DEFAULT_TERMINAL_LINE_HEIGHT,
    /** Межбуквенный интервал в sp (см. [DEFAULT_TERMINAL_LETTER_SPACING]). */
    val letterSpacingSp: Float = DEFAULT_TERMINAL_LETTER_SPACING,
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
