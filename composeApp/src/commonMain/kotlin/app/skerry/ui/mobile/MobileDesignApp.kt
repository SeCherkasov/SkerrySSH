package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ai.AiSettingsStore
import app.skerry.ui.ai.aiProviderFactory
import app.skerry.ui.AppDependencies
import app.skerry.ui.ai.AiAssistantController
import app.skerry.shared.terminal.VaultTerminalHistoryStore
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.connection.toSshAuth
import app.skerry.ui.connection.toTarget
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.nav.PlatformBackHandler
import app.skerry.ui.session.SessionsController
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.sync.SyncStatus
import app.skerry.ui.sync.SyncOnboardingScreen
import app.skerry.ui.terminal.LocalTerminalAppearance
import app.skerry.ui.terminal.LocalTerminalTheme
import app.skerry.ui.terminal.TerminalAppearance
import app.skerry.ui.vault.ResetScope
import app.skerry.ui.vault.VaultGate
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_route_team
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.D
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.app.FeatureFlags
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalConnectHost
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.LocalFeatures
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalKnownHosts
import app.skerry.ui.app.LocalOpenSftp
import app.skerry.ui.app.LocalSecurityLog
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.LocalSshCertificateInspector
import app.skerry.ui.app.LocalSshKeyGenerator
import app.skerry.ui.app.LocalSync
import app.skerry.ui.app.LocalTeams
import app.skerry.ui.app.LocalTunnels
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.app.MobileBackAction
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.app.MobileTab
import app.skerry.ui.app.mobileBackAction
import app.skerry.ui.design.rememberMaterialSymbols
import app.skerry.ui.design.rememberMono
import app.skerry.ui.design.rememberSpaceGrotesk

/**
 * Корень мобильного макета. Поставляет шрифты через [LocalFonts] и живые бэкенды через
 * [LocalHosts]/[LocalKnownHosts]/[LocalFeatures], держит [MobileDesignState] и собирает каркас:
 * контент текущего таба (или push-экрана) + нижний таб-бар.
 *
 * Если в графе есть [AppDependencies.vault], весь контент закрыт гейтом мастер-пароля
 * ([VaultGate]) с мобильными формами ([MobileCreateScreen]/[MobileUnlockScreen]). Без vault
 * (путь превью) рисуется только chrome с мок-данными.
 */
