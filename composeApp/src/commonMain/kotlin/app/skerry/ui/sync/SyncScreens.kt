package app.skerry.ui.sync

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import app.skerry.ui.design.D
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sync_back
import app.skerry.ui.generated.resources.sync_connect
import app.skerry.ui.generated.resources.sync_connecting
import app.skerry.ui.generated.resources.sync_connecting_sub
import app.skerry.ui.generated.resources.sync_field_account
import app.skerry.ui.generated.resources.sync_field_choose_password
import app.skerry.ui.generated.resources.sync_field_master_password
import app.skerry.ui.generated.resources.sync_field_pairing_code
import app.skerry.ui.generated.resources.sync_field_repeat_password
import app.skerry.ui.generated.resources.sync_field_server_url
import app.skerry.ui.generated.resources.sync_insecure_url_warning
import app.skerry.ui.generated.resources.sync_join_desc
import app.skerry.ui.generated.resources.sync_join_title
import app.skerry.ui.generated.resources.sync_keep_connected
import app.skerry.ui.generated.resources.sync_keep_connected_sub
import app.skerry.ui.generated.resources.sync_link_this_device
import app.skerry.ui.generated.resources.sync_passwords_mismatch
import app.skerry.ui.generated.resources.sync_placeholder_account
import app.skerry.ui.generated.resources.sync_placeholder_master_password
import app.skerry.ui.generated.resources.sync_placeholder_min_chars
import app.skerry.ui.generated.resources.sync_placeholder_pairing_code
import app.skerry.ui.generated.resources.sync_placeholder_repeat
import app.skerry.ui.generated.resources.sync_placeholder_server_url
import app.skerry.ui.generated.resources.sync_scan_qr
import app.skerry.ui.generated.resources.sync_setup_desc
import app.skerry.ui.generated.resources.sync_setup_title
import app.skerry.ui.generated.resources.sync_storage_choice_desc
import app.skerry.ui.generated.resources.sync_storage_choice_title
import app.skerry.ui.generated.resources.sync_storage_local_sub
import app.skerry.ui.generated.resources.sync_storage_local_title
import app.skerry.ui.generated.resources.sync_storage_server_sub
import app.skerry.ui.generated.resources.sync_storage_server_title
import app.skerry.ui.generated.resources.sync_switch_later
import app.skerry.ui.generated.resources.sync_zero_knowledge_account
import app.skerry.ui.generated.resources.sync_zero_knowledge_password
import app.skerry.ui.nav.PlatformBackHandler
import app.skerry.ui.secure.SecureScreen
import app.skerry.ui.sync.qr.QrScannerScreen
import app.skerry.ui.sync.qr.qrScannerAvailable
import app.skerry.ui.vault.MIN_MASTER_PASSWORD_LENGTH
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.stringResource

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
                    Txt(stringResource(Res.string.sync_back), color = D.dim, size = 13.sp)
                }
                Spacer(Modifier.height(10.dp))

                Txt(stringResource(Res.string.sync_setup_title), color = D.text, size = 22.sp, weight = FontWeight.Bold)
                Txt(
                    stringResource(Res.string.sync_setup_desc),
                    color = D.dim, size = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )

                when (val s = status) {
                    SyncStatus.Busy -> {
                        Spacer(Modifier.height(8.dp))
                        SyncStatusNotice("sync", D.cyanBright, stringResource(Res.string.sync_connecting), stringResource(Res.string.sync_connecting_sub))
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
        stringResource(Res.string.sync_storage_choice_title),
        color = D.text, size = 22.sp, weight = FontWeight.Bold, lineHeight = 28.sp,
    )
    Txt(
        stringResource(Res.string.sync_storage_choice_desc),
        color = D.dim, size = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp, bottom = 22.dp),
    )

    SyncChoiceCard(
        icon = "lock",
        iconColor = D.moss,
        title = stringResource(Res.string.sync_storage_local_title),
        subtitle = stringResource(Res.string.sync_storage_local_sub),
        onClick = onLocal,
    )
    Spacer(Modifier.height(12.dp))
    SyncChoiceCard(
        icon = "cloud_sync",
        iconColor = D.cyanBright,
        title = stringResource(Res.string.sync_storage_server_title),
        subtitle = stringResource(Res.string.sync_storage_server_sub),
        onClick = onSync,
    )

    Row(
        Modifier.padding(top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Sym("shield_lock", size = 14.sp, color = D.moss)
        Txt(stringResource(Res.string.sync_switch_later), color = D.faint, size = 11.5.sp)
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

/**
 * Форма подключения к sync-серверу (сервер + accountId + мастер-пароль + keep connected). Общая для
 * онбординга и экрана Sync: пароль уходит в [SyncCoordinator] CharArray-ом и затирается там.
 */
@Composable
internal fun SyncSetupBody(
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

    SyncFieldLabel(stringResource(Res.string.sync_field_server_url))
    SyncTextField(serverUrl, stringResource(Res.string.sync_placeholder_server_url), KeyboardType.Uri, icon = "dns") { serverUrl = it }
    SyncFieldLabel(stringResource(Res.string.sync_field_account))
    SyncTextField(account, stringResource(Res.string.sync_placeholder_account), KeyboardType.Text, icon = "person") { account = it }
    SyncFieldLabel(stringResource(Res.string.sync_field_master_password))
    SyncTextField(password, stringResource(Res.string.sync_placeholder_master_password), KeyboardType.Password, masked = true, icon = "key") { password = it }

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
            Txt(stringResource(Res.string.sync_keep_connected), color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt(stringResource(Res.string.sync_keep_connected_sub), color = D.faint, size = 11.5.sp)
        }
    }

    // http:// разрешён (локальный тест/LAN без TLS-прокси), но беззащитен перед MITM — предупреждаем явно.
    if (form.isInsecureUrl) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("warning", size = 14.sp, color = D.sunset)
            Txt(stringResource(Res.string.sync_insecure_url_warning), color = D.sunset, size = 11.5.sp, lineHeight = 15.sp)
        }
    }

    if (errorMessage != null) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("error", size = 14.sp, color = D.sunset)
            Txt(errorMessage, color = D.sunset, size = 12.sp)
        }
    }

    PrimaryButton(
        stringResource(Res.string.sync_connect),
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
        Txt(stringResource(Res.string.sync_zero_knowledge_password), color = D.faint, size = 11.sp)
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
                Txt(stringResource(Res.string.sync_back), color = D.dim, size = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            Txt(stringResource(Res.string.sync_join_title), color = D.text, size = 22.sp, weight = FontWeight.Bold)
            Txt(
                stringResource(Res.string.sync_join_desc),
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

    SyncFieldLabel(stringResource(Res.string.sync_field_pairing_code))
    SyncTextField(code, stringResource(Res.string.sync_placeholder_pairing_code), KeyboardType.Text, icon = "qr_code") { code = it }
    if (qrScannerAvailable) {
        GhostButton(stringResource(Res.string.sync_scan_qr), onClick = { showScanner = true }, icon = "photo_camera", modifier = Modifier.padding(top = 10.dp))
    }
    SyncFieldLabel(stringResource(Res.string.sync_field_choose_password))
    SyncTextField(password, stringResource(Res.string.sync_placeholder_min_chars, MIN_MASTER_PASSWORD_LENGTH), KeyboardType.Password, masked = true, icon = "key") { password = it }
    SyncFieldLabel(stringResource(Res.string.sync_field_repeat_password))
    SyncTextField(confirm, stringResource(Res.string.sync_placeholder_repeat), KeyboardType.Password, masked = true, icon = "key") { confirm = it }
    if (confirm.isNotEmpty() && !passwordsMatch) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("error", size = 14.sp, color = D.sunset)
            Txt(stringResource(Res.string.sync_passwords_mismatch), color = D.sunset, size = 11.5.sp)
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
            Txt(stringResource(Res.string.sync_keep_connected), color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt(stringResource(Res.string.sync_keep_connected_sub), color = D.faint, size = 11.5.sp)
        }
    }

    if (errorMessage != null) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("error", size = 14.sp, color = D.sunset)
            Txt(errorMessage, color = D.sunset, size = 12.sp)
        }
    }

    PrimaryButton(
        stringResource(Res.string.sync_link_this_device),
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
        Txt(stringResource(Res.string.sync_zero_knowledge_account), color = D.faint, size = 11.sp)
    }
}

/** Статус-карточка sync-флоу (иконка + заголовок/подзаголовок) — общая для онбординга и экрана Sync. */
@Composable
internal fun SyncStatusNotice(icon: String, iconColor: Color, title: String, subtitle: String) {
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
private fun SyncTextField(
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
