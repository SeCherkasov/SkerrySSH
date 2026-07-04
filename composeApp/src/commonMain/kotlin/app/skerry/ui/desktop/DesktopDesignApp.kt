package app.skerry.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.vault.SshCertificateInspector
import app.skerry.shared.vault.SshKeyGenerator
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.SecurityLog
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.shared.terminal.VaultTerminalHistoryStore
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.forward.humanRate
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.connection.toTarget
import app.skerry.ui.app.GroupDialog
import app.skerry.ui.host.GroupDialog as GroupEditDialog
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.session.SessionView
import app.skerry.ui.session.SessionsController
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.snippet.SnippetShortcut
import androidx.compose.runtime.collectAsState
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.sync.SyncIndicatorLevel
import app.skerry.ui.sync.syncIndicatorLocalized
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LETTER_SPACING
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LINE_HEIGHT
import app.skerry.ui.terminal.DEFAULT_TERMINAL_SCROLLBACK
import app.skerry.ui.terminal.LocalTerminalAppearance
import app.skerry.ui.terminal.LocalTerminalTheme
import app.skerry.ui.terminal.TerminalAppearance
import app.skerry.ui.terminal.TerminalCursorStyle
import app.skerry.ui.terminal.TerminalTheme
import app.skerry.ui.terminal.TerminalThemes
import app.skerry.ui.terminal.TerminalSessionPrefs
import app.skerry.ui.i18n.UiLanguage
import app.skerry.ui.terminal.TerminalFont
import app.skerry.ui.tunnel.TunnelManager
import app.skerry.ui.vault.AutoLockDuration
import app.skerry.ui.vault.ResetScope
import app.skerry.ui.vault.VaultGate
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_this_session
import app.skerry.ui.generated.resources.shell_disconnect_title
import app.skerry.ui.generated.resources.shell_disconnect_message
import app.skerry.ui.generated.resources.shell_disconnect
import app.skerry.ui.generated.resources.shell_close_split_title
import app.skerry.ui.generated.resources.shell_close_split_message
import app.skerry.ui.generated.resources.shell_close_panel
import app.skerry.ui.generated.resources.shell_unlocked
import app.skerry.ui.generated.resources.shell_settings
import app.skerry.ui.generated.resources.shell_status_connected
import app.skerry.ui.generated.resources.shell_status_disconnected
import app.skerry.ui.generated.resources.shell_status_encoding
import app.skerry.ui.generated.resources.shtail_new_tab
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.BrandMark
import app.skerry.ui.design.ConfirmActionDialog
import app.skerry.ui.design.D
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.vault.DesktopCorruptedScreen
import app.skerry.ui.vault.DesktopCreateScreen
import app.skerry.ui.host.DesktopDeleteHostDialog
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.connection.DesktopPasswordDialog
import app.skerry.ui.vault.DesktopResetScreen
import app.skerry.ui.vault.DesktopUnlockScreen
import app.skerry.ui.app.DesktopView
import app.skerry.ui.design.Dot
import app.skerry.ui.app.FeatureFlags
import app.skerry.ui.design.HLine
import app.skerry.ui.design.IconBtn
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalConnectHost
import app.skerry.ui.app.LocalConnectSplit
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.LocalFeatures
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalKnownHosts
import app.skerry.ui.app.LocalRunSnippetOnHost
import app.skerry.ui.app.LocalSecurityLog
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSftpPrefs
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.LocalSshCertificateInspector
import app.skerry.ui.app.LocalSshKeyGenerator
import app.skerry.ui.app.LocalSync
import app.skerry.ui.app.LocalTeams
import app.skerry.ui.app.LocalTestTransport
import app.skerry.ui.app.LocalTunnels
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.vault.LockScreen
import app.skerry.ui.host.NewConnectionModal
import app.skerry.ui.sync.PairingShowDialog
import app.skerry.ui.app.PendingClose
import app.skerry.ui.settings.SettingsPanel
import app.skerry.ui.app.SftpPrefs
import app.skerry.ui.design.Sym
import app.skerry.ui.sync.SyncOnboardingScreen
import app.skerry.ui.sync.SyncSetupDialog
import app.skerry.ui.session.TabDragState
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine
import app.skerry.ui.app.asSessionView
import app.skerry.ui.app.isAppLevel
import app.skerry.ui.i18n.label
import app.skerry.ui.design.rememberMaterialSymbols
import app.skerry.ui.design.rememberMono
import app.skerry.ui.design.rememberSpaceGrotesk
import app.skerry.ui.session.sessionDotColor
import app.skerry.ui.session.tabBoundsAnchor
import app.skerry.ui.app.asDesktopView
import app.skerry.ui.session.draggableTab

/**
 * Корень десктопного приложения. Поставляет шрифты
 * через [LocalFonts], держит [DesktopDesignState] и собирает структуру: titlebar (44dp) →
 * rail (62dp) + viewport → statusbar (26dp). Поверх — оверлеи lock / new-connection / settings.
 *
 * Живой слой подключается через [vault]: если передан, весь chrome закрыт гейтом мастер-пароля
 * ([app.skerry.ui.vault.VaultGate]) поверх [app.skerry.ui.vault.VaultGateController] — экраны
 * создания/разблокировки рисуются в стиле макета ([DesktopCreateScreen]/[DesktopUnlockScreen]),
 * а чип «Unlocked» в titlebar реально запирает vault. Без [vault] (путь скриншота/превью) данные
 * остаются мок-статичными ([DesktopMockData]), а блокировка — заглушка ([DesktopDesignState]).
 */
