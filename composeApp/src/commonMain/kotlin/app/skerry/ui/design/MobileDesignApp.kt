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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import app.skerry.ui.session.SessionsController
import app.skerry.ui.vault.VaultGate
import app.skerry.ui.vault.VaultGateError
import app.skerry.ui.vault.vaultGateErrorMessage

/**
 * Корень мобильного макета `docs/new/Skerry Mobile.html`, воспроизводимого 1:1 (телефонный
 * аналог [DesktopDesignApp]). Поставляет шрифты через [LocalFonts] и живые бэкенды через
 * [LocalHosts]/[LocalKnownHosts]/[LocalFeatures], держит [MobileDesignState] и собирает каркас:
 * контент текущего таба (или push-экрана) + нижний таб-бар.
 *
 * Если в графе есть [AppDependencies.vault], весь контент закрыт гейтом мастер-пароля
 * ([VaultGate]) с мобильными формами ([MobileCreateScreen]/[MobileUnlockScreen]). Без vault
 * (путь превью) рисуется только chrome с мок-данными — как на desktop.
 *
 * Слайс 1: каркас навигации (5 табов) + lock-экран. Контент табов/push-экранов — плейсхолдеры,
 * наполняются следующими слайсами (Hosts → Terminal → Files → …).
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
) {
    val fonts = DesignFonts(
        ui = rememberSpaceGrotesk(),
        mono = rememberMono(),
        symbols = rememberMaterialSymbols(),
    )
    // Менеджер сессий: либо подан снаружи (офскрин-рендер с фейковым транспортом), либо строится
    // из живого транспорта — один shell на сессию, как в [DesktopDesignApp]/legacy-`MobileApp`.
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
    CompositionLocalProvider(
        LocalFonts provides fonts,
        LocalHosts provides deps.hosts,
        LocalSessions provides liveSessions,
        LocalKnownHosts provides deps.knownHosts,
        LocalFeatures provides features,
        // Инспектор/генератор SSH-ключей + инспектор сертификатов — таб Vault: отпечатки, генерация, разбор cert.
        LocalSshKeyGenerator provides deps.keyGenerator,
        LocalSshCertificateInspector provides deps.certificateInspector,
    ) {
        Box(Modifier.fillMaxSize().background(D.bg)) {
            val vault = deps.vault
            if (vault != null) {
                VaultGate(
                    vault = vault,
                    biometrics = deps.biometrics,
                    createForm = { error, onCreate -> MobileCreateScreen(error, onCreate) },
                    unlockForm = { error, canBio, onUnlock, onBio ->
                        MobileUnlockScreen(error, canBio, onUnlock, onBio)
                    },
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
 * гейтом (пункт «Lock Skerry» в More реально запирает vault); пока используется в следующих слайсах.
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
    // данных ([onVaultUnlocked]), затем перечитываем (как DesktopChrome).
    LaunchedEffect(credentials) {
        onVaultUnlocked()
        credentials?.reload()
    }

    // Хост без привязанного секрета → спрашиваем пароль листом перед подключением.
    var pendingHost by remember { mutableStateOf<Host?>(null) }

    // Стабильная лямбда коннекта (без remember пересоздавалась бы и инвалидировала потребителей
    // [LocalConnectHost]). Живую сессию хоста переиспользуем, мёртвую/отсутствующую — открываем
    // заново ([mobileConnectAction]): на телефоне одна сессия за раз, без накопления сокетов.
    val connectHost = remember(sessions, credentials, state) {
        { host: Host ->
            val existing = sessions?.sessions?.lastOrNull { it.hostId == host.id }
            when (mobileConnectAction(existing?.controller?.uiState)) {
                MobileConnectAction.Resume -> {
                    existing?.let { sessions.activate(it.id) }
                    state.push(MobileRoute.Terminal)
                }
                MobileConnectAction.OpenFresh -> {
                    existing?.let { sessions.close(it.id) }
                    // Одноуровневый резолв: хост → keychain-секрет по credentialId → SshAuth; нет привязки → пароль.
                    val credential = credentials?.find(host.credentialId)
                    if (credential != null) {
                        openMobileSession(sessions, state, host, credential.toSshAuth())
                    } else {
                        pendingHost = host
                    }
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalConnectHost provides connectHost,
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
            pendingHost?.let { host ->
                MobilePasswordSheet(
                    host = host,
                    onDismiss = { pendingHost = null },
                    onConnect = { pw -> pendingHost = null; openMobileSession(sessions, state, host, SshAuth.Password(pw)) },
                )
            }
        }
    }
}

/** Открыть сессию к [host] с [auth] и перейти на push-экран терминала. */
private fun openMobileSession(sessions: SessionsController?, state: MobileDesignState, host: Host, auth: SshAuth) {
    sessions?.open(
        hostId = host.id,
        title = host.label,
        subtitle = host.connectionSubtitle(),
        target = host.toTarget(),
        auth = auth,
    )
    state.push(MobileRoute.Terminal)
}

