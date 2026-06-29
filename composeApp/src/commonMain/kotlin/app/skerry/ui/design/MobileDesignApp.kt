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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshAuth
import app.skerry.ui.AppDependencies
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.connection.toSshAuth
import app.skerry.ui.connection.toTarget
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.secure.SecureScreen
import app.skerry.ui.session.SessionsController
import app.skerry.ui.terminal.LocalTerminalAppearance
import app.skerry.ui.terminal.TerminalAppearance
import app.skerry.ui.vault.RESET_CONFIRM_WORD
import app.skerry.ui.vault.ResetScope
import app.skerry.ui.vault.VaultGate
import app.skerry.ui.vault.VaultGateError
import app.skerry.ui.vault.vaultGateErrorMessage

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
    val liveSessions = sessions ?: remember(deps.transport, scope) {
        deps.transport?.let { t ->
            var counter = 0
            SessionsController(newId = { "sess-${counter++}" }, controllerFactory = { ConnectionController(t, scope) })
        }
    }
    val ownsSessions = sessions == null
    DisposableEffect(liveSessions) { onDispose { if (ownsSessions) liveSessions?.disconnectAll() } }
    // Мемоизируем: LocalTerminalAppearance — staticCompositionLocalOf (сравнение по ссылке); без
    // remember новый инстанс на каждой рекомпозиции форсил бы пересбор поддерева терминала.
    val terminalAppearance = remember(state.terminalFont, state.terminalFontSize) {
        TerminalAppearance(state.terminalFont, state.terminalFontSize)
    }
    CompositionLocalProvider(
        LocalFonts provides fonts,
        // Внешний вид терминала из настроек (More → Appearance): шрифт + кегль читает TerminalScreen.
        LocalTerminalAppearance provides terminalAppearance,
        LocalHosts provides deps.hosts,
        LocalSessions provides liveSessions,
        LocalKnownHosts provides deps.knownHosts,
        LocalFeatures provides features,
        // Инспектор/генератор SSH-ключей + инспектор сертификатов — таб Vault: отпечатки, генерация, разбор cert.
        LocalSshKeyGenerator provides deps.keyGenerator,
        LocalSshCertificateInspector provides deps.certificateInspector,
        LocalTunnels provides deps.tunnels,
        // Сохранённые сниппеты — таб Snippets (библиотека команд + запуск в активный терминал).
        LocalSnippets provides deps.snippets,
        // Vault + биометрия — экрану More для тумблера «разблокировка биометрией» (включить/перенастроить).
        LocalVault provides deps.vault,
        LocalVaultBiometrics provides deps.biometrics,
        // Координатор self-hosted sync — push-экран More → «Security & sync».
        LocalSync provides deps.sync,
    ) {
        Box(Modifier.fillMaxSize().background(D.bg)) {
            val vault = deps.vault
            if (vault != null) {
                VaultGate(
                    vault = vault,
                    biometrics = deps.biometrics,
                    onReset = onVaultReset,
                    createForm = { error, onCreate -> MobileCreateScreen(error, onCreate) },
                    unlockForm = { error, canBio, onUnlock, onBio, onForgot ->
                        MobileUnlockScreen(error, canBio, onUnlock, onBio, onForgot)
                    },
                    corruptedForm = { onReset -> MobileCorruptedScreen(onReset) },
                    resetForm = { onConfirm, onCancel -> MobileResetScreen(onConfirm, onCancel) },
                    // Шаг sync в онбординге (ДО биометрии) — только если sync проведён в граф. Подключение
                    // тут принимает dataKey аккаунта, так что биометрия обернёт уже финальный ключ.
                    offerSyncForm = deps.sync?.let { s -> { onDone -> SyncOnboardingScreen(s, onDone) } },
                    offerBiometricForm = { inFlight, onEnable, onSkip -> MobileBiometricOfferScreen(inFlight, onEnable, onSkip) },
                ) { onLock -> MobileChrome(state, onLock, liveSessions, deps.credentials, onVaultUnlocked) }
            } else {
                MobileChrome(state, onLock = null, sessions = liveSessions, credentials = deps.credentials, onVaultUnlocked = onVaultUnlocked)
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
) {
    // Keychain-секреты живут в открытом vault — за гейтом мастер-пароля сперва прогоняем миграцию
    // данных ([onVaultUnlocked]), затем перечитываем.
    LaunchedEffect(credentials) {
        onVaultUnlocked()
        credentials?.reload()
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
                    if (credential != null) {
                        openMobileSession(sessions, state, host, credential.toSshAuth(), dest)
                    } else {
                        pending = PendingConnect(host, dest)
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
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val route = state.route
            Box(Modifier.fillMaxSize()) {
                if (route != null) {
                    MobileRoutePane(state, route)
                } else {
                    MobileTabPane(state, onLock)
                }
            }
            if (state.showTabs) {
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
        MobileRoute.Team -> MobileRoutePlaceholder(state, "Team")
        MobileRoute.Appearance -> MobileAppearanceScreen(state)
        MobileRoute.Sync -> MobileSyncScreen(state)
    }
}

/** Заглушка push-экрана: back-стрелка + заголовок. */
@Composable
private fun MobileRoutePlaceholder(state: MobileDesignState, title: String) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Sym("chevron_left", size = 27.sp, color = D.cyanBright, modifier = Modifier.clickable(onClick = state::pop))
            Txt(title, color = D.text, size = 18.sp, weight = FontWeight.Bold)
        }
    }
}

// Нижний таб-бар.

/**
 * Нижний таб-бар (5 табов): полупрозрачный тёмный фон + верхняя cyan-линия,
 * активный таб — cyanBright, остальные — faint. Высота контента ~64dp, ниже — отступ под системную
 * навигацию (home-indicator).
 */
@Composable
private fun MobileTabBar(state: MobileDesignState, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Color(0xEB0A1620)),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(D.cyan08))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 9.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            MobileTab.entries.forEach { tab ->
                MobileTabItem(tab, active = state.tab == tab && state.route == null) { state.select(tab) }
            }
        }
    }
}