@Composable
fun DesktopDesignApp(
    // Видимость info-панели персистится снаружи (desktop main): стартовое значение + колбэк записи.
    initialInfoPanel: Boolean = true,
    onInfoPanelChange: (Boolean) -> Unit = {},
    // Схлопнутые папки хостов — так же персистятся снаружи (desktop main): стартовый набор + колбэк записи.
    initialCollapsedGroups: Set<String> = emptySet(),
    onCollapsedGroupsChange: (Set<String>) -> Unit = {},
    // Недавние подключения (секция RECENT) — тоже персистятся снаружи (desktop main): стартовый порядок + колбэк записи.
    initialRecentHostIds: List<String> = emptyList(),
    onRecentHostIdsChange: (List<String>) -> Unit = {},
    // Пользовательские (пустые) группы хостов — тоже персистятся снаружи (desktop main): стартовый список + колбэк записи.
    initialCustomGroups: List<String> = emptyList(),
    onCustomGroupsChange: (List<String>) -> Unit = {},
    // Показ скрытых в SFTP (Ctrl+H) — персистится снаружи (desktop main): стартовое значение + колбэк записи.
    initialSftpShowHidden: Boolean = true,
    onSftpShowHiddenChange: (Boolean) -> Unit = {},
    // Шрифт терминала и его кегль (Appearance → Font / Font size) — персистятся снаружи (desktop main).
    initialTerminalFont: TerminalFont = TerminalFont.DEFAULT,
    onTerminalFontChange: (TerminalFont) -> Unit = {},
    initialTerminalFontSize: Int = DEFAULT_TERMINAL_FONT_SIZE,
    onTerminalFontSizeChange: (Int) -> Unit = {},
    // Высота строки и межбуквенный интервал терминала (Appearance) — персистятся снаружи (desktop main).
    initialTerminalLineHeight: Float = DEFAULT_TERMINAL_LINE_HEIGHT,
    onTerminalLineHeightChange: (Float) -> Unit = {},
    initialTerminalLetterSpacing: Float = DEFAULT_TERMINAL_LETTER_SPACING,
    onTerminalLetterSpacingChange: (Float) -> Unit = {},
    // Язык интерфейса (Appearance → Language) — персистится снаружи (desktop main): стартовое значение + колбэк записи.
    initialUiLanguage: UiLanguage = UiLanguage.DEFAULT,
    onUiLanguageChange: (UiLanguage) -> Unit = {},
    // Настройки терминала (Settings → Терминал): scrollback, стиль курсора, показ OSC-заголовка на вкладках —
    // персистятся снаружи (desktop main): стартовые значения + колбэки записи.
    initialTerminalScrollback: Int = DEFAULT_TERMINAL_SCROLLBACK,
    onTerminalScrollbackChange: (Int) -> Unit = {},
    initialTerminalCursorStyle: TerminalCursorStyle = TerminalCursorStyle.DEFAULT,
    onTerminalCursorStyleChange: (TerminalCursorStyle) -> Unit = {},
    initialShowTerminalTitleOnTabs: Boolean = false,
    onShowTerminalTitleOnTabsChange: (Boolean) -> Unit = {},
    // Цветовая тема терминала (Appearance → карточки тем) — персистится снаружи (desktop main).
    initialTerminalTheme: TerminalTheme = TerminalThemes.DEFAULT,
    onTerminalThemeChange: (TerminalTheme) -> Unit = {},
    // Порог автоблокировки по простою (Settings → Безопасность) — персистится снаружи (desktop main).
    initialAutoLock: AutoLockDuration = AutoLockDuration.DEFAULT,
    onAutoLockChange: (AutoLockDuration) -> Unit = {},
    // Видимость и размер секции RECENT (Settings → Appearance → Interface) — персистятся снаружи (desktop main).
    initialShowRecent: Boolean = true,
    onShowRecentChange: (Boolean) -> Unit = {},
    initialRecentLimit: Int = DesktopDesignState.MAX_RECENT_HOSTS,
    onRecentLimitChange: (Int) -> Unit = {},
    state: DesktopDesignState = remember {
        DesktopDesignState(
            initialInfoPanel, onInfoPanelChange, initialCollapsedGroups, onCollapsedGroupsChange,
            initialRecentHostIds, onRecentHostIdsChange, initialCustomGroups, onCustomGroupsChange,
            initialTerminalFont, onTerminalFontChange, initialTerminalFontSize, onTerminalFontSizeChange,
            initialTerminalLineHeight, onTerminalLineHeightChange, initialTerminalLetterSpacing, onTerminalLetterSpacingChange,
            initialUiLanguage, onUiLanguageChange,
            initialTerminalScrollback, onTerminalScrollbackChange,
            initialTerminalCursorStyle, onTerminalCursorStyleChange,
            initialShowTerminalTitleOnTabs, onShowTerminalTitleOnTabsChange,
            initialTerminalTheme, onTerminalThemeChange,
            initialAutoLock, onAutoLockChange,
            initialShowRecent, onShowRecentChange,
            initialRecentLimit, onRecentLimitChange,
        )
    },
    vault: Vault? = null,
    biometrics: VaultBiometrics? = null,
    // Локальный журнал событий безопасности (Settings → Безопасность). `null` — мок/превью: секция
    // рисует пустой журнал и нейтральную подпись пароля.
    securityLog: SecurityLog? = null,
    hosts: HostManagerController? = null,
    transport: SshTransport? = null,
    // Транспорт для разовой проверки «Test connection»: отдельный от [transport] (живых сессий),
    // потому что проба НЕ должна заносить ключ хоста в known_hosts (read-only verifier). `null` —
    // использовать [transport] (офскрин-рендер/превью, где enroll-побочки нет). См. main.kt.
    testTransport: SshTransport? = null,
    credentials: CredentialManagerController? = null,
    sessions: SessionsController? = null,
    knownHosts: KnownHostsController? = null,
    keyGenerator: SshKeyGenerator? = null,
    certificateInspector: SshCertificateInspector? = null,
    tunnels: TunnelManager? = null,
    snippets: SnippetManager? = null,
    // Координатор self-hosted sync (Phase 2). `null` — sync не подключён на платформе/мок-путь:
    // секция Sync в настройках рисует статичный макет, модалка-онбординг не показывается.
    sync: SyncCoordinator? = null,
    // Координатор Teams (шеринг между аккаунтами поверх sync). `null` — экран Teams в мок-режиме.
    teams: app.skerry.ui.teams.TeamsCoordinator? = null,
    // Контроллер AI-ассистента (BYOK, внешний OpenAI-совместимый провайдер). `null` — AI не подключён:
    // таб «AI» в настройках рисует статичный макет. Поставляется за гейтом vault (ключ хранится в vault).
    ai: app.skerry.ui.ai.AiAssistantController? = null,
    features: FeatureFlags = FeatureFlags(),
    // Вызывается один раз после разблокировки vault, до перечитывания списков — точка для миграции
    // данных (схлопывание двухуровневой модели → хост ссылается на keychain-секрет). No-op в мок/превью.
    onVaultUnlocked: () -> Unit = {},
    // Пустые папки хостов синкаются в записи-макете vault (Phase A): на старте vault залочен, поэтому
    // после unlock (и миграции в [onVaultUnlocked]) перечитываем их отсюда в состояние. No-op в мок/превью.
    customGroupsProvider: () -> List<String> = { emptyList() },
    // Внешняя чистка при сбросе vault (хосты/known_hosts/настройки по [ResetScope]). Вызывается после
    // стирания файла vault; реальную реализацию подставляет desktop `main`. No-op в мок/превью.
    onVaultReset: (ResetScope) -> Unit = {},
) {
    val fonts = DesignFonts(
        ui = rememberSpaceGrotesk(),
        mono = rememberMono(),
        symbols = rememberMaterialSymbols(),
    )
    // Настройка показа скрытых в SFTP: держим в стейте, чтобы Ctrl+H обновлял UI мгновенно, и пишем
    // наружу (персист) при каждом изменении. remember обязателен (как terminalAppearance ниже):
    // LocalSftpPrefs — staticCompositionLocalOf, и новый инстанс на каждой рекомпозиции форсил бы
    // полный пересбор поддерева потребителей. Колбэк персиста — через rememberUpdatedState, чтобы
    // лямбда внутри remember всегда звала свежий onSftpShowHiddenChange.
    var sftpShowHidden by remember { mutableStateOf(initialSftpShowHidden) }
    val sftpShowHiddenWriter = rememberUpdatedState(onSftpShowHiddenChange)
    val sftpPrefs = remember(sftpShowHidden) {
        SftpPrefs(sftpShowHidden) { value ->
            sftpShowHidden = value
            sftpShowHiddenWriter.value(value)
        }
    }
    // Менеджер сессий: либо подан снаружи (офскрин-рендер с фейковым транспортом), либо строится
    // из живого транспорта — один shell на вкладку, как в [app.skerry.ui.mobile.MobileApp]. Свой
    // граф закрываем при dispose; внешний — собственность вызывающего, не трогаем.
    val scope = rememberCoroutineScope()
    val liveSessions = sessions ?: remember(transport, scope, vault) {
        transport?.let { t ->
            // Персист истории команд терминала per-host (для автодополнения) поверх зашифрованного vault.
            val termHistory = vault?.let { VaultTerminalHistoryStore(it) }
            var counter = 0
            SessionsController(
                newId = { "sess-${counter++}" },
                controllerFactory = {
                    ConnectionController(
                        t, scope, history = termHistory,
                        // Читаем настройки терминала в момент connect — новые сессии подхватывают текущий
                        // выбор scrollback/курсора, открытые сохраняют свой эмулятор.
                        terminalPrefs = { TerminalSessionPrefs(state.terminalScrollback, state.terminalCursorStyle) },
                    )
                },
            )
        }
    }
    // Владение фиксируем снимком на момент композиции: внешний менеджер сессий — собственность
    // вызывающего (не рвём), локально построенный закрываем при dispose.
    val ownsSessions = sessions == null
    DisposableEffect(liveSessions) {
        onDispose { if (ownsSessions) liveSessions?.disconnectAll() }
    }
    // Смена стиля курсора в настройках применяется к УЖЕ открытым сессиям на лету (новые берут его при
    // connect через terminalPrefs). Проталкиваем в терминалы каждой вкладки и её split-панели; команда
    // идёт через очередь эмулятора, поэтому без гонки. Отсоединённые/пустые вкладки просто пропускаем.
    val cursorStyle = state.terminalCursorStyle
    LaunchedEffect(cursorStyle, liveSessions) {
        val manager = liveSessions ?: return@LaunchedEffect
        manager.sessions.forEach { s ->
            s.liveTerminal?.applyCursorStyle(cursorStyle.shape, cursorStyle.blink)
            s.splitSession?.liveTerminal?.applyCursorStyle(cursorStyle.shape, cursorStyle.blink)
        }
    }
    // Смена буфера прокрутки в настройках так же применяется к УЖЕ открытым сессиям на лету: при
    // уменьшении лишняя старая история обрезается, при увеличении новые строки дольше держатся.
    // Новые сессии берут значение при connect через terminalPrefs.
    val scrollbackLines = TerminalSessionPrefs(scrollback = state.terminalScrollback).effectiveScrollback
    LaunchedEffect(scrollbackLines, liveSessions) {
        val manager = liveSessions ?: return@LaunchedEffect
        manager.sessions.forEach { s ->
            s.liveTerminal?.applyScrollback(scrollbackLines)
            s.splitSession?.liveTerminal?.applyScrollback(scrollbackLines)
        }
    }
    // Мемоизируем: LocalTerminalAppearance — staticCompositionLocalOf (сравнение по ссылке), а
    // DesktopDesignApp рекомпозируется на смене вкладок/сессий/событий vault. Без remember новый
    // инстанс на каждой рекомпозиции форсил бы полный пересбор поддерева потребителей (весь Canvas
    // терминала), даже когда шрифт/кегль не менялись.
    val terminalAppearance = remember(state.terminalFont, state.terminalFontSize, state.terminalLineHeight, state.terminalLetterSpacing) {
        TerminalAppearance(state.terminalFont, state.terminalFontSize, state.terminalLineHeight, state.terminalLetterSpacing)
    }
    // Язык ответов терминального AI = язык интерфейса: провайдер читает применённый тег локали
    // ([app.skerry.ui.i18n.LocalAppLocale]) и переустанавливается при смене языка (SideEffect
    // перезапускается по смене тега), так что INFO/ASK идут на текущем языке без пересоздания контроллера.
    val aiLocaleTag = app.skerry.ui.i18n.LocalAppLocale.current
    androidx.compose.runtime.SideEffect {
        ai?.uiLanguageProvider = { app.skerry.ui.i18n.aiResponseLanguageName(aiLocaleTag) }
    }
    CompositionLocalProvider(
        LocalFonts provides fonts,
        LocalHosts provides hosts,
        LocalSessions provides liveSessions,
        LocalKnownHosts provides knownHosts,
        LocalSshKeyGenerator provides keyGenerator,
        LocalSshCertificateInspector provides certificateInspector,
        LocalCredentials provides credentials,
        LocalTestTransport provides (testTransport ?: transport),
        LocalTunnels provides tunnels,
        LocalSnippets provides snippets,
        LocalFeatures provides features,
        LocalSftpPrefs provides sftpPrefs,
        // Внешний вид терминала из настроек: шрифт + кегль читает [app.skerry.ui.terminal.TerminalScreen].
        LocalTerminalAppearance provides terminalAppearance,
        // Цветовая тема терминала (Appearance → карточки): фон/текст/ANSI/курсор — тот же рендер.
        LocalTerminalTheme provides state.terminalTheme,
        // Открытый vault + биометрия за гейтом — нужны повторной аутентификации перед копированием
        // пароля из keychain (на desktop биометрии нет, путь сводится к мастер-паролю).
        LocalVault provides vault,
        LocalVaultBiometrics provides biometrics,
        LocalSecurityLog provides securityLog,
        LocalSync provides sync,
        LocalTeams provides teams,
        LocalAi provides ai,
    ) {
        if (vault != null) {
            VaultGate(
                vault = vault,
                biometrics = biometrics,
                securityLog = securityLog,
                // Порог автоблокировки из настроек: смена в UI рекомпозирует VaultGate и перезапускает
                // idle-таймер; Never (idleMs == null) выключает его.
                autoLockIdleMs = state.autoLock.idleMs,
                onReset = onVaultReset,
                // onPairingComplete != null (есть sync) — экран создания предлагает «у меня есть код»:
                // координатор сам создаст vault под выбранным паролем и примет ключ аккаунта.
                createForm = { error, onCreate, onPairingComplete ->
                    DesktopCreateScreen(error, onCreate, sync, onPairingComplete)
                },
                unlockForm = { error, canBio, onUnlock, onBio, onForgot ->
                    DesktopUnlockScreen(error, canBio, onUnlock, onBio, onForgot)
                },
                corruptedForm = { onReset -> DesktopCorruptedScreen(onReset) },
                resetForm = { onConfirm, onCancel -> DesktopResetScreen(onConfirm, onCancel) },
                // Шаг sync в онбординге (паритет с mobile): подключить sync и подтянуть данные сразу
                // после создания vault. Только если sync проведён в граф.
                offerSyncForm = sync?.let { s -> { onDone -> SyncOnboardingScreen(s, onDone) } },
            ) { onLock -> DesktopChrome(state, onLock, liveSessions, credentials, onVaultUnlocked, customGroupsProvider) }
        } else {
            DesktopChrome(state, onLock = null, sessions = liveSessions, credentials = credentials, onVaultUnlocked = onVaultUnlocked, customGroupsProvider = customGroupsProvider)
        }
    }
}

