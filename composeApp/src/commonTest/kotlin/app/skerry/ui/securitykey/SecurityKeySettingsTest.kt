package app.skerry.ui.securitykey

import app.skerry.ui.app.DesktopSettingsState
import app.skerry.ui.app.MobileDesignState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityKeySettingsTest {
    @Test fun desktopIsLocalOptInAndRestores() {
        val changes = mutableListOf<Boolean>()
        val settings = DesktopSettingsState(onSecurityKeysEnabledChange = changes::add)
        assertFalse(settings.securityKeysEnabled)
        settings.toggleSecurityKeysEnabled()
        settings.toggleSecurityKeysEnabled()
        assertEquals(listOf(true, false), changes)
        assertTrue(DesktopSettingsState(initialSecurityKeysEnabled = true).securityKeysEnabled)
    }
    @Test fun mobileIsLocalOptInAndRestores() {
        val changes = mutableListOf<Boolean>()
        val settings = MobileDesignState(onSecurityKeysEnabledChange = changes::add)
        assertFalse(settings.securityKeysEnabled)
        settings.toggleSecurityKeysEnabled()
        settings.toggleSecurityKeysEnabled()
        assertEquals(listOf(true, false), changes)
        assertTrue(MobileDesignState(initialSecurityKeysEnabled = true).securityKeysEnabled)
    }
}
