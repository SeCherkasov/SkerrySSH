package app.skerry.ui.mobile

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
import androidx.compose.runtime.produceState
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
import app.skerry.shared.vault.SecurityEvent
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.appearance_default_value
import app.skerry.ui.generated.resources.appearance_font
import app.skerry.ui.generated.resources.appearance_font_size
import app.skerry.ui.generated.resources.appearance_language
import app.skerry.ui.generated.resources.appearance_letter_spacing
import app.skerry.ui.generated.resources.appearance_line_height
import app.skerry.ui.generated.resources.appearance_section_interface
import app.skerry.ui.generated.resources.appearance_section_terminal
import app.skerry.ui.generated.resources.appearance_title
import app.skerry.ui.generated.resources.more_ai_privacy
import app.skerry.ui.generated.resources.more_ai_subtitle_byok
import app.skerry.ui.generated.resources.more_ai_subtitle_local
import app.skerry.ui.generated.resources.more_appearance_subtitle
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
import app.skerry.ui.nav.PlatformBackHandler
import app.skerry.ui.sync.accountCardModelLocalized
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LETTER_SPACING
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LINE_HEIGHT
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_MAX
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_MIN
import app.skerry.ui.terminal.TerminalFont
import app.skerry.ui.terminal.TerminalTheme
import app.skerry.ui.terminal.TerminalThemes
import app.skerry.ui.vault.AutoLockDuration
import app.skerry.ui.vault.MIN_MASTER_PASSWORD_LENGTH
import app.skerry.ui.vault.VaultGateController
import app.skerry.ui.generated.resources.settings_badge_soon
import app.skerry.ui.generated.resources.settings_change
import app.skerry.ui.generated.resources.settings_manage
import app.skerry.ui.generated.resources.settings_recent_security_events
import app.skerry.ui.generated.resources.settings_security_2fa
import app.skerry.ui.generated.resources.settings_security_2fa_desc
import app.skerry.ui.generated.resources.settings_security_auto_lock
import app.skerry.ui.generated.resources.settings_security_auto_lock_desc
import app.skerry.ui.generated.resources.settings_security_master_password
import app.skerry.ui.generated.resources.settings_security_no_events
import app.skerry.ui.generated.resources.settings_security_subtitle
import app.skerry.ui.generated.resources.settings_security_title
import app.skerry.ui.generated.resources.settings_security_touch_id
import app.skerry.ui.generated.resources.settings_security_touch_id_desc
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.Badge
import app.skerry.ui.settings.ChangeMasterPasswordDialog
import app.skerry.ui.design.D
import app.skerry.ui.app.LocalAi
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalKnownHosts
import app.skerry.ui.app.LocalSecurityLog
import app.skerry.ui.app.LocalSync
import app.skerry.ui.app.LocalTunnels
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.design.NumberStepper
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt
import app.skerry.ui.settings.autoLockLabel
import app.skerry.ui.settings.formatDecimal
import app.skerry.ui.settings.masterPasswordSubtitle
import app.skerry.ui.settings.securityEventLine

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
            // Раздел «Безопасность»: мастер-пароль, биометрия, автоблокировка, журнал событий. Живой путь
            // за гейтом (есть vault); в превью строка инертна (нечего настраивать без vault).
            MoreRow("encrypted", D.cyanBright, stringResource(Res.string.settings_security_title), null, D.dim, onClick = if (preview) null else { -> state.push(MobileRoute.Security) })
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

// Security (push-экран More → Безопасность).

/**
 * Push-экран More → Безопасность (паритет desktop-раздела [SecuritySection]). Смена мастер-пароля
 * (диалог → [VaultGateController.changePassword]), реальный тумблер разблокировки биометрией (скрыт,
 * когда фактора/железа нет), выбор порога автоблокировки (проводится в idle-таймер гейта через
 * `state.autoLock`), журнал событий и подпись «последняя смена пароля» из реального [SecurityLog].
 * Двухфакторная — метка SOON (ещё не реализована). Собственный контроллер поверх ОБЩИХ
 * vault/биометрии/журнала (из composition-local): события пишутся в тот же файл, что и desktop.
 * Без vault (превью) — нейтральный вид без живых действий.
 */