/**
 * Основной chrome (titlebar → rail+viewport → statusbar) и оверлеи. [onLock] != null —
 * живой путь за гейтом: чип «Unlocked» запирает vault. null — мок-путь: блокировку рисует
 * заглушечный [LockScreen] по [DesktopDesignState.locked].
 */
@Composable
private fun DesktopChrome(
    state: DesktopDesignState,
    onLock: (() -> Unit)?,
    sessions: SessionsController?,
    credentials: CredentialManagerController?,
    onVaultUnlocked: () -> Unit,
    customGroupsProvider: () -> List<String>,
) {
    // Keychain-секреты живут в открытом vault — за гейтом мастер-пароля сперва прогоняем
    // миграцию данных ([onVaultUnlocked]), затем перечитываем (секреты + синканутые пустые папки).
    LaunchedEffect(credentials) {
        onVaultUnlocked()
        credentials?.reload()
        state.loadCustomGroups(customGroupsProvider())
    }

    // Хост без привязанного секрета → спрашиваем пароль перед подключением. Единое состояние на все
    // три пути ([PendingAuth]): новая вкладка / split (целевую вкладку фиксируем в момент выбора
    // хоста, а не submit — иначе при переключении вкладок во время ввода пароля split откроется не
    // там, где его запросили) / «Run on host» сниппета (помним и команду).
    var pendingAuth by remember { mutableStateOf<PendingAuth?>(null) }

    // Единый диспетчер подключения с готовой аутентификацией: куда идёт сессия — по типу [PendingAuth].
    fun openResolved(target: PendingAuth, auth: SshAuth) {
        when (target) {
            is PendingAuth.NewTab -> openHostSession(sessions, state, target.host, auth)
            is PendingAuth.Split -> openSplitSession(sessions, state, target.parentId, target.host, auth)
            is PendingAuth.Snippet ->
                openHostSession(sessions, state, target.host, auth) { it.send(target.command + "\n") }
        }
    }

    // Общий шаг всех трёх путей: резолв аутентификации ([resolveHostAuth]) → сразу подключить либо
    // спросить пароль, запомнив цель.
    fun connectOrAsk(target: PendingAuth) {
        when (val resolution = resolveHostAuth(target.host, credentials)) {
            is HostAuthResolution.Resolved -> openResolved(target, resolution.auth)
            HostAuthResolution.NeedsPassword -> pendingAuth = target
        }
    }

    // Стабильные лямбды коннекта: без remember они пересоздавались бы на каждой рекомпозиции и,
    // уходя в staticCompositionLocalOf, инвалидировали бы всех потребителей [LocalConnectHost] и др.
    val connectHost = remember(sessions, credentials, state) {
        { host: Host -> connectOrAsk(PendingAuth.NewTab(host)) }
    }

    // «Run on host» сниппета: открыть сессию к хосту и выполнить команду после подключения.
    val runSnippetOnHost = remember(sessions, credentials, state) {
        { host: Host, command: String -> connectOrAsk(PendingAuth.Snippet(host, command)) }
    }

    // Тот же резолв, но в split-панель активной вкладки (новая независимая вторичная сессия).
    val connectSplitHost = remember(sessions, credentials, state) {
        { host: Host -> connectOrAsk(PendingAuth.Split(host, sessions?.activeId)) }
    }

    // Лок vault должен снять все активные туннели: их соединения держат расшифрованный секрет, и после
    // запирания висеть им нельзя (zero-knowledge). closeAll идемпотентен; для мок-пути (onLock==null)
    // оборачивать нечего.
    val tunnels = LocalTunnels.current
    // Лок снимает активные туннели (их соединения держат расшифрованный секрет — zero-knowledge), но
    // СЕССИИ ВКЛАДОК (включая split) намеренно ПЕРЕЖИВАЮТ lock: после разблокировки терминалы на месте.
    // Висящие диалоги запроса пароля сбрасываем (не оставлять ввод пароля под lock-экраном).
    // Стабилизируем как onRootKey ниже: лямбда уходит в TitleBar и lockAction, и без remember новый
    // инстанс на каждой рекомпозиции заставлял бы их перевычисляться впустую.
    val onLockWithTunnels: (() -> Unit)? = if (onLock == null) null else remember(onLock, tunnels, sessions, state) {
        {
            pendingAuth = null
            // Висящее подтверждение разрыва/закрытия сбрасываем тоже — после unlock действие требует
            // свежего намерения пользователя (как pendingAuth), а не «всплывает» поверх.
            state.dismissClose()
            tunnels?.closeAll()
            // Сессии переживают lock (открытый сокет жить остаётся), но авто-реконнект после lock
            // переаутентифицировался бы устаревшим секретом на запертом vault — запрещаем его,
            // сбрасывая сохранённые учётки у всех сессий и их split-панелей (zero-knowledge).
            sessions?.sessions?.forEach { s ->
                s.controller.clearReconnectCredentials()
                s.splitSession?.controller?.clearReconnectCredentials()
            }
            onLock()
        }
    }

    CompositionLocalProvider(
        LocalConnectHost provides connectHost,
        LocalConnectSplit provides connectSplitHost,
        LocalRunSnippetOnHost provides runSnippetOnHost,
        LocalCredentials provides credentials,
    ) {
        // Глобальный хоткей сниппета: preview-события идут от корня к фокусу, поэтому корневой Box
        // перехватывает аккорд раньше терминала. Совпал сохранённый shortcut и есть подключённая
        // сессия — выполняем команду в её терминале и гасим событие. ГЕЙТ: срабатывает только когда
        // на экране живая сессия (нет app-оверлея/модалки/настроек) — иначе аккорд, набранный в полях
        // редактора сниппета (Command/ShortcutField) или New connection, ушёл бы командой в терминал.
        val snippets = LocalSnippets.current
        // Живой lock (сбрасывает туннели/учётки) на живом пути; state.lock — мок/превью. Через
        // rememberUpdatedState, чтобы onRootKey не зависел от смены самой лямбды блокировки.
        val lockAction = rememberUpdatedState(onLockWithTunnels ?: state::lock)
        // Глобальные хоткеи каркаса (⌘/Ctrl+Shift — New conn/Split/SFTP/AI-bar/Lock, Ctrl+Tab —
        // соседняя вкладка, Alt+цифра — вкладка по номеру) проверяются ПЕРЕД хоткеем сниппета. Тот же
        // гейт: только на живом сессионном экране (нет оверлея/модалки/настроек), чтобы аккорд из полей
        // редактора не ушёл в терминал/навигацию. SelectTab/Next вне диапазона возвращает false и
        // проваливается в сниппет-матчинг (Alt+7 при 4 вкладках может остаться сниппетом).
        val onRootKey = remember(snippets, sessions, state) {
            { event: KeyEvent ->
                if (event.type != KeyEventType.KeyDown) false
                else if (state.appOverlay != null || state.modalOpen || state.settingsOpen) false
                else {
                    val shortcut = matchDesktopShortcut(
                        event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.isMetaPressed, event.key,
                    )
                    if (shortcut != null && runDesktopShortcut(shortcut, state, sessions, lockAction.value)) true
                    else runSnippetHotkey(event, snippets, sessions)
                }
            }
        }
        Box(Modifier.fillMaxSize().background(D.bg).onPreviewKeyEvent(onRootKey)) {
            Column(Modifier.fillMaxSize()) {
                TitleBar(state, onLockWithTunnels)
                HLine()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    IconRail(state)
                    VLine(D.line)
                    Box(Modifier.weight(1f).fillMaxHeight()) { Viewport(state) }
                }
                HLine()
                StatusBar()
            }
            if (state.modalOpen) NewConnectionModal(state, editHost = state.editingHost)
            if (state.settingsOpen) SettingsPanel(state)
            // Модалка-онбординг sync поверх настроек: появляется по «Set up sync», закрывается сама
            // при успешном подключении. Только когда координатор подан (мок-путь без бэкенда — нет).
            LocalSync.current?.let { if (state.syncSetupOpen) SyncSetupDialog(it, onDismiss = state::closeSyncSetup) }
            // Диалог «Link a device»: показывает QR/код быстрого паринга для нового устройства.
            LocalSync.current?.let { if (state.pairingOpen) PairingShowDialog(it, onDismiss = state::closePairing) }
            if (onLock == null && state.locked) LockScreen(state)
            // Один диалог запроса пароля на все три пути подключения; после submit цель ([PendingAuth])
            // диспетчеризуется тем же openResolved, что и путь с привязанным секретом.
            pendingAuth?.let { pending ->
                DesktopPasswordDialog(
                    host = pending.host,
                    onDismiss = { pendingAuth = null },
                    onConnect = { pw ->
                        pendingAuth = null
                        openResolved(pending, SshAuth.Password(pw))
                    },
                )
            }
            // Подтверждение удаления профиля хоста (вызывается из контекстного меню сайдбара).
            // Сам keychain-секрет остаётся в vault (переиспользуемый, управляется во вкладке Vault).
            val hosts = LocalHosts.current
            state.pendingDeleteHost?.let { host ->
                DesktopDeleteHostDialog(
                    host = host,
                    onDismiss = state::dismissDeleteHost,
                    onConfirm = { hosts?.delete(host.id); state.dismissDeleteHost() },
                )
            }
            // Создание/правка группы хостов (кнопка «+папка» и карандаш в заголовке папки). Rename
            // одновременно переписывает Host.group через контроллер и правит side-channel пустых/
            // схлопнутых групп в state; delete разгруппировывает хосты (профили целы).
            when (val gd = state.groupDialog) {
                GroupDialog.Create -> GroupEditDialog(
                    initialName = "",
                    onDismiss = state::dismissGroupDialog,
                    onSave = { name -> state.addCustomGroup(name); state.dismissGroupDialog() },
                    onDelete = null,
                )
                is GroupDialog.Rename -> GroupEditDialog(
                    initialName = gd.name,
                    onDismiss = state::dismissGroupDialog,
                    onSave = { name ->
                        hosts?.renameGroup(gd.name, name)
                        state.renameGroupName(gd.name, name)
                        state.dismissGroupDialog()
                    },
                    onDelete = {
                        hosts?.deleteGroup(gd.name)
                        state.removeCustomGroup(gd.name)
                        state.dismissGroupDialog()
                    },
                )
                null -> {}
            }
            // Подтверждение разрыва сессии (power) / закрытия split-панели — деструктивно, без авто-реконнекта.
            when (val pc = state.pendingClose) {
                is PendingClose.Session -> {
                    val name = sessions?.sessions?.firstOrNull { it.id == pc.id }?.displayTitle ?: stringResource(Res.string.shell_this_session)
                    ConfirmActionDialog(
                        title = stringResource(Res.string.shell_disconnect_title, name),
                        message = stringResource(Res.string.shell_disconnect_message),
                        confirmLabel = stringResource(Res.string.shell_disconnect),
                        onConfirm = { sessions?.close(pc.id); state.dismissClose() },
                        onDismiss = state::dismissClose,
                    )
                }
                is PendingClose.Split -> {
                    ConfirmActionDialog(
                        title = stringResource(Res.string.shell_close_split_title),
                        message = stringResource(Res.string.shell_close_split_message),
                        confirmLabel = stringResource(Res.string.shell_close_panel),
                        onConfirm = { sessions?.closeSplit(pc.parentId); state.dismissClose() },
                        onDismiss = state::dismissClose,
                    )
                }
                null -> {}
            }
        }
    }
}

