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
import androidx.compose.runtime.collectAsState
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
import app.skerry.ui.app.LocalSync
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.app.SettingsTab
import app.skerry.ui.design.HLine
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine
import app.skerry.ui.design.consumeClicks
import app.skerry.ui.sync.SyncStatus
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.appearance_subtitle
import app.skerry.ui.generated.resources.settings_ai_live_subtitle
import app.skerry.ui.generated.resources.settings_keyboard_subtitle
import app.skerry.ui.generated.resources.settings_nav_header
import app.skerry.ui.generated.resources.settings_security_subtitle
import app.skerry.ui.generated.resources.settings_sync_subtitle
import app.skerry.ui.generated.resources.settings_terminal_subtitle
import app.skerry.ui.generated.resources.shtail_nav_about
import app.skerry.ui.generated.resources.shtail_nav_ai
import app.skerry.ui.generated.resources.shtail_nav_appearance
import app.skerry.ui.generated.resources.shtail_nav_keyboard
import app.skerry.ui.generated.resources.shtail_nav_security
import app.skerry.ui.generated.resources.shtail_nav_sync
import app.skerry.ui.generated.resources.shtail_nav_terminal
import app.skerry.ui.vault.VaultGateController
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

// Height of the content pane's sticky header strip: tall enough to clear the top-right close button,
// so the divider line lands just below it and section content scrolls beneath the band.
private val SETTINGS_HEADER_HEIGHT = 44.dp

