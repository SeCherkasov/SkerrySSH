package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import app.skerry.shared.vault.BiometricPrompt
import androidx.compose.runtime.rememberUpdatedState
import app.skerry.ui.nav.PlatformBackHandler
import app.skerry.ui.secure.SecureScreen
import app.skerry.ui.vault.MIN_MASTER_PASSWORD_LENGTH
import app.skerry.ui.vault.VaultGateController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.sync.RemoteDevice
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.sync.SyncSetupForm
import app.skerry.ui.sync.SyncStatus
import app.skerry.ui.sync.qr.QrScannerScreen
import app.skerry.ui.sync.qr.qrScannerAvailable

/**
 * Push-экран More → «Security & sync»: self-hosted синхронизация (Phase 2). В мобильном идиоме
 * (отступление от макета, как Appearance) форма настройки — inline на самом экране, а не модалка.
 * Подключён — статус + «Sync now»/«Disconnect»; не подключён/ошибка — форма (сервер + accountId +
 * мастер-пароль, одно действие «Connect»). Zero-knowledge: пароль уходит в [SyncCoordinator]
 * CharArray-ом и затирается там; здесь держим строкой до отправки и обнуляем сразу после.
 */
@Composable
fun MobileSyncScreen(state: MobileDesignState) {
    Column(Modifier.fillMaxSize().background(D.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Sym("chevron_left", size = 27.sp, color = D.cyanBright, modifier = Modifier.clickable(onClick = state::pop))
            Txt("Sync", color = D.text, size = 18.sp, weight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Txt(
                "End-to-end encrypted sync across your devices. Skerry never sees your data in plaintext.",
                color = D.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            val sync = LocalSync.current
            if (sync == null) {
                MobileSyncStatusCard("cloud_off", D.faint, "Sync unavailable", "Not configured on this device.")
            } else {
                SyncBody(sync)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * Шаг онбординга сразу после создания vault, ДО enroll биометрии
 * (см. [app.skerry.ui.vault.VaultGateState.OfferSync]). Сначала спрашиваем, КАК хранить данные:
 * локально (только это устройство) или через self-hosted sync — и лишь во втором случае показываем
 * форму параметров сервера. Выбор «локально» сразу завершает шаг ([onDone]); подключение sync может
 * принять dataKey аккаунта, поэтому оборачивать биометрию надо уже под финальным ключом — успех
 * ([SyncStatus.Online]) тоже ведёт в [onDone], данные с сервера подтягиваются тут же. Общий экран для
 * обеих платформ (template-fidelity для sync отменён — держим только цветовую схему).
 */
@Composable
fun SyncOnboardingScreen(sync: SyncCoordinator, onDone: () -> Unit) {
    // collectAsState (не ...WithLifecycle): этот экран — commonMain (desktop тоже), а lifecycle-aware
    // сбор есть только на Android. Сбор в STOPPED тут безвреден: auto-lock на ON_STOP запирает vault →
    // controller.state уходит в NeedsUnlock → key(state) рушит этот subtree и отменяет сбор.
    val status by sync.status.collectAsState()
    val currentOnDone by rememberUpdatedState(onDone)

    // Успешное подключение завершает онбординг-шаг. dropWhile + first: ждём ПЕРЕХОД в Online, а не
    // устаревшее значение (после reset координатор гасит сессию асинхронно — на входе ещё мог стоять
    // Online). Один выстрел: Online — data class с меняющимися lastPushed/lastPulled, повторный runSync
    // не должен дёрнуть onDone дважды. Менеджеры списков уже перечитаны координатором (onSynced).
    LaunchedEffect(Unit) {
        sync.status.dropWhile { it is SyncStatus.Online }.first { it is SyncStatus.Online }
        currentOnDone()
    }

    // Если у привязки уже есть сохранённый сервер (Configured / после рестарта — нужен лишь пароль),
    // выбор «локально/sync» уже не имеет смысла: пропускаем развилку и сразу открываем форму.
    var showSyncForm by remember { mutableStateOf(sync.savedConfig != null) }

    // fillMaxSize-скролл-контейнер по фону; контент центрируем по вертикали и ограничиваем по ширине.
    // Center (а не TopCenter): когда форма короче экрана — она по центру (desktop с большим окном и
    // телефон), длиннее (клавиатура) — скроллится. verticalScroll меряет колонку по её высоте.
    Box(Modifier.fillMaxSize().background(D.bg).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(horizontal = 22.dp, vertical = 32.dp)) {
            if (!showSyncForm) {
                SyncStorageChoice(
                    onLocal = onDone,
                    onSync = { showSyncForm = true },
                )
            } else {
                // Back ведёт обратно к развилке — это и есть путь «передумал»: на развилке выбор
                // «Local encrypted storage» завершает онбординг, поэтому отдельная кнопка Skip не нужна.
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { showSyncForm = false }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Sym("chevron_left", size = 20.sp, color = D.dim)
                    Txt("Back", color = D.dim, size = 13.sp)
                }
                Spacer(Modifier.height(10.dp))

                Txt("Set up sync", color = D.text, size = 22.sp, weight = FontWeight.Bold)
                Txt(
                    "Connect this device to your sync server. End-to-end encrypted — Skerry never sees your " +
                        "data in plaintext.",
                    color = D.dim, size = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )

                when (val s = status) {
                    SyncStatus.Busy -> {
                        Spacer(Modifier.height(8.dp))
                        MobileSyncStatusCard("sync", D.cyanBright, "Connecting…", "Talking to your sync server.")
                    }
                    is SyncStatus.Failed -> SyncSetupBody(sync, errorMessage = s.message)
                    else -> SyncSetupBody(sync, errorMessage = null)
                }
            }
        }
    }
}

/**
 * Развилка хранения в онбординге: две карточки-кнопки. «Local encrypted storage» завершает шаг
 * ([onLocal]); «Self-hosted sync server» раскрывает форму параметров ([onSync]). Способ можно сменить
 * позже в настройках — оба варианта обратимы, поэтому выбор здесь ни к чему не обязывает.
 */
@Composable
private fun SyncStorageChoice(onLocal: () -> Unit, onSync: () -> Unit) {
    Txt(
        "Where should Skerry keep your vault?",
        color = D.text, size = 22.sp, weight = FontWeight.Bold, lineHeight = 28.sp,
    )
    Txt(
        "Your hosts and keys are always encrypted. Choose whether they stay only on this device or " +
            "sync across your devices through a server you run.",
        color = D.dim, size = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp, bottom = 22.dp),
    )

    SyncChoiceCard(
        icon = "lock",
        iconColor = D.moss,
        title = "Local encrypted storage",
        subtitle = "Everything stays on this device. No account, no server — the simplest setup.",
        onClick = onLocal,
    )
    Spacer(Modifier.height(12.dp))
    SyncChoiceCard(
        icon = "cloud_sync",
        iconColor = D.cyanBright,
        title = "Self-hosted sync server",
        subtitle = "End-to-end encrypted sync across your devices through a server you run.",
        onClick = onSync,
    )

    Row(
        Modifier.padding(top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Sym("shield_lock", size = 14.sp, color = D.moss)
        Txt("You can switch later in Settings.", color = D.faint, size = 11.5.sp)
    }
}

/** Карточка выбора в развилке хранения: иконка в плашке, заголовок/подзаголовок, шеврон-аффорданс. */
@Composable
private fun SyncChoiceCard(
    icon: String,
    iconColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(D.surfaceDeep)
            .border(1.dp, D.cyan14, RoundedCornerShape(13.dp)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(iconColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Sym(icon, size = 22.sp, color = iconColor)
        }
        Column(Modifier.weight(1f)) {
            Txt(title, color = D.text, size = 15.sp, weight = FontWeight.SemiBold)
            Txt(subtitle, color = D.faint, size = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Sym("chevron_right", size = 22.sp, color = D.dim)
    }
}

/** Промпт включения биометрии при перерегистрации после смены ключа sync (англоязычный, под mobile). */
private val REENROLL_BIOMETRIC_PROMPT = BiometricPrompt(
    title = "Re-enable biometric unlock",
    cancelLabel = "Cancel",
    subtitle = "Confirm your biometrics to bind quick unlock to the new account key.",
)

/**
 * Приглашение перерегистрировать отпечаток после того, как подключение к аккаунту приняло его ключ
 * и сбросило включённую биометрию (см. [SyncCoordinator.biometricResetNeeded]). Видно только когда
 * флаг поднят И на устройстве есть биометрия; «Re-enable» запускает системный промпт и оборачивает
 * dataKey уже под НОВЫМ ключом, «Not now» просто гасит приглашение. Если биометрии на устройстве нет
 * — перерегистрировать нечего, флаг гасим тихо.
 */
@Composable
private fun BiometricReenrollCard(sync: SyncCoordinator) {
    val needed by sync.biometricResetNeeded.collectAsState()
    if (!needed) return
    val vault = LocalVault.current
    val biometrics = LocalVaultBiometrics.current
    if (vault == null || biometrics == null) return
    val controller = remember(vault, biometrics) { VaultGateController(vault, biometrics) }
    if (!controller.canEnableBiometric()) {
        LaunchedEffect(Unit) { sync.acknowledgeBiometricReset() }
        return
    }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(D.surfaceDeep)
            .border(1.dp, D.cyan.copy(alpha = 0.45f), RoundedCornerShape(11.dp)).padding(15.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Sym("fingerprint", size = 22.sp, color = D.cyanBright)
            Column(Modifier.weight(1f)) {
                Txt("Re-enable fingerprint", color = D.text, size = 14.sp, weight = FontWeight.Medium)
                Txt(
                    "Connecting to your account changed the vault key, so biometric unlock was turned off. " +
                        "Set it up again to keep opening Skerry with your fingerprint.",
                    color = D.faint, size = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                "Re-enable",
                icon = "fingerprint",
                enabled = !controller.biometricInFlight,
                onClick = {
                    if (controller.biometricInFlight) return@PrimaryButton
                    // enable оборачивает dataKey под новым ключом; флаг гасим в любом исходе (включил
                    // или отменил промпт) — повторно покажем лишь после следующего принятия ключа.
                    scope.launch {
                        controller.enableBiometric(REENROLL_BIOMETRIC_PROMPT)
                        sync.acknowledgeBiometricReset()
                    }
                },
            )
            GhostButton("Not now", onClick = { sync.acknowledgeBiometricReset() }, fg = D.dim)
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun SyncBody(sync: SyncCoordinator) {
    BiometricReenrollCard(sync)
    when (val status = sync.status.collectAsState().value) {
        is SyncStatus.Online -> {
            MobileSyncStatusCard("cloud_done", D.moss, "Connected · ${status.accountId}", "Pushed ${status.lastPushed} · pulled ${status.lastPulled} this session")
            // Кнопки в том же стиле, что на desktop (ghost, не залитый primary) — паритет платформ.
            // Mobile объединяет вкладки Account+Sync desktop в один экран, поэтому Sync now и
            // Disconnect живут рядом (на desktop они разнесены по вкладкам).
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Sync now", onClick = { sync.syncNow() })
                GhostButton("Disconnect", onClick = { sync.disconnect() }, fg = D.sunset, border = D.sunset.copy(alpha = 0.4f))
            }
            MobileLinkDeviceSection(sync)
            MobileWhatSyncs(sync)
            MobileLinkedDevices(sync)
        }
        SyncStatus.Busy -> MobileSyncStatusCard("sync", D.cyanBright, "Syncing…", "Talking to your sync server.")
        is SyncStatus.Configured -> {
            MobileSyncStatusCard("cloud_off", D.amber, "Linked · ${status.accountId}", "Reconnect with your master password.")
            Spacer(Modifier.height(16.dp))
            SyncSetupBody(sync, errorMessage = null)
        }
        is SyncStatus.Failed -> SyncSetupBody(sync, errorMessage = status.message)
        SyncStatus.Disabled -> SyncSetupBody(sync, errorMessage = null)
    }
}

@Composable
private fun SyncSetupBody(
    sync: SyncCoordinator,
    errorMessage: String?,
) {
    // Предзаполнение из сохранённой привязки (Configured после перезапуска): нужен только пароль.
    val saved = remember { sync.savedConfig }
    var serverUrl by remember { mutableStateOf(saved?.serverUrl ?: "") }
    var account by remember { mutableStateOf(saved?.accountId ?: "") }
    var password by remember { mutableStateOf("") }
    var keepConnected by remember { mutableStateOf(saved?.keepConnected ?: true) }

    val form = SyncSetupForm(serverUrl, account)
    val canSubmit = form.canSubmit(password.length)

    SyncFieldLabel("SERVER URL")
    MobileSyncField(serverUrl, "https://sync.example.com", KeyboardType.Uri, icon = "dns") { serverUrl = it }
    SyncFieldLabel("ACCOUNT")
    MobileSyncField(account, "you@example.com", KeyboardType.Text, icon = "person") { account = it }
    SyncFieldLabel("MASTER PASSWORD")
    MobileSyncField(password, "master password", KeyboardType.Password, masked = true, icon = "key") { password = it }

    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp).clickable { keepConnected = !keepConnected },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
                .background(if (keepConnected) D.cyan else Color.Transparent)
                .border(1.dp, if (keepConnected) D.cyan else D.cyan14, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (keepConnected) Sym("check", size = 14.sp, color = Color(0xFF0A1A26))
        }
        Column(Modifier.weight(1f)) {
            Txt("Keep me connected", color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt("Reconnect automatically after restart.", color = D.faint, size = 11.5.sp)
        }
    }

    // http:// разрешён (локальный тест/LAN без TLS-прокси), но беззащитен перед MITM — предупреждаем явно.
    if (form.isInsecureUrl) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("warning", size = 14.sp, color = D.sunset)
            Txt("Plain http:// — not encrypted in transit. Use https:// for anything but local testing.", color = D.sunset, size = 11.5.sp, lineHeight = 15.sp)
        }
    }

    if (errorMessage != null) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("error", size = 14.sp, color = D.sunset)
            Txt(errorMessage, color = D.sunset, size = 12.sp)
        }
    }

    PrimaryButton(
        "Connect",
        onClick = {
            if (!canSubmit) return@PrimaryButton
            val pw = password.toCharArray() // координатор затрёт массив
            password = ""
            val url = form.normalizedServerUrl
            val acc = form.normalizedAccountId
            // Запуск держит сам координатор (свой scope) — форма уйдёт из композиции на Busy,
            // привязывать к ней корутину нельзя (иначе отмена на полпути). Один вызов: координатор
            // сам решит регистрировать новый аккаунт или входить в существующий.
            sync.connect(url, acc, pw, keepConnected)
        },
        modifier = Modifier.padding(top = 18.dp),
        enabled = canSubmit,
        bg = if (canSubmit) D.cyan else D.cyan.copy(alpha = 0.4f),
        icon = "cloud_sync",
    )
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Sym("shield_lock", size = 14.sp, color = D.moss)
        Txt("Zero-knowledge · password never leaves this device", color = D.faint, size = 11.sp)
    }
}

/**
 * Экран связывания этого устройства по коду (быстрый паринг, вариант B) — показывается с экрана
 * СОЗДАНИЯ vault ([app.skerry.ui.vault.VaultGateState.NeedsCreate]), а не после него. Пароль здесь
 * вводится ОДИН раз: [SyncCoordinator.claimPairing] сам создаёт локальный vault под ним и принимает
 * ключ аккаунта, поэтому повторного ввода и рассинхрона паролей нет. Общий экран для desktop+mobile
 * (template-fidelity для sync отменён — держим лишь цветовую схему). [onBack] возвращает к форме
 * создания, успех ([SyncStatus.Online]) ведёт в [onDone] (гейт уходит к предложению биометрии).
 */
@Composable
fun PairingJoinScreen(sync: SyncCoordinator, onBack: () -> Unit, onDone: () -> Unit) {
    val status by sync.status.collectAsState()
    // onDone создаётся инлайн в гейте (новый инстанс на рекомпозицию) — держим свежую ссылку, чтобы
    // долгоживущий LaunchedEffect(Unit) не звал устаревшую лямбду.
    val currentOnDone by rememberUpdatedState(onDone)

    // Здесь вводится мастер-пароль — защищаем экран от снимков Recent Apps сами, не полагаясь на
    // вызывающего (на desktop SecureScreen — no-op).
    SecureScreen()
    // Системный «назад» = визуальная стрелка Back: вернуться к форме создания, а не закрыть приложение.
    PlatformBackHandler(onBack = onBack)

    // Завершаем шаг лишь при ПЕРЕХОДЕ в Online, а не на устаревшем значении. status — StateFlow, и после
    // сброса vault координатор гасит сессию асинхронно (disconnect в scope.launch): войдя сюда сразу
    // после reset, мы могли бы увидеть ещё не погашенный Online и дёрнуть onDone до того, как claimPairing
    // вообще пересоздаст vault. dropWhile отбрасывает ведущие Online; ждём Online уже ПОСЛЕ нашего claim
    // (он синхронно ставит Busy). Один выстрел: Online — data class со счётчиками, повтор не нужен.
    LaunchedEffect(Unit) {
        sync.status.dropWhile { it is SyncStatus.Online }.first { it is SyncStatus.Online }
        currentOnDone()
    }

    Box(Modifier.fillMaxSize().background(D.bg).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(horizontal = 22.dp, vertical = 32.dp)) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack)
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Sym("chevron_left", size = 20.sp, color = D.dim)
                Txt("Back", color = D.dim, size = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            Txt("Link with a code", color = D.text, size = 22.sp, weight = FontWeight.Bold)
            Txt(
                "On a device that's already signed in, open Settings → Account → Link a device, then " +
                    "scan its QR or paste the code here. The password you choose below encrypts this " +
                    "device's vault and can't be recovered — if you lose it, you'll re-link from scratch.",
                color = D.dim, size = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
            SyncJoinBody(sync, errorMessage = (status as? SyncStatus.Failed)?.message)
        }
    }
}

/**
 * Поля связывания устройства по коду: код (вставка вручную) + опциональный скан QR камерой (только
 * где [qrScannerAvailable]) + пароль, которым [SyncCoordinator.claimPairing] создаст локальный vault
 * и обернёт под него принятый ключ аккаунта. Пароль выбирается здесь впервые (vault ещё нет), поэтому
 * к нему тот же минимум длины, что и при обычном создании. Завершение — общий `status.first { Online }`
 * в [PairingJoinScreen] → onDone.
 */
@Composable
private fun SyncJoinBody(sync: SyncCoordinator, errorMessage: String?) {
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var keepConnected by remember { mutableStateOf(true) }
    var showScanner by remember { mutableStateOf(false) }

    if (showScanner) {
        QrScannerScreen(
            onResult = { code = it; showScanner = false },
            onCancel = { showScanner = false },
        )
        return
    }

    // Пароль создаёт НОВЫЙ vault и невосстановим — требуем подтверждения (как обычная форма создания),
    // иначе опечатка заперла бы устройство, а одноразовый код уже сгорел.
    val passwordsMatch = password == confirm
    val canSubmit = code.isNotBlank() && password.length >= MIN_MASTER_PASSWORD_LENGTH && passwordsMatch

    SyncFieldLabel("PAIRING CODE")
    MobileSyncField(code, "Paste code (sk1.…)", KeyboardType.Text, icon = "qr_code") { code = it }
    if (qrScannerAvailable) {
        GhostButton("Scan QR code", onClick = { showScanner = true }, icon = "photo_camera", modifier = Modifier.padding(top = 10.dp))
    }
    SyncFieldLabel("CHOOSE A PASSWORD FOR THIS DEVICE")
    MobileSyncField(password, "at least $MIN_MASTER_PASSWORD_LENGTH characters", KeyboardType.Password, masked = true, icon = "key") { password = it }
    SyncFieldLabel("REPEAT PASSWORD")
    MobileSyncField(confirm, "repeat to confirm", KeyboardType.Password, masked = true, icon = "key") { confirm = it }
    if (confirm.isNotEmpty() && !passwordsMatch) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("error", size = 14.sp, color = D.sunset)
            Txt("Passwords don't match.", color = D.sunset, size = 11.5.sp)
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp).clickable { keepConnected = !keepConnected },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
                .background(if (keepConnected) D.cyan else Color.Transparent)
                .border(1.dp, if (keepConnected) D.cyan else D.cyan14, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (keepConnected) Sym("check", size = 14.sp, color = Color(0xFF0A1A26))
        }
        Column(Modifier.weight(1f)) {
            Txt("Keep me connected", color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt("Reconnect automatically after restart.", color = D.faint, size = 11.5.sp)
        }
    }

    if (errorMessage != null) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("error", size = 14.sp, color = D.sunset)
            Txt(errorMessage, color = D.sunset, size = 12.sp)
        }
    }

    PrimaryButton(
        "Link this device",
        onClick = {
            if (!canSubmit) return@PrimaryButton
            val pw = password.toCharArray() // координатор затрёт массив
            password = ""
            confirm = ""
            sync.claimPairing(code.trim(), pw, keepConnected)
        },
        modifier = Modifier.padding(top = 18.dp),
        enabled = canSubmit,
        bg = if (canSubmit) D.cyan else D.cyan.copy(alpha = 0.4f),
        icon = "cloud_sync",
    )
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Sym("shield_lock", size = 14.sp, color = D.moss)
        Txt("Zero-knowledge · the account password never travels", color = D.faint, size = 11.sp)
    }
}

