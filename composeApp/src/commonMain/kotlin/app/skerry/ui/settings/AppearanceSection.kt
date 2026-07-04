package app.skerry.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.design.Badge
import app.skerry.ui.design.D
import app.skerry.ui.design.DropdownField
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.NumberStepper
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.appearance_badge_active
import app.skerry.ui.generated.resources.appearance_default_value
import app.skerry.ui.generated.resources.appearance_font
import app.skerry.ui.generated.resources.appearance_font_size
import app.skerry.ui.generated.resources.appearance_language
import app.skerry.ui.generated.resources.appearance_letter_spacing
import app.skerry.ui.generated.resources.appearance_line_height
import app.skerry.ui.generated.resources.appearance_recent_count
import app.skerry.ui.generated.resources.appearance_recent_show
import app.skerry.ui.generated.resources.appearance_recent_show_desc
import app.skerry.ui.generated.resources.appearance_section_interface
import app.skerry.ui.generated.resources.appearance_section_terminal
import app.skerry.ui.generated.resources.appearance_subtitle
import app.skerry.ui.generated.resources.appearance_title
import app.skerry.ui.i18n.UiLanguage
import app.skerry.ui.i18n.label
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LETTER_SPACING
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LINE_HEIGHT
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_MAX
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_MIN
import app.skerry.ui.terminal.TerminalFont
import app.skerry.ui.terminal.TerminalTheme
import app.skerry.ui.terminal.TerminalThemes
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

// Секция Appearance: темы терминала, шрифт/метрики, язык интерфейса, секция RECENT.

@Composable
internal fun AppearanceSection(state: DesktopDesignState) {
    val mono = LocalFonts.current.mono
    SectionTitle(stringResource(Res.string.appearance_title), stringResource(Res.string.appearance_subtitle))
    // Карточки тем сеткой 2×N из каталога [TerminalThemes]; выбор проводится в терминал на лету.
    TerminalThemes.all.chunked(2).forEachIndexed { rowIndex, rowThemes ->
        if (rowIndex > 0) Box(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (theme in rowThemes) {
                ThemeCard(
                    theme = theme,
                    active = theme.id == state.terminalTheme.id,
                    mono = mono,
                    onClick = { state.chooseTerminalTheme(theme) },
                    modifier = Modifier.weight(1f),
                )
            }
            // Нечётный хвост — добиваем пустой ячейкой, чтобы карточка не растянулась на всю ширину.
            if (rowThemes.size == 1) Box(Modifier.weight(1f))
        }
    }
    // Раскладка по секциям, по одной настройке в полноширинной строке: слева подпись + подсказка дефолта
    // (с быстрым сбросом), справа у края — контрол. Кегль/высота/интервал — точный числовой ввод (степпер).
    SectionLabel(stringResource(Res.string.appearance_section_terminal))
    SettingRow(label = stringResource(Res.string.appearance_font)) {
        Box(Modifier.width(180.dp)) { FontPicker(state.terminalFont, onPick = state::chooseTerminalFont) }
    }
    SettingRow(
        label = stringResource(Res.string.appearance_font_size),
        hasHint = true,
        isDefault = state.terminalFontSize == DEFAULT_TERMINAL_FONT_SIZE,
        defaultText = "$DEFAULT_TERMINAL_FONT_SIZE px",
        onReset = { state.chooseTerminalFontSize(DEFAULT_TERMINAL_FONT_SIZE) },
    ) {
        NumberStepper(
            value = state.terminalFontSize.toFloat(),
            onValueChange = { state.chooseTerminalFontSize(it.roundToInt().coerceIn(TERMINAL_FONT_SIZE_MIN, TERMINAL_FONT_SIZE_MAX)) },
            step = 1f,
            format = { it.roundToInt().toString() },
            parse = { it.trim().toIntOrNull()?.toFloat() },
            suffix = "px",
        )
    }
    SettingRow(
        label = stringResource(Res.string.appearance_line_height),
        hasHint = true,
        isDefault = formatDecimal(state.terminalLineHeight, 2) == formatDecimal(DEFAULT_TERMINAL_LINE_HEIGHT, 2),
        defaultText = formatDecimal(DEFAULT_TERMINAL_LINE_HEIGHT, 2),
        onReset = { state.chooseTerminalLineHeight(DEFAULT_TERMINAL_LINE_HEIGHT) },
    ) {
        NumberStepper(
            value = state.terminalLineHeight,
            onValueChange = state::chooseTerminalLineHeight,
            step = 0.05f,
            format = { formatDecimal(it, 2) },
            parse = { it.trim().replace(',', '.').toFloatOrNull() },
            fieldWidth = 52.dp,
        )
    }
    SettingRow(
        label = stringResource(Res.string.appearance_letter_spacing),
        hasHint = true,
        isDefault = formatDecimal(state.terminalLetterSpacing, 1) == formatDecimal(DEFAULT_TERMINAL_LETTER_SPACING, 1),
        defaultText = "${formatDecimal(DEFAULT_TERMINAL_LETTER_SPACING, 1)} px",
        onReset = { state.chooseTerminalLetterSpacing(DEFAULT_TERMINAL_LETTER_SPACING) },
    ) {
        NumberStepper(
            value = state.terminalLetterSpacing,
            onValueChange = state::chooseTerminalLetterSpacing,
            step = 0.1f,
            format = { formatDecimal(it, 1) },
            parse = { it.trim().replace(',', '.').toFloatOrNull() },
            suffix = "px",
            fieldWidth = 52.dp,
        )
    }
    SectionLabel(stringResource(Res.string.appearance_section_interface))
    SettingRow(label = stringResource(Res.string.appearance_language)) {
        Box(Modifier.width(180.dp)) { LanguagePicker(state.uiLanguage, onPick = state::chooseUiLanguage) }
    }
    // Секция RECENT в сайдбаре: показывать ли её и сколько хостов (степпер виден только когда включено).
    SettingToggleRow(
        stringResource(Res.string.appearance_recent_show),
        stringResource(Res.string.appearance_recent_show_desc),
        state.showRecent,
        { state.setRecentVisible(!state.showRecent) },
    )
    if (state.showRecent) {
        SettingRow(
            label = stringResource(Res.string.appearance_recent_count),
            hasHint = true,
            isDefault = state.recentLimit == DesktopDesignState.MAX_RECENT_HOSTS,
            defaultText = DesktopDesignState.MAX_RECENT_HOSTS.toString(),
            onReset = { state.chooseRecentLimit(DesktopDesignState.MAX_RECENT_HOSTS) },
        ) {
            NumberStepper(
                value = state.recentLimit.toFloat(),
                onValueChange = { state.chooseRecentLimit(it.roundToInt()) },
                step = 1f,
                format = { it.roundToInt().toString() },
                parse = { it.trim().toIntOrNull()?.toFloat() },
                fieldWidth = 52.dp,
            )
        }
    }
}