@Composable
private fun MobileTabItem(tab: MobileTab, active: Boolean, onClick: () -> Unit) {
    val color = if (active) D.cyanBright else D.faint
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Sym(tab.icon, size = 24.sp, color = color)
        Txt(tab.label, color = color, size = 10.sp, weight = if (active) FontWeight.SemiBold else FontWeight.Normal)
    }
}

// Lock-экраны (мобильный визуал).

/**
 * Живая форма разблокировки (режим master-password): логотип, заголовок,
 * поле пароля, кнопка Unlock на всю ширину, строка биометрии и футер. PIN-режим макета отложен
 * (нет бэкенда passcode) — см. бэклог нового дизайна. Пароль уходит в [onUnlock] как [CharArray]
 * и затирается контроллером; кнопка/строка биометрии видна только при [canUseBiometric].
 */
@Composable
fun MobileUnlockScreen(
    error: VaultGateError?,
    canUseBiometric: Boolean,
    onUnlock: (CharArray) -> Unit,
    onBiometric: () -> Unit,
    onForgotPassword: () -> Unit,
) {
    var pwd by remember { mutableStateOf("") }
    val submit = { if (pwd.isNotEmpty()) onUnlock(pwd.toCharArray()) }
    // Защита ввода мастер-пароля от снимков экрана/превью в Recent Apps (Android; desktop — no-op).
    SecureScreen()
    MobileLockScaffold(title = "Skerry is locked", subtitle = "Enter your master password", error = error) {
        MobileLockField(pwd, { pwd = it }, "Master password", ImeAction.Done, onSubmit = submit)
        Spacer(Modifier.height(14.dp))
        MobileWideButton("Unlock", onClick = submit)
        if (canUseBiometric) {
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onBiometric),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Sym("fingerprint", size = 24.sp, color = D.cyanBright)
                Txt("Use biometrics", color = D.dim, size = 14.sp)
            }
        }
        // Тупик забытого пароля расшивается только сбросом (zero-knowledge); ведёт на [MobileResetScreen].
        Spacer(Modifier.height(18.dp))
        Txt(
            "Forgot your master password?",
            color = D.faint,
            size = 13.sp,
            modifier = Modifier.clickable(onClick = onForgotPassword),
        )
    }
}

/**
 * Живая форма создания мастер-пароля при первом запуске (мобильный визуал): два поля + кнопка
 * на всю ширину. Валидация (длина/совпадение) — в `VaultGateController`; оба буфера уходят как
 * [CharArray] и затираются там же.
 */