/**
 * Что синхронизируется — паритет с desktop (Settings → Sync, секция WHAT SYNCS). Живые тумблеры
 * уровня аккаунта: пишут [SyncSettings] в vault через координатор, изменение уезжает тем же live-push.
 * «SSH keys» и «Terminal history» из макета убраны сознательно (как на desktop): ключи синкаются
 * всегда вместе с «Hosts & groups», а истории терминала как фичи ещё нет.
 */
@Composable
private fun MobileWhatSyncs(sync: SyncCoordinator) {
    val settings = sync.syncSettings.collectAsState().value
    LaunchedEffect(Unit) { sync.refreshSyncSettings() }
    Txt("WHAT SYNCS", color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 26.dp, bottom = 4.dp))
    // onToggle читает актуальное значение из flow, не снимок композиции (stale-closure write-write).
    MobileSyncToggleRow("Hosts & groups", null, on = settings.syncHosts) {
        val current = sync.syncSettings.value
        sync.setSyncSettings(current.copy(syncHosts = !current.syncHosts))
    }
    MobileSyncToggleRow("Snippets", null, on = settings.syncSnippets) {
        val current = sync.syncSettings.value
        sync.setSyncSettings(current.copy(syncSnippets = !current.syncSnippets))
    }
}

/**
 * Mobile-секция «Link a device» (вошедшее устройство): кнопка раскрывает inline-карточку с QR/кодом
 * быстрого паринга (общий [PairingOfferContent]). Inline, а не диалог — мобильный идиом экрана Sync.
 */
