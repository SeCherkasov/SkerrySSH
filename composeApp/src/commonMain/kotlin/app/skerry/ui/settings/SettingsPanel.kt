package app.skerry.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalFeatures
import app.skerry.ui.app.LocalSecurityLog
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.app.SettingsTab
import app.skerry.ui.design.D
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine
import app.skerry.ui.design.consumeClicks
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_nav_header
import app.skerry.ui.generated.resources.shtail_nav_about
import app.skerry.ui.generated.resources.shtail_nav_account
import app.skerry.ui.generated.resources.shtail_nav_ai
import app.skerry.ui.generated.resources.shtail_nav_appearance
import app.skerry.ui.generated.resources.shtail_nav_keyboard
import app.skerry.ui.generated.resources.shtail_nav_security
import app.skerry.ui.generated.resources.shtail_nav_sync
import app.skerry.ui.generated.resources.shtail_nav_terminal
import app.skerry.ui.vault.VaultGateController
import org.jetbrains.compose.resources.stringResource

/** Settings panel (760x560 modal): 200dp nav + content with 8 sections (AI/Appearance/.../About). */
@Composable
fun SettingsPanel(state: DesktopDesignState) {
    // Live security controller over shared vault/biometrics/log: password change, biometrics
    // toggle, event reading. null when there is no vault (mock/preview). reload re-reads the
    // log/label after actions. changePwOpen is lifted to overlay level: the dialog is drawn over
    // the whole settings card.
    val securityController = rememberSecurityController()
    var securityReload by remember { mutableStateOf(0) }
    var changePwOpen by remember { mutableStateOf(false) }
    ModalScrim(onDismiss = state::closeSettings, scrimColor = Color(0xA6060E16)) {
        Box(
            Modifier
                .width(760.dp)
                .height(560.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(D.surfaceDeep)
                .border(1.dp, D.cyan14, RoundedCornerShape(12.dp))
                .consumeClicks(),
        ) {
        Row(Modifier.fillMaxSize()) {
            // AI tab is visible when the AI feature flag is on or a live assistant controller is
            // connected (a real BYOK provider behind the vault gate). Otherwise the tab is hidden and
            // the default selection (state.settingsTab = AI) falls back to Account.
            val features = LocalFeatures.current
            val aiVisible = features.ai || LocalAi.current != null
            val effectiveTab = if (state.settingsTab == SettingsTab.AI && !aiVisible) SettingsTab.Account else state.settingsTab
            Column(Modifier.width(200.dp).fillMaxHeight().background(Color(0x33000000)).padding(horizontal = 8.dp, vertical = 16.dp)) {
                Txt(stringResource(Res.string.settings_nav_header), color = D.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
                SETTINGS_NAV.filter { aiVisible || it.tab != SettingsTab.AI }.forEach { item ->
                    NavRow(item, active = effectiveTab == item.tab, onClick = { state.showSettingsTab(item.tab) })
                }
            }
            VLine(D.line)
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 22.dp)) {
                when (effectiveTab) {
                    SettingsTab.AI -> AiSection(state)
                    SettingsTab.Appearance -> AppearanceSection(state)
                    SettingsTab.Terminal -> TerminalSection(state)
                    SettingsTab.Account -> AccountSection(state)
                    SettingsTab.Sync -> SyncSection(state)
                    SettingsTab.Security -> SecuritySection(
                        state = state,
                        controller = securityController,
                        reload = securityReload,
                        onChangeMasterPassword = { changePwOpen = true },
                        onBiometricToggled = { securityReload++ },
                    )
                    SettingsTab.Keyboard -> KeyboardSection()
                    SettingsTab.About -> AboutSection()
                }
            }
        }
        // Close button: overlay in the top-right corner of the card (duplicates scrim click/Esc).
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = state::closeSettings)
                .padding(8.dp),
        ) {
            Sym(name = "close", size = 18.sp, color = D.dim)
        }
        // Change master password dialog: overlay over the whole settings card (outside the scroll).
        if (changePwOpen && securityController != null) {
            ChangeMasterPasswordDialog(
                controller = securityController,
                onClose = { changePwOpen = false },
                onChanged = { securityReload++ },
            )
        }
        }
    }
}

/**
 * Builds a live [VaultGateController] over the shared vault/biometrics/log (from CompositionLocal)
 * for the Security section (password change, biometrics toggle, log reading). A separate instance
 * from the gate: events are written to the shared [SecurityLog], gate navigation is not needed here.
 * `null` when there is no vault (mock/preview): the section renders a neutral view.
 */
@Composable
private fun rememberSecurityController(): VaultGateController? {
    val vault = LocalVault.current
    val biometrics = LocalVaultBiometrics.current
    val log = LocalSecurityLog.current
    return remember(vault, biometrics, log) {
        vault?.let { VaultGateController(it, biometrics, securityLog = log) }
    }
}

@Composable
private fun NavRow(item: SettingsNavItem, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 1.dp).clip(RoundedCornerShape(6.dp)).background(if (active) D.cyan10 else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(item.icon, size = 16.sp, color = if (active) D.cyanBright else D.dim)
        Txt(item.tab.navLabel(), color = if (active) D.cyanBright else D.dim, size = 12.5.sp)
    }
}

/** Localized label for a settings navigation item. */
@Composable
private fun SettingsTab.navLabel(): String = when (this) {
    SettingsTab.Account -> stringResource(Res.string.shtail_nav_account)
    SettingsTab.AI -> stringResource(Res.string.shtail_nav_ai)
    SettingsTab.Sync -> stringResource(Res.string.shtail_nav_sync)
    SettingsTab.Security -> stringResource(Res.string.shtail_nav_security)
    SettingsTab.Appearance -> stringResource(Res.string.shtail_nav_appearance)
    SettingsTab.Terminal -> stringResource(Res.string.shtail_nav_terminal)
    SettingsTab.Keyboard -> stringResource(Res.string.shtail_nav_keyboard)
    SettingsTab.About -> stringResource(Res.string.shtail_nav_about)
}