@Composable
fun MobileDesignApp(
    deps: AppDependencies = AppDependencies(),
    state: MobileDesignState = remember { MobileDesignState() },
    features: FeatureFlags = FeatureFlags(),
    sessions: SessionsController? = null,
    // AI-контроллер снаружи (офскрин-рендер экрана AI с фейковым провайдером); null — строится
    // из deps.vault ниже, как обычно.
    aiOverride: AiAssistantController? = null,
    // Точка миграции данных при разблокировке vault (паритет с desktop `main`/`DesktopDesignApp`).
    // No-op в превью/офскрине; Android-точка входа подставит вызов VaultMigration, когда появится.
    onVaultUnlocked: () -> Unit = {},
    // Внешняя чистка при безвозвратном сбросе vault (хосты/known_hosts/настройки по [ResetScope]).
    // Явный шов паритета с desktop: Android-точка входа подставит реальную чистку (как `onVaultReset`
    // в desktop `main`), когда мобильный граф vault будет проведён. No-op в превью/офскрине.
    onVaultReset: (ResetScope) -> Unit = {},
) {
    val fonts = DesignFonts(
        ui = rememberSpaceGrotesk(),
        mono = rememberMono(),
        symbols = rememberMaterialSymbols(),
    )
    // Менеджер сессий: либо подан снаружи (офскрин-рендер с фейковым транспортом), либо строится
    // из живого транспорта — один shell на сессию.
    // Свой граф закрываем при dispose; внешний — собственность вызывающего, не трогаем.
    val scope = rememberCoroutineScope()
    val liveSessions = sessions ?: remember(deps.transport, scope, deps.vault) {
        deps.transport?.let { t ->
            // Персист истории команд терминала per-host (для автодополнения) поверх зашифрованного vault.
            val termHistory = deps.vault?.let { VaultTerminalHistoryStore(it) }
            var counter = 0
            SessionsController(
                newId = { "sess-${counter++}" },
                controllerFactory = { ConnectionController(t, scope, history = termHistory) },
            )
        }
    }
    val ownsSessions = sessions == null
    DisposableEffect(liveSessions) { onDispose { if (ownsSessions) liveSessions?.disconnectAll() } }
    // Мемоизируем: LocalTerminalAppearance — staticCompositionLocalOf (сравнение по ссылке); без
    // remember новый инстанс на каждой рекомпозиции форсил бы пересбор поддерева терминала.
    val terminalAppearance = remember(state.terminalFont, state.terminalFontSize, state.terminalLineHeight, state.terminalLetterSpacing) {
        TerminalAppearance(state.terminalFont, state.terminalFontSize, state.terminalLineHeight, state.terminalLetterSpacing)
    }
    // AI-ассистент — паритет с desktop `main`: настройки (провайдер/BYOK/локальная модель) — запись
    // SETTINGS в vault; запрос идёт в облако или в локальный рантайм по выбору маршрутизатора
    // (aiProviderFactory + localAi из платформенного графа). Строится при наличии vault (в превью —
    // null → AI-поверхности показывают мок). На старте vault залочен (settings=дефолт); refresh при
    // разблокировке.
    val builtAi = remember(deps.vault, scope) {
        deps.vault?.let { v ->
            val store = AiSettingsStore(v)
            AiAssistantController(
                initialSettings = store.load(),
                persist = store::save,
                providerFactory = aiProviderFactory(deps.localAi),
                scope = scope,
                reload = store::load,
                localInstalled = { m -> deps.localAi?.installed(m) ?: false },
                models = deps.localAi?.modelsController(scope),
            )
        }
    }
    val ai = aiOverride ?: builtAi
    // AI-настройки живут записью SETTINGS в (синхронизируемом) vault. Контроллер надо перечитывать,
    // когда синк подтянул записи с сервера — иначе BYOK-ключ, настроенный на другом устройстве, в
    // мобильном UI не появится без перезахода. Разблокировку vault обрабатываем ОТДЕЛЬНО, в
    // [MobileChrome] (он композится только за гейтом и заходит в композицию на каждый unlock): вешать
    // refresh на [deps.credentials] нельзя — на Android этот контроллер создаётся сразу и не меняется,
    // так что эффект сработал бы ровно один раз на залоченном старте и вернул бы дефолт («AI сбрасывается»).
    val syncStatus = deps.sync?.status?.collectAsState()?.value
    LaunchedEffect(syncStatus) {
        if (syncStatus is SyncStatus.Online && syncStatus.lastPulled > 0) ai?.refresh()
    }
    // Язык ответов терминального AI = язык интерфейса (см. DesktopDesignApp): провайдер читает
    // применённый тег локали и переустанавливается при смене языка.
    val aiLocaleTag = app.skerry.ui.i18n.LocalAppLocale.current
    androidx.compose.runtime.SideEffect {
        ai?.uiLanguageProvider = { app.skerry.ui.i18n.aiResponseLanguageName(aiLocaleTag) }
    }
    CompositionLocalProvider(
        LocalFonts provides fonts,
        // Внешний вид терминала из настроек (More → Appearance): шрифт + кегль читает TerminalScreen.
        LocalTerminalAppearance provides terminalAppearance,
        // Цветовая тема терминала (More → Appearance → карточки): фон/текст/ANSI/курсор — тот же рендер.
        LocalTerminalTheme provides state.terminalTheme,
        LocalHosts provides deps.hosts,
        LocalSessions provides liveSessions,
        LocalKnownHosts provides deps.knownHosts,
        LocalFeatures provides features,
        // AI-ассистент (BYOK): таб настроек More→AI, per-host политики, терминальный AI-бар.
        LocalAi provides ai,
        // Инспектор/генератор SSH-ключей + инспектор сертификатов — таб Vault: отпечатки, генерация, разбор cert.
        LocalSshKeyGenerator provides deps.keyGenerator,
        LocalSshCertificateInspector provides deps.certificateInspector,
        LocalTunnels provides deps.tunnels,
        // Сохранённые сниппеты — таб Snippets (библиотека команд + запуск в активный терминал).
        LocalSnippets provides deps.snippets,
        // Vault + биометрия — экрану More для тумблера «разблокировка биометрией» (включить/перенастроить).
        LocalVault provides deps.vault,
        LocalVaultBiometrics provides deps.biometrics,
        LocalSecurityLog provides deps.securityLog,
        // Координатор self-hosted sync — push-экран More → «Синхронизация».
        LocalSync provides deps.sync,
        // Teams — push-экран More → «Команда» (шеринг хостов/сниппетов между аккаунтами).
        LocalTeams provides deps.teams,
    ) {
        Box(Modifier.fillMaxSize().background(D.bg)) {
            val vault = deps.vault
            if (vault != null) {
                VaultGate(
                    vault = vault,
                    biometrics = deps.biometrics,
                    securityLog = deps.securityLog,
                    // Порог автоблокировки из настроек: смена рекомпозирует VaultGate и перезапускает
                    // idle-таймер; Never (idleMs == null) выключает его.
                    autoLockIdleMs = state.autoLock.idleMs,
                    onReset = onVaultReset,
                    // onPairingComplete != null (есть sync) — экран создания предлагает «у меня есть код»:
                    // координатор сам создаст vault под выбранным паролем и примет ключ аккаунта.
                    createForm = { error, onCreate, onPairingComplete ->
                        MobileCreateScreen(error, onCreate, deps.sync, onPairingComplete)
                    },
                    unlockForm = { error, canBio, onUnlock, onBio, onForgot ->
                        MobileUnlockScreen(error, canBio, onUnlock, onBio, onForgot)
                    },
                    corruptedForm = { onReset -> MobileCorruptedScreen(onReset) },
                    resetForm = { onConfirm, onCancel -> MobileResetScreen(onConfirm, onCancel) },
                    // Шаг sync в онбординге (ДО биометрии) — только если sync проведён в граф. Подключение
                    // тут принимает dataKey аккаунта, так что биометрия обернёт уже финальный ключ.
                    offerSyncForm = deps.sync?.let { s -> { onDone -> SyncOnboardingScreen(s, onDone) } },
                    offerBiometricForm = { inFlight, onEnable, onSkip -> MobileBiometricOfferScreen(inFlight, onEnable, onSkip) },
                ) { onLock -> MobileChrome(state, onLock, liveSessions, deps.credentials, onVaultUnlocked, ai) }
            } else {
                MobileChrome(state, onLock = null, sessions = liveSessions, credentials = deps.credentials, onVaultUnlocked = onVaultUnlocked, ai = ai)
            }
        }
    }
}

