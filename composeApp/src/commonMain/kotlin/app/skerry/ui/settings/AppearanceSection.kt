package app.skerry.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.design.DropdownField
import app.skerry.ui.design.NumberStepper
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.appearance_language
import app.skerry.ui.generated.resources.appearance_recent_count
import app.skerry.ui.generated.resources.appearance_recent_show
import app.skerry.ui.generated.resources.appearance_recent_show_desc
import app.skerry.ui.generated.resources.appearance_subtitle
import app.skerry.ui.i18n.UiLanguage
import app.skerry.ui.i18n.label
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

// Appearance section: UI language and the RECENT section of the sidebar. Everything
// terminal-specific (theme, font, metrics) lives in [TerminalSection].

@Composable
internal fun AppearanceSection(state: DesktopDesignState) {
    SectionSubtitle(stringResource(Res.string.appearance_subtitle))
    SettingRow(label = stringResource(Res.string.appearance_language)) {
        Box(Modifier.width(180.dp)) { LanguagePicker(state.uiLanguage, onPick = state::chooseUiLanguage) }
    }
    // RECENT section in the sidebar: whether to show it and how many hosts. The count is a
    // sub-setting of the toggle (indented, attached right below) so it reads as one block.
    SettingToggleRow(
        stringResource(Res.string.appearance_recent_show),
        stringResource(Res.string.appearance_recent_show_desc),
        state.showRecent,
        { state.setRecentVisible(!state.showRecent) },
    )
    if (state.showRecent) {
        SettingRow(
            label = stringResource(Res.string.appearance_recent_count),
            modifier = Modifier.padding(start = 14.dp),
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

/** UI language dropdown (System / English / Russian). */
@Composable
private fun LanguagePicker(current: UiLanguage, onPick: (UiLanguage) -> Unit) {
    DropdownField(current, UiLanguage.entries, label = { it.label() }, onPick = onPick)
}