@Composable
fun MobileCreateScreen(error: VaultGateError?, onCreate: (CharArray, CharArray) -> Unit) {
    var pwd by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val submit = { if (pwd.isNotEmpty() && confirm.isNotEmpty()) onCreate(pwd.toCharArray(), confirm.toCharArray()) }
    // Защита ввода мастер-пароля от снимков экрана/превью в Recent Apps (Android; desktop — no-op).
    SecureScreen()
    MobileLockScaffold(
        title = "Set a master password",
        subtitle = "It encrypts this vault and never leaves the device — there is no recovery.",
        error = error,
    ) {
        MobileLockField(pwd, { pwd = it }, "Master password", ImeAction.Next)
        Spacer(Modifier.height(12.dp))
        MobileLockField(confirm, { confirm = it }, "Repeat password", ImeAction.Done, onSubmit = submit)
        Spacer(Modifier.height(14.dp))
        MobileWideButton("Create vault", onClick = submit)
    }
}

/**
 * Разовое предложение включить биометрию сразу после создания vault (мобильный визуал). Vault уже
 * открыт — шаг необязательный: «Use biometrics» запускает системный промпт, «Not now» пускает в
 * приложение. Кнопки гаснут на время промпта ([inFlight]). Биометрию всегда можно настроить позже в More.
 */
@Composable
fun MobileBiometricOfferScreen(inFlight: Boolean, onEnable: () -> Unit, onSkip: () -> Unit) {
    MobileLockScaffold(
        title = "Quick unlock",
        subtitle = "Open Skerry with your fingerprint instead of the master password.",
        error = null,
    ) {
        MobileWideButton("Use biometrics", onClick = { if (!inFlight) onEnable() }, enabled = !inFlight)
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.fillMaxWidth().clickable(enabled = !inFlight, onClick = onSkip).padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Txt("Not now", color = D.dim, size = 14.sp, weight = FontWeight.Medium)
        }
    }
}

/**
 * Экран повреждённого файла vault (мобильный визуал, паритет [DesktopCorruptedScreen]). Файл не
 * читается → пароль ввести нельзя; единственное действие — уйти на подтверждение сброса ([onReset]).
 */
@Composable
fun MobileCorruptedScreen(onReset: () -> Unit) {
    MobileLockScaffold(
        title = "Storage is damaged",
        subtitle = "The vault file can't be read or decrypted. To use Skerry again you'll need to reset it.",
        error = null,
    ) {
        MobileWideButton("Reset Skerry", onClick = onReset)
    }
}

/**
 * Экран подтверждения безвозвратного сброса (мобильный визуал, паритет [DesktopResetScreen]): выбор
 * объёма ([ResetScope]) + type-to-confirm — danger-кнопка активна только когда вписано
 * [RESET_CONFIRM_WORD]. Удаление необратимо (zero-knowledge), поэтому барьер от случайного тапа жёсткий.
 */
@Composable
fun MobileResetScreen(onConfirm: (ResetScope) -> Unit, onCancel: () -> Unit) {
    var scope by remember { mutableStateOf(ResetScope.SecretsOnly) }
    var confirmText by remember { mutableStateOf("") }
    val canConfirm = confirmText.trim() == RESET_CONFIRM_WORD
    MobileLockScaffold(
        title = "Reset Skerry",
        subtitle = "This is permanent. Saved passwords, keys and identities are erased — there is no recovery.",
        error = null,
    ) {
        MobileResetScopeRow(
            selected = scope == ResetScope.SecretsOnly,
            title = "Secrets only",
            subtitle = "Keep host profiles and known_hosts.",
            onSelect = { scope = ResetScope.SecretsOnly },
        )
        Spacer(Modifier.height(10.dp))
        MobileResetScopeRow(
            selected = scope == ResetScope.Everything,
            title = "Erase everything",
            subtitle = "Also remove host profiles, known_hosts and settings.",
            onSelect = { scope = ResetScope.Everything },
        )
        Spacer(Modifier.height(14.dp))
        MobileLockPlainField(confirmText, { confirmText = it }, "Type $RESET_CONFIRM_WORD to confirm", ImeAction.Done) {
            if (canConfirm) onConfirm(scope)
        }
        Spacer(Modifier.height(14.dp))
        MobileWideButton(
            "Reset permanently",
            onClick = { if (canConfirm) onConfirm(scope) },
            bg = if (canConfirm) D.storm else Color(0x14FFFFFF),
            fg = if (canConfirm) Color(0xFF0A1A26) else D.faint,
            enabled = canConfirm,
        )
        Spacer(Modifier.height(16.dp))
        // Единственный выход из необратимого экрана — тап-зона на всю ширину (паритет desktop),
        // иначе промах пальцем оставляет на danger-экране без очевидного отступления.
        Txt(
            "Cancel",
            color = D.dim,
            size = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onCancel)
                .padding(vertical = 10.dp),
        )
    }
}