@Composable
fun MobileSecurityScreen(state: MobileDesignState) {
    val vault = LocalVault.current
    val biometrics = LocalVaultBiometrics.current
    val log = LocalSecurityLog.current
    val controller = remember(vault, biometrics, log) {
        vault?.let { VaultGateController(it, biometrics, securityLog = log) }
    }
    var reload by remember { mutableStateOf(0) }
    var changePwOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(D.bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Sym("chevron_left", size = 27.sp, color = D.cyanBright, modifier = Modifier.clickable(onClick = state::pop))
                Txt(stringResource(Res.string.settings_security_title), color = D.text, size = 18.sp, weight = FontWeight.Bold)
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
                Txt(stringResource(Res.string.settings_security_subtitle), color = D.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))

                // Мастер-пароль: подпись = реальная «последняя смена» из журнала (или нейтральный текст).
                // Чтение журнала — файловый I/O + JSON-разбор, уводим его с потока композиции.
                val lastChange by produceState<String?>(null, controller, reload) {
                    value = withContext(Dispatchers.Default) { controller?.lastPasswordChangeAt() }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Txt(stringResource(Res.string.settings_security_master_password), color = D.text, size = 14.5.sp)
                        Txt(masterPasswordSubtitle(lastChange), color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    // Смена доступна только за живым vault; без него — приглушённо/инертно.
                    Txt(
                        stringResource(Res.string.settings_change),
                        color = if (controller != null) D.cyanBright else D.faint,
                        size = 13.sp,
                        weight = FontWeight.Medium,
                        modifier = if (controller != null) Modifier.clickable { changePwOpen = true } else Modifier,
                    )
                }
                MobileSecurityDivider()

                // Разблокировка биометрией: строка только когда фактор доступен (иначе настраивать нечего).
                if (controller != null && controller.canEnableBiometric()) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Txt(stringResource(Res.string.settings_security_touch_id), color = D.text, size = 14.5.sp)
                            Txt(stringResource(Res.string.settings_security_touch_id_desc), color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                        Toggle(
                            on = controller.biometricEnabled,
                            onToggle = {
                                if (controller.biometricInFlight) return@Toggle
                                scope.launch {
                                    if (controller.biometricEnabled) controller.disableBiometric()
                                    else controller.enableBiometric(MOBILE_ENABLE_BIOMETRIC_PROMPT)
                                    reload++
                                }
                            },
                        )
                    }
                    MobileSecurityDivider()
                }

                // Автоблокировка: реальный порог простоя, проводится в idle-таймер VaultGate через state.autoLock.
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Txt(stringResource(Res.string.settings_security_auto_lock), color = D.text, size = 14.5.sp)
                        Txt(stringResource(Res.string.settings_security_auto_lock_desc), color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    Box(Modifier.width(160.dp)) { MobileAutoLockPicker(state.autoLock, onPick = state::chooseAutoLock) }
                }
                MobileSecurityDivider()

                // Двухфакторная — ещё не реализована: честная метка SOON вместо фейкового «включена».
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Txt(stringResource(Res.string.settings_security_2fa), color = D.dim, size = 14.5.sp)
                            Badge(stringResource(Res.string.settings_badge_soon), bg = Color(0x1AF2A65A), fg = D.amber, radius = 3, size = 9.sp)
                        }
                        Txt(stringResource(Res.string.settings_security_2fa_desc), color = D.faint, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    Txt(stringResource(Res.string.settings_manage), color = D.faint, size = 13.sp)
                }

                // Недавние события безопасности — из реального журнала (или «пока событий нет»).
                Txt(stringResource(Res.string.settings_recent_security_events), color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                val events by produceState(emptyList<SecurityEvent>(), controller, reload) {
                    value = withContext(Dispatchers.Default) { controller?.recentSecurityEvents(8) ?: emptyList() }
                }
                if (events.isEmpty()) {
                    Txt(stringResource(Res.string.settings_security_no_events), color = D.faint, size = 12.sp, modifier = Modifier.padding(vertical = 3.dp))
                } else {
                    events.forEach { event ->
                        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Txt("●", color = D.moss, size = 9.sp)
                            Txt(securityEventLine(event), color = D.dim, size = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
        // Диалог смены мастер-пароля — модальный оверлей поверх экрана; back закрывает сперва его.
        if (changePwOpen && controller != null) {
            PlatformBackHandler(enabled = true) { changePwOpen = false }
            ChangeMasterPasswordDialog(
                controller = controller,
                onClose = { changePwOpen = false },
                onChanged = { reload++ },
            )
        }
    }
}

/** Разделительная линия между строками раздела Безопасность (тон макета). */
@Composable
private fun MobileSecurityDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(D.cyan.copy(alpha = 0.05f)))
}