// ──────────────────────────────── контент (плейсхолдеры слайса 1) ────────────────────────────────

/**
 * Корневой экран текущего таба. Hosts/Files/Vault/More реализованы 1:1; Snippets — заголовок макета
 * (28sp) + плейсхолдер (Phase 2). [onLock] прокидывается в хаб More («Lock Skerry»).
 */
@Composable
private fun MobileTabPane(state: MobileDesignState, onLock: (() -> Unit)?) {
    when (state.tab) {
        MobileTab.Hosts -> MobileHostsScreen(state)
        MobileTab.Files -> MobileFilesScreen()
        MobileTab.Vault -> MobileVaultScreen()
        MobileTab.More -> MobileMoreScreen(state, onLock)
        else -> MobileTabPlaceholder(state.tab)
    }
}

@Composable
private fun MobileTabPlaceholder(tab: MobileTab) {
    val title = when (tab) {
        MobileTab.Hosts -> "Hosts"
        MobileTab.Files -> "Files"
        MobileTab.Snippets -> "Snippets"
        MobileTab.Vault -> "Vault"
        MobileTab.More -> "More"
    }
    Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 6.dp)) {
        Txt(title, color = D.text, size = 28.sp, weight = FontWeight.Bold, letterSpacing = (-0.5).sp)
        Spacer(Modifier.height(20.dp))
        Txt("Скоро — слайс этого раздела", color = D.faint, size = 13.sp)
    }
}

/**
 * Полноэкранный push-экран. HostDetail реализован 1:1 ([MobileHostDetailScreen], слайс 2B);
 * остальные — back-стрелка + заголовок ([MobileRoutePlaceholder]), тело придёт со слайсом раздела.
 */
@Composable
private fun MobileRoutePane(state: MobileDesignState, route: MobileRoute) {
    when (route) {
        MobileRoute.HostDetail -> MobileHostDetailScreen(state)
        MobileRoute.Terminal -> MobileTerminalScreen(state)
        MobileRoute.Ports -> MobilePortsScreen(state)
        MobileRoute.Known -> MobileKnownScreen(state)
        MobileRoute.Team -> MobileRoutePlaceholder(state, "Team")
    }
}

/** Заглушка push-экрана раздела: back-стрелка + заголовок; тело придёт со слайсом раздела. */
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

// ──────────────────────────────── нижний таб-бар ────────────────────────────────

/**
 * Нижний таб-бар `Skerry Mobile.html` (5 табов): полупрозрачный тёмный фон + верхняя cyan-линия,
 * активный таб — cyanBright, остальные — faint. Высота контента ~64dp, ниже — отступ под системную
 * навигацию (home-indicator макета).
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

// ──────────────────────────────── lock-экраны (мобильный визуал) ────────────────────────────────

/**
 * Живая форма разблокировки `Skerry Mobile.html` (режим master-password): логотип, заголовок,
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
) {
    var pwd by remember { mutableStateOf("") }
    val submit = { if (pwd.isNotEmpty()) onUnlock(pwd.toCharArray()) }
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

/** Каркас lock-экрана `Skerry Mobile.html`: радиальный фон, логотип 64dp, заголовок, [fields], футер. */
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

/** Primary-кнопка на всю ширину (cyan-фон, тёмный текст, радиус 13) — стиль кнопок мобильного макета. */
@Composable
private fun MobileWideButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(D.cyan)
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, color = Color(0xFF0A1A26), size = 16.sp, weight = FontWeight.Bold)
    }
}
