package app.skerry.ui.mobile

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.skerry.ui.app.LocalKeepAlivePower
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.desktop.runForm
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.keepalive_autostart
import app.skerry.ui.generated.resources.keepalive_battery
import app.skerry.ui.generated.resources.keepalive_battery_off
import app.skerry.ui.generated.resources.keepalive_battery_on
import app.skerry.ui.generated.resources.keepalive_device
import app.skerry.ui.generated.resources.keepalive_open
import app.skerry.ui.generated.resources.keepalive_recents
import app.skerry.ui.generated.resources.keepalive_title
import app.skerry.ui.generated.resources.keepalive_vendor_samsung
import app.skerry.ui.generated.resources.keepalive_wakelock
import app.skerry.ui.generated.resources.keepalive_wakelock_deferred
import app.skerry.ui.keepalive.KeepAlivePower
import app.skerry.ui.keepalive.KeepAliveVendor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The keep-alive screen against a fake device.
 *
 * What is worth pinning here is everything the original of this feature got wrong: a switch that
 * changes nothing outside the composition, a status line read once and never again after the person
 * has been sent to a system page to change it, and rows offering to open a page the device does not
 * have.
 */
@OptIn(ExperimentalTestApi::class)
class MobileKeepAliveScreenTest {

    @Test
    fun theWakeLockSwitchWritesThroughToTheDevice() {
        val power = FakeKeepAlivePower(KeepAliveVendor.Samsung)
        keepAliveScreen(power) {
            onSwitch().assertIsOff()
            onSwitch().performClick()
            assertTrue(power.wakeLockEnabled, "the switch must reach the device, not just the UI state")
            onSwitch().assertIsOn()
            onSwitch().performClick()
            assertFalse(power.wakeLockEnabled)
        }
    }

    /** The switch shows what the device already holds, not a fresh default, when the screen opens. */
    @Test
    fun theSwitchOpensOnTheStoredValue() {
        val power = FakeKeepAlivePower(KeepAliveVendor.Samsung).apply { wakeLockEnabled = true }
        keepAliveScreen(power) { onSwitch().assertIsOn() }
    }

    @Test
    fun theBatteryRowOpensTheSystemList() {
        val power = FakeKeepAlivePower(KeepAliveVendor.Samsung)
        keepAliveScreen(power) {
            // Battery is the first of the link rows on every device.
            onAllNodesWithText(string(Res.string.keepalive_open))[0].performScrollTo().performClick()
            assertEquals(1, power.batteryPagesOpened)
            assertEquals(0, power.autostartPagesOpened)
            assertEquals(0, power.detailPagesOpened)
        }
    }

    /**
     * A ROM with no autostart list gets no autostart row: the button behind it can only open this
     * app's details page, which is not what the row would be promising.
     */
    @Test
    fun theAutostartRowIsDrawnOnlyForARomThatHasOne() {
        keepAliveScreen(FakeKeepAlivePower(KeepAliveVendor.Xiaomi)) {
            onNodeWithText(string(Res.string.keepalive_autostart)).performScrollTo().assertIsDisplayed()
            assertEquals(2, linkCount())
        }
        keepAliveScreen(FakeKeepAlivePower(KeepAliveVendor.Samsung)) {
            assertEquals(0, onAllNodesWithText(string(Res.string.keepalive_autostart)).fetchSemanticsNodes().size)
            assertEquals(1, linkCount())
        }
    }

    /** And it opens that page, not one of the other two the screen also knows how to open. */
    @Test
    fun theAutostartRowOpensTheRomsOwnPage() {
        val power = FakeKeepAlivePower(KeepAliveVendor.Xiaomi)
        keepAliveScreen(power) {
            onAllNodesWithText(string(Res.string.keepalive_open))[1].performScrollTo().performClick()
            assertEquals(1, power.autostartPagesOpened)
            assertEquals(0, power.batteryPagesOpened)
            assertEquals(0, power.detailPagesOpened)
        }
    }

    /**
     * The recents steps live in the ROM's own security app and in the task switcher, neither of
     * which this app can open. A button landing on the app details page under those words would be
     * a lie, so the row offers none.
     */
    @Test
    fun theRecentsRowOffersNothingToOpen() {
        val power = FakeKeepAlivePower(KeepAliveVendor.Samsung)
        keepAliveScreen(power) {
            onNodeWithText(string(Res.string.keepalive_recents)).performScrollTo()
                .assertIsDisplayed().assertHasNoClickAction()
            assertEquals(1, linkCount(), "battery is the only row with a page behind it on a Samsung")
        }
    }

    /**
     * The whole row carries the click, not the action word alone: a bare word is under the 24dp
     * target floor, and a clickable leaf swallows the row's label into its own merged node, leaving
     * a screen reader with identical "Open" stops it cannot tell apart.
     */
    @Test
    fun theActionAndItsLabelAreOneStop() {
        val power = FakeKeepAlivePower(KeepAliveVendor.Samsung)
        keepAliveScreen(power) {
            onNodeWithText(string(Res.string.keepalive_battery)).performScrollTo().assertHasClickAction()
        }
    }

