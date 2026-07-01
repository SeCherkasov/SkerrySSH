package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.appearance_font
import app.skerry.ui.generated.resources.appearance_font_size
import app.skerry.ui.generated.resources.appearance_language
import app.skerry.ui.generated.resources.appearance_section_interface
import app.skerry.ui.generated.resources.appearance_section_terminal
import app.skerry.ui.generated.resources.appearance_title
import app.skerry.ui.generated.resources.more_ai_privacy
import app.skerry.ui.generated.resources.more_ai_subtitle_byok
import app.skerry.ui.generated.resources.more_ai_subtitle_local
import app.skerry.ui.generated.resources.more_appearance_subtitle
import app.skerry.ui.generated.resources.more_biometric_enabled
import app.skerry.ui.generated.resources.more_biometric_skip
import app.skerry.ui.generated.resources.more_biometric_unlock
import app.skerry.ui.generated.resources.more_known_hosts
import app.skerry.ui.generated.resources.more_lock
import app.skerry.ui.generated.resources.more_port_forwarding
import app.skerry.ui.generated.resources.more_security_sync
import app.skerry.ui.generated.resources.more_sync_error
import app.skerry.ui.generated.resources.more_sync_linked_locked
import app.skerry.ui.generated.resources.more_sync_local_only
import app.skerry.ui.generated.resources.more_sync_synced
import app.skerry.ui.generated.resources.more_sync_syncing
import app.skerry.ui.generated.resources.more_team
import app.skerry.ui.generated.resources.more_title
import app.skerry.ui.i18n.UiLanguage
import app.skerry.ui.i18n.label
import app.skerry.ui.sync.accountCardModelLocalized
import app.skerry.ui.terminal.TERMINAL_FONT_SIZES
import app.skerry.ui.terminal.TerminalFont
import app.skerry.ui.terminal.TerminalTheme
import app.skerry.ui.terminal.TerminalThemes
import app.skerry.ui.vault.VaultGateController
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Корневой таб More: заголовок + карточка профиля + список разделов-ссылок. Хаб навигации к
 * push-экранам Port forwarding / Known hosts / Team и к действию «Lock Skerry».
 *
 * Живой путь ([onLock] != null, за гейтом vault): карточка профиля — локальный vault,
 * подзаголовки Port forwarding/Known hosts — живые счётчики
 * ([mobileMorePortsSubtitle]/[mobileMoreKnownSubtitle]) из [LocalSessions]/[LocalKnownHosts], строки
 * AI/Appearance/Security инертны (заглушки), «Lock Skerry» реально запирает vault. Превью/
 * офскрин ([onLock] == null) — статичная карточка мок-профиля.
 */
@Composable
fun MobileMoreScreen(state: MobileDesignState, onLock: (() -> Unit)?) {
    val preview = onLock == null
    Column(Modifier.fillMaxSize().background(D.bg).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 14.dp)) {
            Txt(stringResource(Res.string.more_title), color = D.text, size = 28.sp, weight = FontWeight.Bold, letterSpacing = (-0.5).sp)
        }
        if (preview) MockProfileCard() else LocalVaultCard()

        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            val ports = if (preview) "2 active" else portsSubtitle()
            val known = if (preview) "1 changed" else knownSubtitle()
            val knownWarn = if (preview) true else knownChanged() > 0

            MoreRow("lan", D.cyanBright, stringResource(Res.string.more_port_forwarding), ports, D.moss, onClick = { state.push(MobileRoute.Ports) })
            MoreRow("fingerprint", D.cyanBright, stringResource(Res.string.more_known_hosts), known, if (knownWarn) D.sunset else D.moss, onClick = { state.push(MobileRoute.Known) })
            MoreRow("groups", D.cyanBright, stringResource(Res.string.more_team), if (preview) "Platform crew" else null, D.dim, onClick = { state.push(MobileRoute.Team) })
            // AI: живой путь (есть контроллер) → push экрана настроек AI; иначе инертная заглушка (превью).
            val aiLive = LocalAi.current != null
            MoreRow("auto_awesome", D.amber, stringResource(Res.string.more_ai_privacy), if (aiLive) stringResource(Res.string.more_ai_subtitle_byok) else stringResource(Res.string.more_ai_subtitle_local), D.dim, onClick = if (aiLive) { -> state.push(MobileRoute.Ai) } else null)
            MoreRow("palette", D.cyanBright, stringResource(Res.string.appearance_title), stringResource(Res.string.more_appearance_subtitle), D.dim, onClick = { state.push(MobileRoute.Appearance) })
            MoreRow("shield_lock", D.cyanBright, stringResource(Res.string.more_security_sync), if (preview) stringResource(Res.string.more_sync_synced) else syncSubtitle(), D.dim, onClick = if (preview) null else { -> state.push(MobileRoute.Sync) })
            // Тумблер биометрии — живой путь за гейтом (vault открыт). Прячется, если биометрия
            // недоступна на устройстве (нет железа/не зачислен отпечаток) или это превью/офскрин.
            if (!preview) BiometricUnlockRow()
            MoreRow("lock", D.sunset, stringResource(Res.string.more_lock), null, D.dim, labelColor = D.sunset, divider = false, onClick = onLock)
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun portsSubtitle(): String {
    // Туннели — глобальный раздел: счёт активных берём из менеджера, без привязки к открытой
    // сессии. null (нет менеджера: превью/офскрин) → пустой подзаголовок.
    val manager = LocalTunnels.current ?: return mobileMorePortsSubtitle(null)
    return mobileMorePortsSubtitle(mobileActiveTunnelCount(manager.tunnels))
}

