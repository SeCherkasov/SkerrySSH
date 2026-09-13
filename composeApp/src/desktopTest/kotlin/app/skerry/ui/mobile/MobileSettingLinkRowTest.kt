package app.skerry.ui.mobile

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_change
import app.skerry.ui.generated.resources.settings_change_pw_title
import app.skerry.ui.generated.resources.settings_security_master_password
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The settings row that is a place to go rather than a switch, shared by the Security and keep-alive
 * screens.
 *
 * Two properties are worth pinning because both were wrong in the hand-rolled copies this replaced:
 * the click belongs to the whole row (a bare action word is under the 24dp target floor, and a
 * clickable leaf takes the row's label into its own merged node, leaving a screen reader with
 * "Change, button" and no idea what it changes), and a row with nothing behind it must lose the
 * click, not merely dim the word.
 */
@OptIn(ExperimentalTestApi::class)
class MobileSettingLinkRowTest {

    @Test
    fun theWholeRowCarriesTheClickAndItsLabel() {
        var taps = 0
        runForm({ MobileSettingLinkRow(LABEL, SUBTITLE, ACTION, onAction = { taps++ }) }) {
            // Found by the label, clicked at the row's centre — the word alone is not the target.
            onNodeWithText(LABEL).assertHasClickAction().performClick()
            assertEquals(1, taps)
            onNodeWithText(SUBTITLE).assertIsDisplayed()
            onNodeWithText(ACTION).assertIsDisplayed()
        }
    }

    @Test
    fun aRowWithNothingBehindItTakesNoClick() {
        runForm({ MobileSettingLinkRow(LABEL, SUBTITLE, ACTION, onAction = null) }) {
            onNodeWithText(LABEL).assertHasNoClickAction()
            onNodeWithText(ACTION).assertHasNoClickAction()
        }
    }

    /** The note variant: a setting that lives in the firmware has no action word to offer at all. */
    @Test
    fun theNoteRowOffersNoActionWord() {
        runForm({ MobileSettingNoteRow(LABEL, SUBTITLE) }) {
            onNodeWithText(LABEL).assertIsDisplayed().assertHasNoClickAction()
            onNodeWithText(SUBTITLE).assertIsDisplayed()
        }
    }

    /**
     * The Security screen draws its master-password row through the same primitive, and without a
     * vault behind it the row has to be inert rather than open a dialog over a locked app.
     */
    @Test
    fun theSecurityScreenRowIsInertWithoutAVault() {
        val state = MobileDesignState()
        runForm({ MobileSecurityScreen(state) }) {
            val label = string(Res.string.settings_security_master_password)
            onNodeWithText(label).performScrollTo().assertIsDisplayed().assertHasNoClickAction()
            assertEquals(
                1,
                onAllNodesWithText(string(Res.string.settings_change)).fetchSemanticsNodes().size,
                "the action word is drawn, only dimmed and unclickable",
            )
        }
    }

    /**
     * And with a vault behind it the same row reaches the handler it is named for: the conversion to
     * the shared primitive is the moment a rewired click would go unnoticed.
     */
    @Test
    fun theSecurityScreenRowOpensThePasswordDialog() {
        val vault = UnlockedVault(runBlocking { initializeVaultCrypto(); IonspinVaultCrypto().newDataKey() })
        val state = MobileDesignState()
        runForm({
            CompositionLocalProvider(LocalVault provides vault) { MobileSecurityScreen(state) }
        }) {
            onNodeWithText(string(Res.string.settings_security_master_password)).performScrollTo().performClick()
            waitForIdle()
            onNodeWithText(string(Res.string.settings_change_pw_title)).assertIsDisplayed()
        }
    }

    private companion object {
        const val LABEL = "Autostart"
        const val SUBTITLE = "Allow the app to start on its own"
        const val ACTION = "Open"
    }
}