/**
 * Каркас мобильного макета: контент (push-экран либо корневой таб) + нижний таб-бар, видимый
 * только на корневых экранах ([MobileDesignState.showTabs]). [onLock] != null — живой путь за
 * гейтом (пункт «Lock Skerry» в More реально запирает vault).
 */
@Composable
private fun MobileChrome(
    state: MobileDesignState,
    onLock: (() -> Unit)?,
    sessions: SessionsController?,
    credentials: CredentialManagerController?,
    onVaultUnlocked: () -> Unit,
    ai: AiAssistantController?,
) {
    // Keychain-секреты живут в открытом vault — за гейтом мастер-пароля сперва прогоняем миграцию
    // данных ([onVaultUnlocked]), затем перечитываем. [MobileChrome] композится только за гейтом и
    // заходит в композицию на каждую разблокировку, поэтому здесь же перечитываем AI-настройки из
    // теперь-открытого vault (BYOK-ключ хранится записью SETTINGS; на залоченном старте контроллер
    // видел только дефолт). Синканные с другого устройства правки ловит отдельный эффект в MobileDesignApp.
    LaunchedEffect(credentials) {
        onVaultUnlocked()
        credentials?.reload()
        ai?.refresh()
    }

    // Хост без привязанного секрета → спрашиваем пароль листом перед подключением. Вместе с хостом
    // запоминаем пункт назначения (терминал/файлы), чтобы после ввода пароля уйти туда, откуда звали.
    var pending by remember { mutableStateOf<PendingConnect?>(null) }

    // Стабильная лямбда коннекта (без remember пересоздавалась бы и инвалидировала потребителей
    // [LocalConnectHost]/[LocalOpenSftp]). Живую сессию хоста переиспользуем, мёртвую/отсутствующую —
    // открываем заново ([mobileConnectAction]): на телефоне одна сессия за раз, без накопления сокетов.
    // [dest] — куда уйти после подключения: Connect → терминал, SFTP → таб Files (тот же путь, включая
    // запрос пароля, расходится только финальной навигацией [navigateAfterConnect]).
    val connect = remember(sessions, credentials, state) {
        { host: Host, dest: MobileConnectDest ->
            val existing = sessions?.sessions?.lastOrNull { it.hostId == host.id }
            when (mobileConnectAction(existing?.controller?.uiState)) {
                MobileConnectAction.Resume -> {
                    existing?.let { sessions.activate(it.id) }
                    navigateAfterConnect(state, dest)
                }
                MobileConnectAction.OpenFresh -> {
                    existing?.let { sessions.close(it.id) }
                    // Одноуровневый резолв: хост → keychain-секрет по credentialId → SshAuth; нет привязки → пароль.
                    val credential = credentials?.find(host.credentialId)
                    when {
                        // Telnet/Serial без аутентификации — коннектим сразу, без запроса пароля.
                        host.connectionType != ConnectionType.SSH ->
                            openMobileSession(sessions, state, host, SshAuth.Password(""), dest)
                        credential != null ->
                            openMobileSession(sessions, state, host, credential.toSshAuth(), dest)
                        else -> pending = PendingConnect(host, dest)
                    }
                }
            }
        }
    }
    // Производные стабильные лямбды для двух точек входа: Connect (→ терминал) и SFTP (→ push-экран Files).
    val connectHost = remember(connect) { { host: Host -> connect(host, MobileConnectDest.Terminal) } }
    val openSftp = remember(connect) { { host: Host -> connect(host, MobileConnectDest.Files) } }

    CompositionLocalProvider(
        LocalConnectHost provides connectHost,
        LocalOpenSftp provides openSftp,
        // Keychain открытого vault — нужен листу «New connection» для выбора/создания секрета (паритет desktop).
        LocalCredentials provides credentials,
    ) {
        // Системный «назад»/жест ведём по стеку приложения, а не закрываем Activity: закрыть push-экран
        // (→ подлежащий таб), затем уйти с не-Hosts таба на Hosts. На корневом Hosts без оверлеев back не
        // перехватываем — система штатно закрывает приложение. Открытые листы/диалоги гасят свой back
        // СВОИМИ BackHandler (они композятся глубже/позже → перехватывают первыми по LIFO диспетчера),
        // поэтому при открытом оверлее навигационный перехват держим выключенным, чтобы он не сработал
        // следом. Регистрируем до контента — он становится самым низкоприоритетным в стеке back.
        val overlayOpen = pending != null || state.sheetNewConn || state.renamingGroup != null || state.modalOpen
        val backAction = if (overlayOpen) null else mobileBackAction(state.route, state.tab)
        PlatformBackHandler(enabled = backAction != null) {
            when (backAction) {
                MobileBackAction.PopRoute -> state.pop()
                MobileBackAction.GoHome -> state.select(MobileTab.Hosts)
                null -> {}
            }
        }
        // Клавиатуру читаем ДО того, как корневой safeDrawing её потребит (внутри Box WindowInsets.ime
        // уже равен 0). Нужно, чтобы спрятать нижний таб-бар на время ввода: safeDrawing поднимает всё
        // содержимое над клавиатурой, и таб-бар (BottomCenter) иначе всплывал бы полосой прямо над ней.
        val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val route = state.route
            Box(Modifier.fillMaxSize()) {
                if (route != null) {
                    MobileRoutePane(state, route)
                } else {
                    MobileTabPane(state, onLock)
                }
            }
            if (state.showTabs && !keyboardVisible) {
                MobileTabBar(state, Modifier.align(Alignment.BottomCenter))
            }
            if (state.sheetNewConn) {
                MobileNewConnectionSheet(state)
            }
            // Карандаш у заголовка папки → диалог Rename/Delete группы. Профили правит контроллер
            // (renameGroup/deleteGroup), стор синхронизирует свёрнутость. Паритет desktop GroupDialog.
            state.renamingGroup?.let { groupName ->
                val hosts = LocalHosts.current
                MobileGroupRenameDialog(
                    initialName = groupName,
                    onDismiss = state::dismissRenameGroup,
                    onSave = { newName ->
                        hosts?.renameGroup(groupName, newName)
                        state.onGroupRenamed(groupName, newName)
                        state.dismissRenameGroup()
                    },
                    onDelete = {
                        hosts?.deleteGroup(groupName)
                        state.onGroupDeleted(groupName)
                        state.dismissRenameGroup()
                    },
                )
            }
            pending?.let { (host, dest) ->
                MobilePasswordSheet(
                    host = host,
                    onDismiss = { pending = null },
                    onConnect = { pw -> pending = null; openMobileSession(sessions, state, host, SshAuth.Password(pw), dest) },
                )
            }
        }
    }
}

