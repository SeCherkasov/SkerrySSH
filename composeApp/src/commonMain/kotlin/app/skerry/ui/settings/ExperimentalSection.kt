package app.skerry.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_experimental_jump_shell
import app.skerry.ui.generated.resources.settings_experimental_jump_shell_desc
import org.jetbrains.compose.resources.stringResource

/** Settings → Experimental: features under test, each off until this device turns it on. */
@Composable
internal fun ExperimentalSection(state: DesktopDesignState) {
    Column(Modifier.fillMaxWidth()) {
        // Offers "type ssh on the jump host" in the connection form, and lets such profiles connect.
        SettingToggleRow(
            stringResource(Res.string.settings_experimental_jump_shell),
            stringResource(Res.string.settings_experimental_jump_shell_desc),
            on = state.settings.jumpViaShellOffered,
            onToggle = state.settings::toggleJumpViaShellOffered,
        )
    }
}