/** Выпадающий список порога автоблокировки (мобильный) — переиспользует триггер/меню Appearance. */
@Composable
private fun MobileAutoLockPicker(current: AutoLockDuration, onPick: (AutoLockDuration) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { MobileSelectTrigger(current.autoLockLabel(), onClick = { open = !open }) },
        menu = { width ->
            MobileDropdownMenu(width) {
                AutoLockDuration.entries.forEach { option ->
                    MobileDropdownOption(option.autoLockLabel(), selected = option == current) { onPick(option); open = false }
                }
            }
        },
    )
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
            MobileStepperRow(
                label = stringResource(Res.string.appearance_font_size),
                isDefault = state.terminalFontSize == DEFAULT_TERMINAL_FONT_SIZE,
                defaultText = "$DEFAULT_TERMINAL_FONT_SIZE px",
                onReset = { state.chooseTerminalFontSize(DEFAULT_TERMINAL_FONT_SIZE) },
            ) {
                NumberStepper(
                    value = state.terminalFontSize.toFloat(),
                    onValueChange = { state.chooseTerminalFontSize(it.roundToInt().coerceIn(TERMINAL_FONT_SIZE_MIN, TERMINAL_FONT_SIZE_MAX)) },
                    step = 1f,
                    format = { it.roundToInt().toString() },
                    parse = { it.trim().toIntOrNull()?.toFloat() },
                    suffix = "px",
                )
            }
            MobileStepperRow(
                label = stringResource(Res.string.appearance_line_height),
                isDefault = formatDecimal(state.terminalLineHeight, 2) == formatDecimal(DEFAULT_TERMINAL_LINE_HEIGHT, 2),
                defaultText = formatDecimal(DEFAULT_TERMINAL_LINE_HEIGHT, 2),
                onReset = { state.chooseTerminalLineHeight(DEFAULT_TERMINAL_LINE_HEIGHT) },
            ) {
                NumberStepper(
                    value = state.terminalLineHeight,
                    onValueChange = state::chooseTerminalLineHeight,
                    step = 0.05f,
                    format = { formatDecimal(it, 2) },
                    parse = { it.trim().replace(',', '.').toFloatOrNull() },
                    fieldWidth = 52.dp,
                )
            }
            MobileStepperRow(
                label = stringResource(Res.string.appearance_letter_spacing),
                isDefault = formatDecimal(state.terminalLetterSpacing, 1) == formatDecimal(DEFAULT_TERMINAL_LETTER_SPACING, 1),
                defaultText = "${formatDecimal(DEFAULT_TERMINAL_LETTER_SPACING, 1)} px",
                onReset = { state.chooseTerminalLetterSpacing(DEFAULT_TERMINAL_LETTER_SPACING) },
            ) {
                NumberStepper(
                    value = state.terminalLetterSpacing,
                    onValueChange = state::chooseTerminalLetterSpacing,
                    step = 0.1f,
                    format = { formatDecimal(it, 1) },
                    parse = { it.trim().replace(',', '.').toFloatOrNull() },
                    suffix = "px",
                    fieldWidth = 52.dp,
                )
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

/** Строка настройки со степпером (мобильная): слева подпись + подсказка дефолта, справа [NumberStepper]. */
@Composable
private fun MobileStepperRow(
    label: String,
    isDefault: Boolean,
    defaultText: String,
    onReset: () -> Unit,
    stepper: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Txt(label, color = D.text, size = 14.5.sp)
            MobileDefaultValueHint(isDefault, defaultText, onReset)
        }
        stepper()
    }
}

/** Подсказка дефолта (мобильная): серый текст когда значение дефолтное, cyan-кликабельный сброс когда изменено. */
@Composable
private fun MobileDefaultValueHint(isDefault: Boolean, defaultText: String, onReset: () -> Unit) {
    val text = stringResource(Res.string.appearance_default_value, defaultText)
    if (isDefault) {
        Txt(text, color = D.faint, size = 12.sp, modifier = Modifier.padding(top = 3.dp))
    } else {
        Row(
            Modifier.padding(top = 3.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onReset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Sym("restart_alt", size = 14.sp, color = D.cyan)
            Txt(text, color = D.cyan, size = 12.sp)
        }
    }
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