/**
 * Отложенное подключение, ждущее ввода пароля (SSH-хост без привязанного секрета), — и одновременно
 * адрес доставки готовой аутентификации: новая вкладка, split-панель конкретной вкладки или запуск
 * команды сниппета на хосте.
 */
private sealed interface PendingAuth {
    val host: Host

    /** Подключение новой вкладкой (или в активную пустую). */
    data class NewTab(override val host: Host) : PendingAuth

    /** Подключение в split-панель вкладки [parentId] (фиксируется в момент выбора хоста). */
    data class Split(override val host: Host, val parentId: String?) : PendingAuth

    /** Открыть сессию к хосту и выполнить [command] после подключения. */
    data class Snippet(override val host: Host, val command: String) : PendingAuth
}

/**
 * Глобальный хоткей сниппета: на KeyDown сериализует аккорд ([SnippetShortcut]), ищет сниппет с этим
 * хоткеем и, если есть подключённая сессия, выполняет его команду в её терминале. Возвращает `true`
 * (событие поглощено) только при реальном запуске — иначе клавиша уходит дальше (в терминал и т.д.).
 */
private fun runSnippetHotkey(event: KeyEvent, manager: SnippetManager?, sessions: SessionsController?): Boolean {
    if (event.type != KeyEventType.KeyDown || manager == null) return false
    val combo = SnippetShortcut.format(
        event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.isMetaPressed, event.key,
    ) ?: return false
    val entry = manager.forShortcut(combo) ?: return false
    val terminal = (sessions?.active?.controller?.uiState as? ConnectionUiState.Connected)?.terminal ?: return false
    manager.run(entry.id) { terminal.send(it) }
    return true
}