/**
 * Полноширинная строка настройки: слева подпись, под ней (при [hasHint]) подсказка дефолта с быстрым
 * сбросом; справа на той же линии — контрол ([NumberStepper]/дропдаун).
 */
@Composable
private fun SettingRow(
    label: String,
    hasHint: Boolean = false,
    isDefault: Boolean = true,
    defaultText: String = "",
    onReset: () -> Unit = {},
    control: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Txt(label, color = D.text, size = 13.sp, weight = FontWeight.Medium)
            if (hasHint) DefaultValueHint(isDefault, defaultText, onReset)
        }
        control()
    }
}

/** Заголовок группы настроек: мелкие капсы в приглушённом цвете, с отступом сверху для отделения секций. */
@Composable
private fun SectionLabel(text: String) {
    Txt(
        text,
        color = D.faint,
        size = 11.sp,
        weight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
    )
}

/**
 * Подсказка значения по умолчанию: серый статичный текст, когда значение уже дефолтное; cyan-кликабельная
 * строка со значком сброса, когда изменено (клик возвращает к [defaultText]-значению через [onReset]).
 */
@Composable
private fun DefaultValueHint(isDefault: Boolean, defaultText: String, onReset: () -> Unit) {
    val text = stringResource(Res.string.appearance_default_value, defaultText)
    if (isDefault) {
        Txt(text, color = D.faint, size = 11.sp, modifier = Modifier.padding(top = 2.dp))
    } else {
        Row(
            Modifier.padding(top = 2.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onReset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Sym("restart_alt", size = 13.sp, color = D.cyan)
            Txt(text, color = D.cyan, size = 11.sp)
        }
    }
}

/**
 * Формат дробного значения с фиксированным числом знаков после точки (KMP-common без String.format).
 * Корректно показывает знак для отрицательных дробей с нулевой целой частью (−0.5).
 */
internal fun formatDecimal(value: Float, decimals: Int): String {
    val factor = if (decimals <= 1) 10 else 100
    val scaled = (value * factor).roundToInt()
    val whole = scaled / factor
    val frac = abs(scaled % factor).toString().padStart(decimals, '0')
    val sign = if (value < 0 && whole == 0) "-" else ""
    return "$sign$whole.$frac"
}

/** Выпадающий список языка интерфейса (System / English / Русский). */
@Composable
private fun LanguagePicker(current: UiLanguage, onPick: (UiLanguage) -> Unit) {
    DropdownField(current, UiLanguage.entries, label = { it.label() }, onPick = onPick)
}

/** Выпадающий список шрифта терминала (Hack / JetBrains Mono) — оба без лигатур. */
@Composable
private fun FontPicker(current: TerminalFont, onPick: (TerminalFont) -> Unit) {
    DropdownField(current, TerminalFont.entries, label = { it.displayName }, onPick = onPick)
}

/**
 * Карточка выбора темы терминала: мини-превью `ls -la` в РЕАЛЬНЫХ цветах [theme] (фон/текст/ANSI) —
 * так пользователь видит палитру до применения. Клик выбирает тему; активная — cyan-рамка + бейдж.
 */
@Composable
private fun ThemeCard(
    theme: TerminalTheme,
    active: Boolean,
    mono: FontFamily,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (active) D.cyan else D.cyan08, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().background(theme.background).padding(10.dp)) {
            Row { Txt("~ ", color = theme.ansi[2], size = 10.sp, font = mono); Txt("ls -la", color = theme.foreground, size = 10.sp, font = mono) }
            Row { Txt("drwxr-xr-x ", color = theme.ansi[6], size = 10.sp, font = mono); Txt("src", color = theme.ansi[4], size = 10.sp, font = mono) }
            Row { Txt("-rw-r--r-- ", color = theme.ansi[8], size = 10.sp, font = mono); Txt(".env", color = theme.ansi[3], size = 10.sp, font = mono) }
        }
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Txt(theme.displayName, color = D.text, size = 11.5.sp, weight = FontWeight.Medium)
            if (active) Badge(stringResource(Res.string.appearance_badge_active), bg = D.cyan14, fg = D.cyanBright, radius = 3, size = 9.sp)
        }
    }
}
