package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.more_experimental
import app.skerry.ui.generated.resources.settings_experimental_jump_shell
import app.skerry.ui.generated.resources.settings_experimental_jump_shell_desc
import app.skerry.ui.generated.resources.settings_experimental_subtitle
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/** More → Experimental push screen (parity with the desktop [app.skerry.ui.settings.ExperimentalSection]). */
@Composable
fun MobileExperimentalScreen(state: MobileDesignState) {
    Box(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            MobilePushHeader(stringResource(Res.string.more_experimental), onBack = state::pop)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
                Txt(
                    stringResource(Res.string.settings_experimental_subtitle),
                    color = Skerry.colors.dim,
                    size = 12.5.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
                MobileToggleRow(
                    title = stringResource(Res.string.settings_experimental_jump_shell),
                    desc = stringResource(Res.string.settings_experimental_jump_shell_desc),
                    on = state.jumpViaShellOffered,
                    onToggle = state::toggleJumpViaShellOffered,
                )
                Spacer(Modifier.height(96.dp))
            }
        }
    }
}