/**
 * Исполнить глобальный хоткей каркаса ([matchDesktopShortcut]). Возвращает `true`, если действие
 * применилось (событие поглощаем), `false` — если нет цели (напр. Alt+цифра вне числа вкладок): тогда
 * вызывающий пропускает клавишу дальше (в т.ч. в хоткей сниппета). Живой режим адресует вкладки через
 * [SessionsController]; мок/превью (нет живых сессий) — через демо-вкладки [DesktopDesignState].
 */
private fun runDesktopShortcut(
    shortcut: DesktopShortcut,
    state: DesktopDesignState,
    sessions: SessionsController?,
    onLock: () -> Unit,
): Boolean {
    when (shortcut) {
        is DesktopShortcut.SelectTab -> return selectTabByIndex(shortcut.index, state, sessions)
        DesktopShortcut.NextTab -> return cycleTab(+1, state, sessions)
        DesktopShortcut.PrevTab -> return cycleTab(-1, state, sessions)
        DesktopShortcut.NewConnection -> state.openModal()
        DesktopShortcut.SplitTerminal -> if (sessions != null) sessions.toggleSplit() else state.toggleSplit()
        DesktopShortcut.OpenSftp -> if (sessions != null) {
            state.clearOverlay(); sessions.setActiveView(SessionView.Sftp)
        } else {
            state.showView(DesktopView.Sftp)
        }
        DesktopShortcut.Lock -> onLock()
        DesktopShortcut.FocusAiBar -> state.requestAiBarFocus()
    }
    return true
}