/** Settings panel (760x560 modal): 200dp nav + content with 7 sections (AI/Sync/.../About). */
@Composable
fun SettingsPanel(state: DesktopDesignState) {
    // Live security controller over shared vault/biometrics/log: password change, biometrics
    // toggle, event reading. null when there is no vault (mock/preview). reload re-reads the
    // log/label after actions. changePwOpen is lifted to overlay level: the dialog is drawn over
    // the whole settings card.
    val securityController = rememberSecurityController()
    val sync = LocalSync.current
    // Sync configured → the password is the account password (issue #32): show/host the account
    // rotation dialog instead of the local-only master-password one. Derived from the status flow
    // (reactive, no per-recomposition disk read): a saved link means status is never Disabled.
    val syncStatus = sync?.status?.collectAsState()?.value
    val syncConfigured = syncStatus != null && syncStatus !is SyncStatus.Disabled
    var securityReload by remember { mutableStateOf(0) }
    var changePwOpen by remember { mutableStateOf(false) }
    var changeAccountPwOpen by remember { mutableStateOf(false) }
    ModalScrim(onDismiss = state::closeSettings, scrimColor = Skerry.colors.modalScrim) {
        Box(
            Modifier
                .width(760.dp)
                .height(560.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Skerry.colors.surfaceDeep)
                .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                .consumeClicks(),
        ) {
        Row(Modifier.fillMaxSize()) {
            // AI tab is visible when the AI feature flag is on or a live assistant controller is
            // connected (a real BYOK provider behind the vault gate). Otherwise the tab is hidden and
            // the default selection (state.settingsTab = AI) falls back to Sync.
            val features = LocalFeatures.current
            val aiVisible = features.ai || LocalAi.current != null
            val effectiveTab = if (state.settingsTab == SettingsTab.AI && !aiVisible) SettingsTab.Sync else state.settingsTab
            Column(Modifier.width(200.dp).fillMaxHeight().background(Skerry.colors.card).padding(horizontal = 8.dp, vertical = 16.dp)) {
                Txt(stringResource(Res.string.settings_nav_header), color = Skerry.colors.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
                SETTINGS_NAV.filter { aiVisible || it.tab != SettingsTab.AI }.forEach { item ->
                    NavRow(item, active = effectiveTab == item.tab, onClick = { state.showSettingsTab(item.tab) })
                }
            }
            VLine(Skerry.colors.line)
            // Content pane with a static header strip: the section title sits above a divider line
            // (aligned under the close button) and stays put; section content scrolls beneath the
            // line and disappears behind the opaque band. The About tab has its own centered layout
            // (logo/version), so it opts out of the header and keeps the plain top padding.
            val hasHeader = effectiveTab != SettingsTab.About
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 26.dp)
                        .padding(top = if (hasHeader) SETTINGS_HEADER_HEIGHT + 14.dp else 22.dp, bottom = 22.dp),
                ) {
                    when (effectiveTab) {
                        SettingsTab.AI -> AiSection(state)
                        SettingsTab.Appearance -> AppearanceSection(state)
                        SettingsTab.Terminal -> TerminalSection(state)
                        SettingsTab.Sync -> SyncSection(state)
                        SettingsTab.Security -> SecuritySection(
                            state = state,
                            controller = securityController,
                            reload = securityReload,
                            syncConfigured = syncConfigured,
                            onChangeMasterPassword = { changePwOpen = true },
                            onChangeAccountPassword = { changeAccountPwOpen = true },
                            onBiometricToggled = { securityReload++ },
                        )
                        SettingsTab.Keyboard -> KeyboardSection()
                        SettingsTab.About -> AboutSection()
                    }
                }
                // Static header: the section description over an opaque band (card background) the
                // scrolled content hides behind, capped by the divider line under the close button.
                // Declared after the scroll content so it draws on top; the close button (card-level
                // TopEnd overlay) stays above both. The band holds the subtitle, not the section
                // title — the title already lives in the nav and would be a duplicate here.
                if (hasHeader) {
                    Column(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
                        Box(
                            Modifier.fillMaxWidth().height(SETTINGS_HEADER_HEIGHT - 1.dp).background(Skerry.colors.surfaceDeep).padding(horizontal = 26.dp).padding(end = 20.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Txt(effectiveTab.headerSubtitle(), color = Skerry.colors.text, size = 13.5.sp, weight = FontWeight.Medium, maxLines = 1)
                        }
                        HLine()
                    }
                }
            }
        }
        // Close button: overlay in the top-right corner, vertically centered within the header strip
        // height so it lines up with the section title and sits above the divider. Height is tied to
        // SETTINGS_HEADER_HEIGHT (not a magic offset) so it can't desync from the header. Only pointer
        // way to close.
        Box(
            Modifier.align(Alignment.TopEnd).height(SETTINGS_HEADER_HEIGHT).padding(end = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = state::closeSettings).padding(8.dp),
            ) {
                Sym(name = "close", size = 18.sp, color = Skerry.colors.dim)
            }
        }
        // Change master password dialog: overlay over the whole settings card (outside the scroll).
        if (changePwOpen && securityController != null) {
            ChangeMasterPasswordDialog(
                controller = securityController,
                onClose = { changePwOpen = false },
                onChanged = { securityReload++ },
            )
        }
        // Change account (sync) password dialog — shown when sync is configured (issue #32).
        if (changeAccountPwOpen && sync != null) {
            ChangeAccountPasswordDialog(
                sync = sync,
                onClose = { changeAccountPwOpen = false },
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
        Modifier.fillMaxWidth().padding(bottom = 1.dp).clip(RoundedCornerShape(6.dp)).background(if (active) Skerry.colors.cyan10 else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(item.icon, size = 16.sp, color = if (active) Skerry.colors.cyanBright else Skerry.colors.dim)
        Txt(item.tab.navLabel(), color = if (active) Skerry.colors.cyanBright else Skerry.colors.dim, size = 12.5.sp)
    }
}

/** Localized header-band description for a tab (the nav label holds the section title). */
@Composable
private fun SettingsTab.headerSubtitle(): String = when (this) {
    SettingsTab.AI -> stringResource(Res.string.settings_ai_live_subtitle)
    SettingsTab.Sync -> stringResource(Res.string.settings_sync_subtitle)
    SettingsTab.Security -> stringResource(Res.string.settings_security_subtitle)
    SettingsTab.Appearance -> stringResource(Res.string.appearance_subtitle)
    SettingsTab.Terminal -> stringResource(Res.string.settings_terminal_subtitle)
    SettingsTab.Keyboard -> stringResource(Res.string.settings_keyboard_subtitle)
    SettingsTab.About -> "" // About opts out of the header band
}

/** Localized label for a settings navigation item. */
@Composable
private fun SettingsTab.navLabel(): String = when (this) {
    SettingsTab.AI -> stringResource(Res.string.shtail_nav_ai)
    SettingsTab.Sync -> stringResource(Res.string.shtail_nav_sync)
    SettingsTab.Security -> stringResource(Res.string.shtail_nav_security)
    SettingsTab.Appearance -> stringResource(Res.string.shtail_nav_appearance)
    SettingsTab.Terminal -> stringResource(Res.string.shtail_nav_terminal)
    SettingsTab.Keyboard -> stringResource(Res.string.shtail_nav_keyboard)
    SettingsTab.About -> stringResource(Res.string.shtail_nav_about)
}