/** Подзаголовок строки «Security & sync»: статус координатора sync (нет/локально/подключено). */
@Composable
private fun syncSubtitle(): String {
    val sync = LocalSync.current ?: return stringResource(Res.string.more_sync_local_only)
    return when (sync.status.collectAsState().value) {
        is app.skerry.ui.sync.SyncStatus.Online -> stringResource(Res.string.more_sync_synced)
        app.skerry.ui.sync.SyncStatus.Busy -> stringResource(Res.string.more_sync_syncing)
        is app.skerry.ui.sync.SyncStatus.Configured -> stringResource(Res.string.more_sync_linked_locked)
        is app.skerry.ui.sync.SyncStatus.Failed -> stringResource(Res.string.more_sync_error)
        app.skerry.ui.sync.SyncStatus.Disabled -> stringResource(Res.string.more_sync_local_only)
    }
}

@Composable
private fun knownChanged(): Int = LocalKnownHosts.current?.mismatches?.size ?: 0

@Composable
private fun knownSubtitle(): String = mobileMoreKnownSubtitle(knownChanged())

/**
 * Строка раздела хаба: ведущая иконка + название + подпись справа + chevron. [onClick] == null —
 * инертная строка (раздел вне MVP, без действия). [divider] — нижняя
 * линия (нет у последней строки).
 */
@Composable
private fun MoreRow(
    icon: String,
    iconColor: Color,
    label: String,
    subtitle: String?,
    subtitleColor: Color,
    labelColor: Color = D.text,
    divider: Boolean = true,
    onClick: (() -> Unit)?,
) {
    val base = Modifier.fillMaxWidth()
    val clickable = if (onClick != null) {
        base.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    } else {
        base
    }
    Row(
        clickable.padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Sym(icon, size = 21.sp, color = iconColor)
        Txt(label, color = labelColor, size = 14.5.sp, modifier = Modifier.weight(1f))
        if (!subtitle.isNullOrEmpty()) Txt(subtitle, color = subtitleColor, size = 11.sp)
        if (onClick != null && labelColor != D.sunset) Sym("chevron_right", size = 20.sp, color = D.faint)
    }
    if (divider) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp).background(D.cyan.copy(alpha = 0.05f)))
}

/** Промпт включения биометрии для vault (англоязычный — под мобильный UI). */
private val MOBILE_ENABLE_BIOMETRIC_PROMPT = BiometricPrompt(
    title = "Enable biometric unlock",
    cancelLabel = "Cancel",
    subtitle = "Confirm your biometrics to unlock Skerry without typing the master password.",
)

/**
 * Живой тумблер «Unlock with biometrics» (за гейтом vault, vault открыт). Включение оборачивает
 * `dataKey` под аппаратный `bioKey` (требует биометрического подтверждения), выключение удаляет
 * артефакт. Строка целиком скрыта, если на устройстве нет доступной биометрии — тогда настраивать
 * нечего. Контроллер собственный (vault/биометрия общие): нам нужны только реактивные
 * `biometricEnabled`/`biometricInFlight` и enable/disable, не навигация гейта.
 */