/** Выбрать вкладку по 0-based индексу; `false`, если такой вкладки нет (клавиша пойдёт дальше). */
internal fun selectTabByIndex(index: Int, state: DesktopDesignState, sessions: SessionsController?): Boolean {
    if (sessions != null) {
        val target = sessions.sessions.getOrNull(index) ?: return false
        sessions.activate(target.id)
        return true
    }
    if (index !in state.tabs.indices) return false
    state.setTab(index)
    return true
}

/** Циклически сдвинуть активную вкладку на [delta] (по кругу); `false`, если вкладок нет. */
internal fun cycleTab(delta: Int, state: DesktopDesignState, sessions: SessionsController?): Boolean {
    if (sessions != null) {
        val list = sessions.sessions
        if (list.isEmpty()) return false
        val current = list.indexOfFirst { it.id == sessions.activeId }.coerceAtLeast(0)
        val next = ((current + delta) % list.size + list.size) % list.size
        sessions.activate(list[next].id)
        return true
    }
    val count = state.tabs.size
    if (count == 0) return false
    val next = ((state.activeTab + delta) % count + count) % count
    state.setTab(next)
    return true
}

/**
 * Подключиться к [host] с [auth]: если активна пустая вкладка («+») — коннект в неё, иначе новая
 * вкладка ([SessionsController.connect]). Затем переключаемся на терминал (сбрасывая app-оверлей).
 */
private fun openHostSession(
    sessions: SessionsController?,
    state: DesktopDesignState,
    host: Host,
    auth: SshAuth,
    onConnected: ((app.skerry.ui.terminal.TerminalScreenState) -> Unit)? = null,
) {
    // Отмечаем хост в секции RECENT сайдбара (новейший — первым, переживает перезапуск).
    state.recordRecentHost(host.id)
    sessions?.connect(
        hostId = host.id,
        title = host.label,
        subtitle = host.connectionSubtitle(),
        target = host.toTarget(),
        auth = auth,
        onConnected = onConnected,
    )
    // Живой режим: подвью держит сама вкладка — лишь снимаем оверлей, чтобы показать её терминал.
    // Мок/превью (нет сессий): фолбэк на Terminal через showView.
    if (sessions != null) state.clearOverlay() else state.showView(DesktopView.Terminal)
}

