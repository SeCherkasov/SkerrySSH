package app.skerry.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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

/** Панель настроек (модалка 760×560): nav 200dp + контент с 8 секциями (AI/Appearance/…/About). */
@Composable
fun SettingsPanel(state: DesktopDesignState) {
    // Живой контроллер безопасности (поверх общих vault/биометрии/журнала): смена пароля, тумблер
    // биометрии, чтение событий. null — мок/превью без vault. reload перечитывает журнал/метку после
    // действий. changePwOpen поднят на уровень оверлея: диалог рисуется поверх всей карточки настроек.
    val securityController = rememberSecurityController()
    var securityReload by remember { mutableStateOf(0) }
    var changePwOpen by remember { mutableStateOf(false) }
    ModalScrim(onDismiss = state::closeSettings, scrimColor = Color(0xA6060E16)) {
        Row(
            Modifier
                .width(760.dp)
                .height(560.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(D.surfaceDeep)
                .border(1.dp, D.cyan14, RoundedCornerShape(12.dp))
                .consumeClicks(),
        ) {
            // AI-таб виден, когда либо включён флаг незавершённых AI-поверхностей, либо подключён
            // живой контроллер ассистента (реальный BYOK-провайдер за гейтом vault). Иначе таб скрыт,
            // а дефолтный выбор (state.settingsTab = AI, как в прототипе) проецируется на Account.
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
        // Диалог смены мастер-пароля — оверлей поверх всей карточки настроек (не внутри скролла).
        if (changePwOpen && securityController != null) {
            ChangeMasterPasswordDialog(
                controller = securityController,
                onClose = { changePwOpen = false },
                onChanged = { securityReload++ },
            )
        }
    }
}

/**
 * Построить живой [VaultGateController] поверх общих vault/биометрии/журнала (из CompositionLocal) —
 * для раздела Безопасность (смена пароля, тумблер биометрии, чтение журнала). Отдельный инстанс от
 * гейта: события пишутся в ОБЩИЙ [SecurityLog] (консистентны на уровне файла), навигация гейта здесь
 * не нужна. `null` — мок/превью без vault: секция рисует нейтральный вид.
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

/** Локализованная подпись пункта навигации настроек. */
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