@Composable
private fun BiometricUnlockRow() {
    val vault = LocalVault.current ?: return
    val biometrics = LocalVaultBiometrics.current ?: return
    val controller = remember(vault, biometrics) { VaultGateController(vault, biometrics) }
    if (!controller.canEnableBiometric()) return
    val scope = rememberCoroutineScope()

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Sym("fingerprint", size = 21.sp, color = D.cyanBright)
        Column(Modifier.weight(1f)) {
            Txt(stringResource(Res.string.more_biometric_unlock), color = D.text, size = 14.5.sp)
            Txt(
                if (controller.biometricEnabled) stringResource(Res.string.more_biometric_enabled) else stringResource(Res.string.more_biometric_skip),
                color = D.dim, size = 11.sp, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Toggle(
            on = controller.biometricEnabled,
            onToggle = {
                if (controller.biometricInFlight) return@Toggle
                scope.launch {
                    if (controller.biometricEnabled) {
                        controller.disableBiometric()
                    } else {
                        controller.enableBiometric(MOBILE_ENABLE_BIOMETRIC_PROMPT)
                    }
                }
            },
        )
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp).background(D.cyan.copy(alpha = 0.05f)))
}

// Appearance (push-экран More → Appearance).

/**
 * Push-экран More → Appearance: выбор темы терминала (карточки), шрифта и кегля. Оба шрифта
 * рендерятся без лигатур (см. [app.skerry.ui.terminal.TerminalAppearance]).
 */
@Composable
fun MobileAppearanceScreen(state: MobileDesignState) {
    Column(Modifier.fillMaxSize().background(D.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Sym("chevron_left", size = 27.sp, color = D.cyanBright, modifier = Modifier.clickable(onClick = state::pop))
            Txt(stringResource(Res.string.appearance_title), color = D.text, size = 18.sp, weight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Txt(stringResource(Res.string.appearance_section_terminal), color = D.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
            // Карточки тем сеткой 2×N из каталога [TerminalThemes]; выбор проводится в терминал на лету.
            TerminalThemes.all.chunked(2).forEach { rowThemes ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (theme in rowThemes) {
                        MobileThemeCard(
                            theme = theme,
                            active = theme.id == state.terminalTheme.id,
                            onClick = { state.chooseTerminalTheme(theme) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowThemes.size == 1) Box(Modifier.weight(1f))
                }
            }
            FontSettingRow(stringResource(Res.string.appearance_font)) {
                MobileFontPicker(state.terminalFont, onPick = state::chooseTerminalFont)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(D.cyan.copy(alpha = 0.05f)))
            FontSettingRow(stringResource(Res.string.appearance_font_size)) {
                MobileFontSizePicker(state.terminalFontSize, onPick = state::chooseTerminalFontSize)
            }
            Txt(stringResource(Res.string.appearance_section_interface), color = D.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
            FontSettingRow(stringResource(Res.string.appearance_language)) {
                MobileLanguagePicker(state.uiLanguage, onPick = state::chooseUiLanguage)
            }
        }
    }
}

/**
 * Мобильная карточка выбора темы терминала: мини-превью `ls -la` в реальных цветах [theme]; клик
 * выбирает, активная — cyan-рамка + бейдж ACTIVE. Зеркалит desktop-карточку из SettingsPanel.
 */
@Composable
private fun MobileThemeCard(
    theme: TerminalTheme,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mono = LocalFonts.current.mono
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (active) D.cyan else D.cyan.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().background(theme.background).padding(8.dp)) {
            Row { Txt("~ ", color = theme.ansi[2], size = 9.sp, font = mono); Txt("ls -la", color = theme.foreground, size = 9.sp, font = mono) }
            Row { Txt("drwxr-xr-x ", color = theme.ansi[6], size = 9.sp, font = mono); Txt("src", color = theme.ansi[4], size = 9.sp, font = mono) }
            Row { Txt("-rw-r--r-- ", color = theme.ansi[8], size = 9.sp, font = mono); Txt(".env", color = theme.ansi[3], size = 9.sp, font = mono) }
        }
        Row(
            Modifier.fillMaxWidth().background(D.surface2).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Txt(theme.displayName, color = D.text, size = 11.sp, weight = FontWeight.Medium, maxLines = 1)
            if (active) Badge("ACTIVE", bg = D.cyan14, fg = D.cyanBright, radius = 3, size = 8.sp)
        }
    }
}

/** Выпадающий список языка интерфейса (System / English / Русский). */
@Composable
private fun MobileLanguagePicker(current: UiLanguage, onPick: (UiLanguage) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { MobileSelectTrigger(current.label(), onClick = { open = !open }) },
        menu = { width ->
            MobileDropdownMenu(width) {
                UiLanguage.entries.forEach { option ->
                    MobileDropdownOption(option.label(), selected = option == current) { onPick(option); open = false }
                }
            }
        },
    )
}

/** Строка настройки: подпись слева + контрол (дропдаун) справа фиксированной ширины. */
@Composable
private fun FontSettingRow(label: String, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Txt(label, color = D.text, size = 14.5.sp, modifier = Modifier.weight(1f))
        Box(Modifier.width(180.dp)) { control() }
    }
}