    /**
     * A refused hand-off to the running service must not leave the switch reading "on" over sessions
     * that never got the lock.
     */
    @Test
    fun aChangeTheRunningSessionDidNotGetIsSaidSo() {
        keepAliveScreen(FakeKeepAlivePower(KeepAliveVendor.Samsung, delivers = false)) {
            assertEquals(0, deferredNotes())
            onSwitch().performClick()
            onNodeWithText(string(Res.string.keepalive_wakelock_deferred)).performScrollTo()
                .assertIsDisplayed()
                // Drawn for the eye only: read out as well, the sentence would be heard twice, once
                // from this line and once from the announcer that carries it.
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
            // And it does reach someone who is not looking at the screen.
            onNodeWithContentDescription(string(Res.string.keepalive_wakelock_deferred)).assertIsDisplayed()
        }
        keepAliveScreen(FakeKeepAlivePower(KeepAliveVendor.Samsung)) {
            onSwitch().performClick()
            assertEquals(0, deferredNotes(), "the change reached the session; there is nothing to warn about")
        }
    }

    /**
     * The exemption is granted in a system page we navigated away to, so the status has to be read
     * again on the way back. Read once at composition, the screen keeps telling a person who has
     * just allowed it that the system may still suspend the app.
     */
    @Test
    fun theBatteryStatusIsReadAgainWhenTheScreenComesBack() {
        val power = FakeKeepAlivePower(KeepAliveVendor.Samsung)
        val owner = TestLifecycleOwner()
        keepAliveScreen(power, owner) {
            onNodeWithText(string(Res.string.keepalive_battery_off)).performScrollTo().assertIsDisplayed()
            // What the trip to the system page changes.
            power.exempt = true
            runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            waitForIdle()
            onNodeWithText(string(Res.string.keepalive_battery_on)).performScrollTo().assertIsDisplayed()
        }
    }

    /** Preview/offscreen: the screen draws, and nothing on it pretends to be configurable. */
    @Test
    fun withoutAPowerHookEveryRowIsInert() {
        keepAliveScreen(power = null) {
            assertEquals(0, linkCount())
            onSwitch().performClick()
            onSwitch().assertIsOff()
            assertEquals(
                0,
                onAllNodesWithText(string(Res.string.keepalive_device, "")).fetchSemanticsNodes().size,
                "no device to name, so no dangling \"Detected: \" line",
            )
        }
    }

    /**
     * `Build.MANUFACTURER` is whatever the firmware wrote, and a custom ROM writes what it likes. The
     * name is drawn in the middle of a list of instructions, so a bidi override in it would rewrite
     * the lines around it.
     */
    @Test
    fun anUnnamedFamilyDrawsASanitizedManufacturer() {
        val raw = "Ace\u202Ecorp"
        keepAliveScreen(FakeKeepAlivePower(KeepAliveVendor.Other, manufacturer = raw)) {
            onNodeWithText(string(Res.string.keepalive_device, untrustedLabel(raw)))
                .performScrollTo().assertIsDisplayed()
            assertEquals(
                0,
                onAllNodesWithText(string(Res.string.keepalive_device, raw)).fetchSemanticsNodes().size,
                "the raw manufacturer string reached the screen unfiltered",
            )
        }
    }

    /**
     * And it draws with no lifecycle owner at all. `LocalLifecycleOwner` has no default value, so a
     * screen that reads it unconditionally throws in the offscreen mobile renderer, which provides
     * none — the More tab and this route would stop producing a PNG.
     */
    @Test
    fun theScreenDrawsWithoutALifecycleOwner() {
        val state = MobileDesignState()
        runForm({ MobileKeepAliveScreen(state) }) {
            onNodeWithText(string(Res.string.keepalive_title)).assertIsDisplayed()
        }
    }

    /** With a known one, the line names the family rather than the raw manufacturer string. */
    @Test
    fun theDeviceLineNamesTheDetectedFamily() {
        keepAliveScreen(FakeKeepAlivePower(KeepAliveVendor.Samsung)) {
            val expected = string(Res.string.keepalive_device, string(Res.string.keepalive_vendor_samsung))
            onNodeWithText(expected).performScrollTo().assertIsDisplayed()
        }
    }

    private fun ComposeUiTest.deferredNotes() =
        onAllNodesWithText(string(Res.string.keepalive_wakelock_deferred)).fetchSemanticsNodes().size

    private fun ComposeUiTest.onSwitch() =
        onNodeWithContentDescription(string(Res.string.keepalive_wakelock)).performScrollTo()

    /** The "Open" words that actually carry a click — an inert row draws the word without one. */
    private fun ComposeUiTest.linkCount() =
        onAllNodesWithText(string(Res.string.keepalive_open)).filter(hasClickAction()).fetchSemanticsNodes().size

    private fun keepAliveScreen(
        power: KeepAlivePower?,
        owner: TestLifecycleOwner = TestLifecycleOwner(),
        body: ComposeUiTest.() -> Unit,
    ) {
        // Hoisted out of the composable: built inside, a recomposition would hand the screen a new one.
        val state = MobileDesignState()
        runForm({
            CompositionLocalProvider(
                LocalLifecycleOwner provides owner,
                LocalKeepAlivePower provides power,
            ) {
                MobileKeepAliveScreen(state)
            }
        }) { body() }
    }
}

/** A lifecycle the test drives itself, to replay the return from a system settings page. */
private class TestLifecycleOwner : LifecycleOwner {
    val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry

    init {
        // Started, not resumed: the resume is the event under test.
        registry.currentState = Lifecycle.State.STARTED
    }
}