/**
 * Подключить [host] с [auth] в split-панель активной вкладки (новая независимая вторичная сессия).
 * Без активной вкладки — no-op. См. [SessionsController.connectSplit].
 */
private fun openSplitSession(sessions: SessionsController?, state: DesktopDesignState, parentId: String?, host: Host, auth: SshAuth) {
    if (sessions == null || parentId == null) return
    // Подключение во вторичную панель — тоже реальный коннект к хосту: отмечаем его в RECENT.
    state.recordRecentHost(host.id)
    sessions.connectSplit(
        parentId = parentId,
        hostId = host.id,
        title = host.label,
        subtitle = host.connectionSubtitle(),
        target = host.toTarget(),
        auth = auth,
    )
}

@Composable
private fun TitleBar(state: DesktopDesignState, onLock: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Brush.verticalGradient(listOf(D.titleTop, D.titleBottom)))
            .padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            BrandMark(size = 28.dp)
            Txt("Skerry", color = D.text, size = 14.5.sp, weight = FontWeight.Bold, letterSpacing = (-0.2).sp)
        }
        Row(
            Modifier.weight(1f).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // Живые вкладки из менеджера сессий (за гейтом vault); иначе — мок-вкладки.
            val sessions = LocalSessions.current
            // Локализованная подпись новой пустой вкладки резолвится здесь (composable-сторона):
            // в SessionsController stringResource недоступен, поэтому лейбл прокидываем в openBlank.
            val newTabTitle = stringResource(Res.string.shtail_new_tab)
            if (sessions != null) {
                // Состояние drag-reorder вкладок: перетаскивание чипов местами.
                val tabDrag = remember { TabDragState() }
                // rememberUpdatedState: pointerInput пересоздаётся лишь по ключу tabId, поэтому лямбда
                // ids() должна читать свежий список через .value, иначе onDragEnd взял бы устаревший
                // порядок (как сделано для drag хостов).
                val tabIds = rememberUpdatedState(sessions.sessions.map { it.id })
                sessions.sessions.forEachIndexed { index, s ->
                    // Линия вставки перед чипом, над которым сейчас зависла перетаскиваемая вкладка.
                    if (tabDrag.insertLineIndex == index) TabInsertLine()
                    // При сплите чип показывает сфокусированную панель: имя меняется
                    // при переключении фокуса между основной и split-панелью.
                    val focused = if (s.splitOpen && s.focusedSplit) s.splitSession ?: s else s
                    SessionTabChip(
                        name = focused.tabTitle(state.showTerminalTitleOnTabs),
                        dot = sessionDotColor(focused.controller.uiState),
                        split = s.splitOpen,
                        active = s.id == sessions.activeId,
                        onClick = { sessions.activate(s.id) },
                        onClose = { tabDrag.clearBounds(s.id); sessions.close(s.id) },
                        dragging = tabDrag.draggingTabId == s.id,
                        modifier = Modifier
                            .tabBoundsAnchor(tabDrag, s.id)
                            .draggableTab(tabDrag, s.id, ids = { tabIds.value }) { from, to -> sessions.moveTab(from, to) },
                    )
                }
                // Линия вставки в самом конце ряда (перенос вкладки в хвост).
                if (tabDrag.insertLineIndex == sessions.sessions.size) TabInsertLine()
            } else {
                state.tabs.forEachIndexed { i, tab ->
                    SessionTabChip(tab.name, tab.dot, active = i == state.activeTab, onClick = { state.setTab(i) }, onClose = { state.closeTab(i) })
                }
            }
            // «+» создаёт ПУСТУЮ вкладку без сессии (живой режим) и переключает на её терминал-
            // плейсхолдер; первое подключение из сайдбара заполнит её ([SessionsController.connect]).
            // В мок/превью (нет живых сессий) сохраняем прежнее поведение — открыть модалку.
            IconBtn(
                "add",
                onClick = {
                    if (sessions != null) {
                        // Новая пустая вкладка стартует с подвью Terminal (дефолт Session.view);
                        // снимаем оверлей, чтобы показать её терминал-плейсхолдер.
                        sessions.openBlank(newTabTitle)
                        state.clearOverlay()
                    } else {
                        state.openModal()
                    }
                },
                box = 26,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(D.cyan08)
                    .border(1.dp, D.cyan20, RoundedCornerShape(6.dp))
                    .clickable(onClick = onLock ?: state::lock)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Sym("lock_open", size = 14.sp, color = D.cyan)
                Txt(stringResource(Res.string.shell_unlocked), color = D.cyan, size = 11.sp, weight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Вкладка-сессия как сегментная пилюля (стиль референса в палитре night-sea) с выделением в духе
 * редактора: активная — тонкая cyan-полоска по верхней кромке + cyan-подложка с ярким текстом;
 * наведённая неактивная — чуть более светлый фон; покоящаяся — приглушённая полупрозрачная пилюля
 * с тусклым текстом. Слева статус-точка соединения. Крестик закрытия показывается только на активной
 * либо наведённой вкладке; у прочих место зарезервировано пустым боксом, чтобы текст
 * не прыгал при появлении крестика.
 */
@Composable
private fun SessionTabChip(
    name: String,
    dot: Color,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    split: Boolean = false,
    dragging: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    // Общий interactionSource: clickable эмитит hover-события, которые читает collectIsHoveredAsState.
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val showClose = active || hovered
    Row(
        modifier
            // Перетаскиваемый чип приглушаем (alpha), чтобы было видно, что он «оторван» от ряда.
            .alpha(if (dragging) 0.5f else 1f)
            .height(28.dp)
            .clip(shape)
            .background(
                when {
                    active -> D.cyan10
                    hovered -> Color(0x1FFFFFFF)
                    else -> D.card
                },
            )
            .border(1.dp, if (active) D.cyan20 else D.line, shape)
            // Акцентная полоска по верхней кромке активной вкладки (стиль вкладок редактора).
            // drawBehind рисуем поверх фона/обводки, но под контентом; ширину чипа не раздувает.
            .then(
                if (active) {
                    Modifier.drawBehind { drawRect(D.cyan, size = Size(size.width, 2.dp.toPx())) }
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = 11.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(dot)
        // Значок split: вкладка держит две панели.
        if (split) Sym("splitscreen_right", size = 13.sp, color = if (active) D.cyan else D.faint)
        Txt(
            name,
            color = if (active) D.text else D.dim,
            size = 12.sp,
            weight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp),
        )
        if (showClose) {
            IconBtn("close", onClick = onClose, box = 16, icon = 14.sp, tint = if (active) D.dim else D.faint)
        } else {
            Box(Modifier.width(16.dp))
        }
    }
}

/** Вертикальная линия-индикатор позиции вставки при drag-reorder вкладок (cyan-акцент). */
@Composable
private fun TabInsertLine() {
    Box(Modifier.width(2.dp).height(22.dp).clip(RoundedCornerShape(1.dp)).background(D.cyan))
}

@Composable
private fun IconRail(state: DesktopDesignState) {
    val sessions = LocalSessions.current
    // Текущий session-level пункт для подсветки: подвью активной вкладки (живой режим) либо
    // мок-фолбэк [state.view]. Под открытым app-оверлеем session-пункты не подсвечены.
    val currentSessionView = sessions?.active?.view?.asDesktopView() ?: state.view
    Column(
        Modifier
            .width(62.dp)
            .fillMaxHeight()
            .background(D.railBg)
            .padding(horizontal = 7.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        RAIL.forEach { item ->
            val active = if (state.appOverlay != null) item.view == state.appOverlay
            else item.view == currentSessionView
            RailButton(
                icon = item.icon,
                label = stringResource(item.label),
                active = active,
                onClick = {
                    // App-level (Vault/Known/Teams/Snippets) → оверлей. Session-level: в живом режиме
                    // правит ТОЛЬКО подвью активной вкладки (источник правды) + снимает оверлей, не
                    // трогая мок-фолбэк state.view; без сессий — мок-путь через showView.
                    when {
                        item.view.isAppLevel -> state.showView(item.view)
                        sessions != null -> { state.clearOverlay(); sessions.setActiveView(item.view.asSessionView()) }
                        else -> state.showView(item.view)
                    }
                },
            )
        }
        Spacer(Modifier.weight(1f))
        RailButton(icon = "settings", label = stringResource(Res.string.shell_settings), active = false, onClick = state::openSettings)
    }
}

@Composable
private fun RailButton(icon: String, label: String, active: Boolean, onClick: () -> Unit) {
    val fg = if (active) D.cyanBright else D.faint
    Box(Modifier.fillMaxWidth()) {
        if (active) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(vertical = 9.dp)
                    .width(2.dp)
                    .height(20.dp)
                    .background(D.cyan, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (active) D.cyan10 else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Sym(icon, size = 21.sp, color = fg)
            Txt(label, color = fg, size = 9.sp, weight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
        }
    }
}

@Composable
private fun StatusBar() {
    val mono = LocalFonts.current.mono
    // В живом режиме статус слева и throughput отражают активную сессию.
    val sessions = LocalSessions.current
    val active = sessions?.active
    val connected = active?.controller?.uiState is ConnectionUiState.Connected
    val live = sessions != null
    val statusText = if (!live || connected) stringResource(Res.string.shell_status_connected) else stringResource(Res.string.shell_status_disconnected)
    val statusColor = if (!live || connected) D.moss else D.faint
    // Поллер скорости канала активной сессии (когда подключена). remember безусловный — ключи
    // (сессия + флаг connected) пересоздают его при смене сессии/подключения; openThroughput
    // идемпотентен (кэш в ConnectionController).
    val throughput = remember(active, connected) {
        if (connected) active.controller.openThroughput() else null
    }
    val upRate = throughput?.upRate
    val downRate = throughput?.downRate
    // RTT-пинг активной сессии (тот же приём, что throughput); до первого замера/при сбое — null.
    val ping = remember(active, connected) {
        if (connected) active.controller.openPing() else null
    }
    val rttMs = ping?.rttMs
    // Размер сетки — живой cols×rows активного терминала; вне коннекта остаётся мок-метка макета.
    val gridLabel = (sessions?.active?.controller?.uiState as? ConnectionUiState.Connected)
        ?.terminal?.let { "${it.cols} × ${it.rows}" } ?: "80 × 24"
    Row(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(D.ink)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusItem("circle", statusText, color = statusColor, iconSize = 11.sp, mono = mono)
            // RTT-пинг активной сессии live (до первого замера — «—»); в мок-режиме — метка шаблона.
            StatusItem("network_ping", if (live) (rttMs?.let { "$it ms" } ?: "—") else "42 ms", mono = mono)
            // Throughput канала live (до коннекта — «—»); в мок-режиме (офскрин) — метки шаблона.
            StatusItem("arrow_upward", if (live) (upRate?.let { humanRate(it) } ?: "—") else "1.2 KB/s", mono = mono)
            StatusItem("arrow_downward", if (live) (downRate?.let { humanRate(it) } ?: "—") else "8.4 KB/s", mono = mono)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Индикатор sync ведёт по статусу сессии (см. syncIndicator): «Sync online» только при
            // активной сессии + доступном сервере; привязано-но-не-подключено → «Sync paused», и т.д.
            // Прячем, когда sync не настроен / ещё не пинговали.
            val syncC = LocalSync.current
            val ind = syncC?.let { syncIndicatorLocalized(it.status.collectAsState().value, it.serverReachable.collectAsState().value) }
            if (ind != null) {
                StatusItem(
                    ind.icon,
                    ind.label,
                    color = when (ind.level) {
                        SyncIndicatorLevel.OK -> D.moss
                        SyncIndicatorLevel.WARN -> D.amber
                        SyncIndicatorLevel.ERROR -> D.sunset
                    },
                    mono = mono,
                )
            }
            // Версия сервера — live ident активной сессии (до коннекта/если транспорт молчит — «—»).
            StatusItem("memory", if (live) (sessions.active?.controller?.serverVersion ?: "—") else "SSH-2.0-OpenSSH_8.9p1", mono = mono)
            Txt(stringResource(Res.string.shell_status_encoding), color = D.faint, size = 10.5.sp, font = mono)
            Txt(gridLabel, color = D.faint, size = 10.5.sp, font = mono)
        }
    }
}

@Composable
private fun StatusItem(
    icon: String,
    text: String,
    color: Color = D.faint,
    iconSize: TextUnit = 13.sp,
    mono: FontFamily,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Sym(icon, size = iconSize, color = color)
        Txt(text, color = color, size = 10.5.sp, font = mono)
    }
}