/** Выпадающий список шрифта терминала (Hack / JetBrains Mono) — оба без лигатур. */
@Composable
private fun MobileFontPicker(current: TerminalFont, onPick: (TerminalFont) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { MobileSelectTrigger(current.displayName, onClick = { open = !open }) },
        menu = { width ->
            MobileDropdownMenu(width) {
                TerminalFont.entries.forEach { option ->
                    MobileDropdownOption(option.displayName, selected = option == current) { onPick(option); open = false }
                }
            }
        },
    )
}

/** Выпадающий список кегля шрифта терминала ([TERMINAL_FONT_SIZES], px). */
@Composable
private fun MobileFontSizePicker(current: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { MobileSelectTrigger("$current px", onClick = { open = !open }) },
        menu = { width ->
            MobileDropdownMenu(width) {
                TERMINAL_FONT_SIZES.forEach { size ->
                    MobileDropdownOption("$size px", selected = size == current) { onPick(size); open = false }
                }
            }
        },
    )
}

/** Триггер селекта: значение слева, шеврон справа. */
@Composable
private fun MobileSelectTrigger(value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(value, color = D.text, size = 13.sp)
        Sym("expand_more", size = 18.sp, color = D.faint)
    }
}

/** Колонка-меню выпадающего списка (поверхность + обводка макета). */
@Composable
private fun MobileDropdownMenu(width: Dp, content: @Composable () -> Unit) {
    Column(
        Modifier.width(width).clip(RoundedCornerShape(8.dp)).background(D.surface2).border(1.dp, D.cyan14, RoundedCornerShape(8.dp)),
    ) { content() }
}

/** Пункт выпадающего списка; выбранный подсвечен cyan. */
@Composable
private fun MobileDropdownOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Txt(
        label,
        color = if (selected) D.cyanBright else D.text,
        size = 13.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
    )
}

// Профиль.

/**
 * Живая карточка профиля: отражает реальное состояние sync — не настроен → локальный vault
 * («Encrypted on this device»), подключён → accountId + хост сервера. Управление синхронизацией
 * (set up / reconnect / disconnect / устройства) живёт на экране «Security & sync».
 *
 * Ветвление на отдельный [LiveLocalVaultCard] (а не условный `collectAsState` в одном теле) держит
 * composable-вызовы безусловными — правило слотовой таблицы Compose, как и на desktop ([LocalSync]
 * стабилен, но строгий паттерн надёжнее при будущих рефакторингах).
 */
@Composable
private fun LocalVaultCard() {
    when (val sync = LocalSync.current) {
        null -> AccountProfileCard(accountCardModelLocalized(null))
        else -> LiveLocalVaultCard(sync)
    }
}

@Composable
private fun LiveLocalVaultCard(sync: app.skerry.ui.sync.SyncCoordinator) {
    AccountProfileCard(accountCardModelLocalized(sync.status.collectAsState().value, sync.savedConfig?.serverUrl))
}

@Composable
private fun AccountProfileCard(model: app.skerry.ui.sync.AccountCardModel) {
    ProfileCard(initials = model.initials, avatarBg = D.cyan, title = model.title, subtitle = model.subtitle, badge = null)
}

/** Статичная карточка профиля (превью/офскрин). */
@Composable
private fun MockProfileCard() {
    ProfileCard(initials = "MK", avatarBg = D.cyan, title = "Maya Kovac", subtitle = "maya@skerry.dev", badge = "PRO")
}

@Composable
private fun ProfileCard(initials: String, avatarBg: Color, title: String, subtitle: String, badge: String?) {
    Row(
        Modifier
            .padding(horizontal = 18.dp)
            .padding(bottom = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x08FFFFFF))
            .border(1.dp, D.cyan08, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(avatarBg), contentAlignment = Alignment.Center) {
            Txt(initials, color = Color(0xFF0A1A26), size = 16.sp, weight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Txt(title, color = D.text, size = 15.sp, weight = FontWeight.SemiBold)
            Txt(subtitle, color = D.dim, size = 12.sp)
        }
        if (badge != null) {
            Badge(badge, bg = D.amber.copy(alpha = 0.14f), fg = D.amber, radius = 20, size = 9.5.sp)
        }
    }
}