/** Хост, ждущий ввода пароля, вместе с пунктом назначения после подключения (терминал/файлы). */
private data class PendingConnect(val host: Host, val dest: MobileConnectDest)

/** Открыть сессию к [host] с [auth] и перейти к месту назначения ([dest]): терминал или push-экран Files. */
private fun openMobileSession(
    sessions: SessionsController?,
    state: MobileDesignState,
    host: Host,
    auth: SshAuth,
    dest: MobileConnectDest,
) {
    sessions?.open(
        hostId = host.id,
        title = host.label,
        subtitle = host.connectionSubtitle(),
        target = host.toTarget(),
        auth = auth,
    )
    navigateAfterConnect(state, dest)
}

// Контент: корневые табы и push-экраны.

/**
 * Корневой экран текущего таба. [onLock] прокидывается в хаб More («Lock Skerry»).
 */
@Composable
private fun MobileTabPane(state: MobileDesignState, onLock: (() -> Unit)?) {
    when (state.tab) {
        MobileTab.Hosts -> MobileHostsScreen(state)
        MobileTab.Snippets -> MobileSnippetsScreen(state)
        MobileTab.Vault -> MobileVaultScreen(state)
        MobileTab.More -> MobileMoreScreen(state, onLock)
    }
}

/**
 * Полноэкранный push-экран. [MobileRoute.HostDetail] открывает [MobileHostDetailScreen];
 * остальные — back-стрелка + заголовок ([MobileRoutePlaceholder]), тело не реализовано.
 */
@Composable
private fun MobileRoutePane(state: MobileDesignState, route: MobileRoute) {
    when (route) {
        MobileRoute.HostDetail -> MobileHostDetailScreen(state)
        MobileRoute.Terminal -> MobileTerminalScreen(state)
        MobileRoute.Files -> MobileFilesScreen(onBack = state::pop)
        MobileRoute.Ports -> MobilePortsScreen(state)
        MobileRoute.Known -> MobileKnownScreen(state)
        MobileRoute.Team -> MobileTeamsScreen(state)
        MobileRoute.Appearance -> MobileAppearanceScreen(state)
        MobileRoute.Sync -> MobileSyncScreen(state)
        MobileRoute.Ai -> MobileAiScreen(state)
        MobileRoute.Security -> MobileSecurityScreen(state)
    }
}

/** Заглушка push-экрана: back-стрелка + заголовок. */
@Composable
private fun MobileRoutePlaceholder(state: MobileDesignState, title: String) {
    Column(Modifier.fillMaxSize()) {
        MobilePushHeader(title, onBack = state::pop)
    }
}