/** Строка выбора объёма сброса (мобильный визуал): радио-точка + заголовок/подзаголовок, кликабельна целиком. */
@Composable
private fun MobileResetScopeRow(selected: Boolean, title: String, subtitle: String, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .border(1.dp, if (selected) D.cyan else D.line, RoundedCornerShape(11.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(9.dp))
                .border(1.dp, if (selected) D.cyan else D.faint, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(D.cyan))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, color = D.text, size = 14.sp, weight = FontWeight.Medium)
            Txt(subtitle, color = D.dim, size = 12.sp, lineHeight = 16.sp)
        }
    }
}

/** Каркас lock-экрана: радиальный фон, логотип 64dp, заголовок, [fields], футер. */
@Composable
private fun MobileLockScaffold(
    title: String,
    subtitle: String,
    error: VaultGateError?,
    fields: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(colors = listOf(Color(0xFF132838), Color(0xFF06121C))))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 30.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.radialGradient(listOf(Color(0xFF142634), Color(0xFF0A141B), Color(0xFF05090D)))),
            contentAlignment = Alignment.Center,
        ) {
            BrandMark(size = 64.dp)
        }
        Spacer(Modifier.height(16.dp))
        Txt(title, color = D.text, size = 20.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Txt(subtitle, color = D.dim, size = 13.sp)
        Spacer(Modifier.height(26.dp))
        Column(Modifier.width(300.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            fields()
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Txt(vaultGateErrorMessage(error), color = D.storm, size = 12.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("shield_lock", size = 14.sp, color = D.faint)
            Txt("Never leaves this device", color = D.faint, size = 11.sp)
        }
    }
}

/** Поле мастер-пароля макета: иконка-замок + скрытый ввод; Enter (Done) вызывает [onSubmit]. */
@Composable
private fun MobileLockField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    imeAction: ImeAction,
    onSubmit: () -> Unit = {},
) {
    // Рамка/иконка — в decorationBox, чтобы клик по всей площади поля ставил каретку.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        textStyle = TextStyle(color = D.text, fontSize = 15.sp, fontFamily = LocalFonts.current.ui),
        cursorBrush = SolidColor(D.cyan),
        keyboardOptions = KeyboardOptions(imeAction = imeAction, keyboardType = KeyboardType.Password),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }, onGo = { onSubmit() }),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(D.surface2)
                    .border(1.dp, D.cyan.copy(alpha = 0.16f), RoundedCornerShape(13.dp))
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Sym("lock", size = 19.sp, color = D.faint)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 15.sp)
                    inner()
                }
            }
        },
    )
}

/** Плоское поле ввода макета (без маскирования/иконки) — для type-to-confirm на экране сброса. */
@Composable
private fun MobileLockPlainField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    imeAction: ImeAction,
    onSubmit: () -> Unit = {},
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = D.text, fontSize = 15.sp, fontFamily = LocalFonts.current.ui),
        cursorBrush = SolidColor(D.cyan),
        // Слово подтверждения — заглавное (RESET): глушим автокоррекцию (иначе IME перепишет в «Reset»
        // и сравнение никогда не совпадёт) и сразу включаем верхний регистр.
        keyboardOptions = KeyboardOptions(
            imeAction = imeAction,
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.Characters,
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }, onGo = { onSubmit() }),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(D.surface2)
                    .border(1.dp, D.cyan.copy(alpha = 0.16f), RoundedCornerShape(13.dp))
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 15.sp)
                    inner()
                }
            }
        },
    )
}

/**
 * Primary-кнопка на всю ширину (по умолчанию cyan-фон, тёмный текст, радиус 13) — стиль кнопок
 * мобильного макета. [bg]/[fg]/[enabled] переопределяются для danger-варианта (сброс vault): пока
 * не вписано слово подтверждения, кнопка приглушена и не кликается.
 */
@Composable
private fun MobileWideButton(
    label: String,
    onClick: () -> Unit,
    bg: Color = D.cyan,
    fg: Color = Color(0xFF0A1A26),
    enabled: Boolean = true,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, color = fg, size = 16.sp, weight = FontWeight.Bold)
    }
}
