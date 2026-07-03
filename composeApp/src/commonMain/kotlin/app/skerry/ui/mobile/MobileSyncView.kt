package app.skerry.ui.mobile

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.ui.vault.VaultGateController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.sync.RemoteDevice
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.sync.SyncSetupBody
import app.skerry.ui.sync.SyncStatus
import app.skerry.ui.sync.syncFailureText
import app.skerry.ui.sync.SyncStatusNotice
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sync_title
import app.skerry.ui.generated.resources.sync_mobile_intro
import app.skerry.ui.generated.resources.sync_unavailable_title
import app.skerry.ui.generated.resources.sync_unavailable_subtitle
import app.skerry.ui.generated.resources.sync_connecting_sub
import app.skerry.ui.generated.resources.sync_syncing
import app.skerry.ui.generated.resources.sync_reenroll_title
import app.skerry.ui.generated.resources.sync_reenroll_desc
import app.skerry.ui.generated.resources.sync_reenroll_action
import app.skerry.ui.generated.resources.sync_not_now
import app.skerry.ui.generated.resources.sync_connected_title
import app.skerry.ui.generated.resources.sync_session_stats
import app.skerry.ui.generated.resources.sync_sync_now
import app.skerry.ui.generated.resources.sync_disconnect
import app.skerry.ui.generated.resources.sync_linked_title
import app.skerry.ui.generated.resources.sync_reconnect_password
import app.skerry.ui.generated.resources.sync_what_syncs
import app.skerry.ui.generated.resources.sync_what_hosts
import app.skerry.ui.generated.resources.sync_what_snippets
import app.skerry.ui.generated.resources.sync_link_device
import app.skerry.ui.generated.resources.sync_hide
import app.skerry.ui.generated.resources.sync_linked_devices
import app.skerry.ui.generated.resources.sync_loading_devices
import app.skerry.ui.generated.resources.sync_load_devices_failed
import app.skerry.ui.generated.resources.sync_only_this_device
import app.skerry.ui.generated.resources.sync_this_device_badge
import app.skerry.ui.generated.resources.sync_device_linked_current
import app.skerry.ui.generated.resources.sync_device_linked
import app.skerry.ui.generated.resources.sync_confirm
import app.skerry.ui.generated.resources.sync_cancel
import app.skerry.ui.generated.resources.sync_revoke
import app.skerry.ui.generated.resources.stail_reenroll_prompt_title
import app.skerry.ui.generated.resources.stail_reenroll_prompt_cancel
import app.skerry.ui.generated.resources.stail_reenroll_prompt_subtitle
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.D
import app.skerry.ui.design.GhostButton
import app.skerry.ui.app.LocalSync
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.sync.PairingOfferContent
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt

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
        MobilePushHeader(stringResource(Res.string.sync_title), onBack = state::pop)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Txt(
                stringResource(Res.string.sync_mobile_intro),
                color = D.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            val sync = LocalSync.current
            if (sync == null) {
                SyncStatusNotice("cloud_off", D.faint, stringResource(Res.string.sync_unavailable_title), stringResource(Res.string.sync_unavailable_subtitle))
            } else {
                SyncBody(sync)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

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
    // Строки промпта резолвим в composable-scope (stringResource нельзя в onClick-лямбде) и держим готовый
    // объект для передачи в enableBiometric.
    val reenrollPrompt = BiometricPrompt(
        title = stringResource(Res.string.stail_reenroll_prompt_title),
        cancelLabel = stringResource(Res.string.stail_reenroll_prompt_cancel),
        subtitle = stringResource(Res.string.stail_reenroll_prompt_subtitle),
    )

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(D.surfaceDeep)
            .border(1.dp, D.cyan.copy(alpha = 0.45f), RoundedCornerShape(11.dp)).padding(15.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Sym("fingerprint", size = 22.sp, color = D.cyanBright)
            Column(Modifier.weight(1f)) {
                Txt(stringResource(Res.string.sync_reenroll_title), color = D.text, size = 14.sp, weight = FontWeight.Medium)
                Txt(
                    stringResource(Res.string.sync_reenroll_desc),
                    color = D.faint, size = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                stringResource(Res.string.sync_reenroll_action),
                icon = "fingerprint",
                enabled = !controller.biometricInFlight,
                onClick = {
                    if (controller.biometricInFlight) return@PrimaryButton
                    // enable оборачивает dataKey под новым ключом; флаг гасим в любом исходе (включил
                    // или отменил промпт) — повторно покажем лишь после следующего принятия ключа.
                    scope.launch {
                        controller.enableBiometric(reenrollPrompt)
                        sync.acknowledgeBiometricReset()
                    }
                },
            )
            GhostButton(stringResource(Res.string.sync_not_now), onClick = { sync.acknowledgeBiometricReset() }, fg = D.dim)
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun SyncBody(sync: SyncCoordinator) {
    BiometricReenrollCard(sync)
    when (val status = sync.status.collectAsState().value) {
        is SyncStatus.Online -> {
            SyncStatusNotice("cloud_done", D.moss, stringResource(Res.string.sync_connected_title, status.accountId), stringResource(Res.string.sync_session_stats, status.lastPushed, status.lastPulled))
            // Кнопки в том же стиле, что на desktop (ghost, не залитый primary) — паритет платформ.
            // Mobile объединяет вкладки Account+Sync desktop в один экран, поэтому Sync now и
            // Disconnect живут рядом (на desktop они разнесены по вкладкам).
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(stringResource(Res.string.sync_sync_now), onClick = { sync.syncNow() })
                GhostButton(stringResource(Res.string.sync_disconnect), onClick = { sync.disconnect() }, fg = D.sunset, border = D.sunset.copy(alpha = 0.4f))
            }
            MobileLinkDeviceSection(sync)
            MobileWhatSyncs(sync)
            MobileLinkedDevices(sync)
        }
        SyncStatus.Busy -> SyncStatusNotice("sync", D.cyanBright, stringResource(Res.string.sync_syncing), stringResource(Res.string.sync_connecting_sub))
        is SyncStatus.Configured -> {
            SyncStatusNotice("cloud_off", D.amber, stringResource(Res.string.sync_linked_title, status.accountId), stringResource(Res.string.sync_reconnect_password))
            Spacer(Modifier.height(16.dp))
            SyncSetupBody(sync, errorMessage = null)
        }
        is SyncStatus.Failed -> SyncSetupBody(sync, errorMessage = syncFailureText(status))
        SyncStatus.Disabled -> SyncSetupBody(sync, errorMessage = null)
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
    Txt(stringResource(Res.string.sync_what_syncs), color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 26.dp, bottom = 4.dp))
    // onToggle читает актуальное значение из flow, не снимок композиции (stale-closure write-write).
    MobileSyncToggleRow(stringResource(Res.string.sync_what_hosts), null, on = settings.syncHosts) {
        val current = sync.syncSettings.value
        sync.setSyncSettings(current.copy(syncHosts = !current.syncHosts))
    }
    MobileSyncToggleRow(stringResource(Res.string.sync_what_snippets), null, on = settings.syncSnippets) {
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
        GhostButton(stringResource(Res.string.sync_link_device), onClick = { show = true }, icon = "qr_code", modifier = Modifier.padding(top = 12.dp))
        return
    }
    Spacer(Modifier.height(16.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(D.surfaceDeep)
            .border(1.dp, D.cyan14, RoundedCornerShape(13.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(Res.string.sync_link_device), color = D.text, size = 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable { show = false }.padding(horizontal = 10.dp, vertical = 5.dp)) {
                Txt(stringResource(Res.string.sync_hide), color = D.dim, size = 12.sp)
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

    Txt(stringResource(Res.string.sync_linked_devices), color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 26.dp, bottom = 6.dp))
    when {
        loading -> Txt(stringResource(Res.string.sync_loading_devices), color = D.faint, size = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        devices.isEmpty() -> Txt(stringResource(Res.string.sync_load_devices_failed), color = D.amber, size = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        devices.size == 1 && devices.first().current -> Txt(stringResource(Res.string.sync_only_this_device), color = D.faint, size = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
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
                if (device.current) Txt(stringResource(Res.string.sync_this_device_badge), color = D.moss, size = 10.sp)
            }
            Txt(if (device.current) stringResource(Res.string.sync_device_linked_current) else stringResource(Res.string.sync_device_linked), color = D.faint, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (onRevoke != null) {
            if (confirming) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MobileRevokeChip(stringResource(Res.string.sync_confirm), D.sunset) { confirming = false; onRevoke() }
                    MobileRevokeChip(stringResource(Res.string.sync_cancel), D.dim) { confirming = false }
                }
            } else {
                MobileRevokeChip(stringResource(Res.string.sync_revoke), D.dim) { confirming = true }
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
