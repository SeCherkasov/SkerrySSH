package app.skerry.ui.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.DesktopSettingsState
import app.skerry.ui.app.LocalManualLockOffered
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_kb_lock
import app.skerry.ui.generated.resources.shell_lock
import app.skerry.ui.settings.KeyboardSection
import app.skerry.ui.theme.SkerryTheme
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The manual lock on screen follows [LocalManualLockOffered] (issue #398): a trusted device reopens
 * the vault at once, so the title-bar pill and its Settings → Keyboard row go, and come back without it.
 */
@OptIn(ExperimentalTestApi::class)
class ManualLockOfferedTest {

    @Test
    fun `the title bar offers the lock only while a lock is offered`() {
        assertEquals(1, shown(offered = true, Res.string.shell_lock) { TitleBar(DesktopDesignState(DesktopSettingsState()), onLock = {}) })
        assertEquals(0, shown(offered = false, Res.string.shell_lock) { TitleBar(DesktopDesignState(DesktopSettingsState()), onLock = {}) })
    }

    @Test
    fun `settings list the lock shortcut only while a lock is offered`() {
        assertEquals(1, shown(offered = true, Res.string.settings_kb_lock) { KeyboardSection() })
        assertEquals(0, shown(offered = false, Res.string.settings_kb_lock) { KeyboardSection() })
    }

    private fun shown(offered: Boolean, label: StringResource, content: @Composable () -> Unit): Int {
        var count = -1
        runComposeUiTest {
            setContent {
                SkerryTheme {
                    CompositionLocalProvider(
                        LocalManualLockOffered provides offered,
                        LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                    ) { content() }
                }
            }
            count = count(label)
        }
        return count
    }

    private fun ComposeUiTest.count(label: StringResource) = onAllNodesWithText(string(label)).fetchSemanticsNodes().size
}