@Composable
private fun MobileLinkDeviceSection(sync: SyncCoordinator) {
    var show by remember { mutableStateOf(false) }
    if (!show) {
        GhostButton("Link a device", onClick = { show = true }, icon = "qr_code", modifier = Modifier.padding(top = 12.dp))
        return
    }
    Spacer(Modifier.height(16.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(D.surfaceDeep)
            .border(1.dp, D.cyan14, RoundedCornerShape(13.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("Link a device", color = D.text, size = 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable { show = false }.padding(horizontal = 10.dp, vertical = 5.dp)) {
                Txt("Hide", color = D.dim, size = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        PairingOfferContent(sync)
    }
}

@Composable
private fun MobileSyncToggleRow(label: String, sub: String?, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(label, color = D.text, size = 13.5.sp, weight = FontWeight.Medium)
            if (sub != null) Txt(sub, color = D.faint, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Toggle(on = on, onToggle = onToggle)
    }
}

/**
 * Реальные устройства аккаунта — паритет с desktop (Settings → Account, LINKED DEVICES). На активной
 * сессии сервер всегда возвращает хотя бы текущее устройство, поэтому пустой список = listDevices
 * проглотил ошибку: честно говорим, а не «только вы». Revoke отзывает чужое (с подтверждением вторым
 * кликом) и перечитывает список.
 */
@Composable
private fun MobileLinkedDevices(sync: SyncCoordinator) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<RemoteDevice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(sync, reload) {
        loading = true
        // Отозванные устройства больше не привязаны — не показываем (сервер хранит строку с revoked=true).
        // Текущее устройство всегда первым (sortedByDescending стабилен — порядок прочих сохраняется).
        devices = sync.listDevices().filter { !it.revoked }.sortedByDescending { it.current }
        loading = false
    }

    Txt("LINKED DEVICES", color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 26.dp, bottom = 6.dp))
    when {
        loading -> Txt("Loading devices…", color = D.faint, size = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        devices.isEmpty() -> Txt("Couldn't load devices. Try Sync now.", color = D.amber, size = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        devices.size == 1 && devices.first().current -> Txt("Only this device so far.", color = D.faint, size = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        else -> devices.forEach { d ->
            MobileDeviceRow(
                device = d,
                onRevoke = if (d.current || d.revoked) null else {
                    { scope.launch { if (sync.revokeDevice(d.id)) reload++ } }
                },
            )
        }
    }
}

@Composable
private fun MobileDeviceRow(device: RemoteDevice, onRevoke: (() -> Unit)?) {
    // Отзыв необратим из UI (устройство переподключается мастер-паролем) — подтверждаем вторым кликом.
    var confirming by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Sym("devices", size = 20.sp, color = D.dim)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(device.name, color = D.text, size = 13.5.sp, weight = FontWeight.Medium)
                if (device.current) Txt("● this device", color = D.moss, size = 10.sp)
            }
            Txt(if (device.current) "linked · this device" else "linked device", color = D.faint, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (onRevoke != null) {
            if (confirming) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MobileRevokeChip("Confirm", D.sunset) { confirming = false; onRevoke() }
                    MobileRevokeChip("Cancel", D.dim) { confirming = false }
                }
            } else {
                MobileRevokeChip("Revoke", D.dim) { confirming = true }
            }
        }
    }
}

@Composable
private fun MobileRevokeChip(label: String, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(7.dp)).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Txt(label, color = fg, size = 12.sp)
    }
}

@Composable
private fun MobileSyncStatusCard(icon: String, iconColor: Color, title: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).border(1.dp, D.cyan08, RoundedCornerShape(11.dp)).padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Sym(icon, size = 22.sp, color = iconColor)
        Column(Modifier.weight(1f)) {
            Txt(title, color = D.text, size = 14.sp, weight = FontWeight.Medium)
            Txt(subtitle, color = D.faint, size = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun SyncFieldLabel(text: String) {
    Txt(text, color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun MobileSyncField(
    value: String,
    placeholder: String,
    keyboardType: KeyboardType,
    masked: Boolean = false,
    icon: String? = null,
    onChange: (String) -> Unit,
) {
    val ui = LocalFonts.current.ui
    val style = remember(ui) { TextStyle(color = D.text, fontSize = 15.sp, fontFamily = ui, lineHeight = 20.sp) }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(D.cyan),
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (masked) KeyboardType.Password else keyboardType),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(11.dp)).padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (icon != null) Sym(icon, size = 18.sp, color = D.faint)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 15.sp)
                    inner()
                }
            }
        },
    )
}
