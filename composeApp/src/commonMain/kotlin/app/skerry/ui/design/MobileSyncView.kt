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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.sync.SyncSetupForm
import app.skerry.ui.sync.SyncSetupMode
import app.skerry.ui.sync.SyncStatus

/**
 * Push-экран More → «Security & sync»: self-hosted синхронизация (Phase 2). В мобильном идиоме
 * (отступление от макета, как Appearance) форма настройки — inline на самом экране, а не модалка.
 * Подключён — статус + «Sync now»/«Disconnect»; не подключён/ошибка — форма (сервер + accountId +
 * мастер-пароль, режим New account/Existing). Zero-knowledge: пароль уходит в [SyncCoordinator]
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

@Composable
private fun SyncBody(sync: SyncCoordinator) {
    when (val status = sync.status.collectAsState().value) {
        is SyncStatus.Online -> {
            MobileSyncStatusCard("cloud_done", D.moss, "Connected · ${status.accountId}", "Pushed ${status.lastPushed} · pulled ${status.lastPulled} this session")
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Sync now", onClick = { sync.syncNow() }, icon = "sync")
                GhostButton("Disconnect", onClick = { sync.disconnect() }, fg = D.sunset, border = D.sunset.copy(alpha = 0.4f))
            }
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
    var mode by remember { mutableStateOf(if (saved != null) SyncSetupMode.Login else SyncSetupMode.Register) }
    var serverUrl by remember { mutableStateOf(saved?.serverUrl ?: "") }
    var account by remember { mutableStateOf(saved?.accountId ?: "") }
    var password by remember { mutableStateOf("") }
    var keepConnected by remember { mutableStateOf(saved?.keepConnected ?: true) }

    val form = SyncSetupForm(serverUrl, account)
    val canSubmit = form.canSubmit(password.length)

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(D.bg).border(1.dp, D.cyan08, RoundedCornerShape(9.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SyncModeTab("New account", mode == SyncSetupMode.Register, Modifier.weight(1f)) { mode = SyncSetupMode.Register }
        SyncModeTab("Existing", mode == SyncSetupMode.Login, Modifier.weight(1f)) { mode = SyncSetupMode.Login }
    }

    SyncFieldLabel("SERVER URL")
    MobileSyncField(serverUrl, "https://sync.example.com", KeyboardType.Uri) { serverUrl = it }
    SyncFieldLabel("ACCOUNT")
    MobileSyncField(account, "you@example.com", KeyboardType.Text) { account = it }
    SyncFieldLabel("MASTER PASSWORD")
    MobileSyncField(password, "master password", KeyboardType.Password, masked = true) { password = it }

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

    val label = if (mode == SyncSetupMode.Register) "Create account" else "Log in"
    PrimaryButton(
        label,
        onClick = {
            if (!canSubmit) return@PrimaryButton
            val pw = password.toCharArray() // координатор затрёт массив
            password = ""
            val url = form.normalizedServerUrl
            val acc = form.normalizedAccountId
            // Запуск держит сам координатор (свой scope) — форма уйдёт из композиции на Busy,
            // привязывать к ней корутину нельзя (иначе отмена на полпути).
            when (mode) {
                SyncSetupMode.Register -> sync.register(url, acc, pw, keepConnected)
                SyncSetupMode.Login -> sync.login(url, acc, pw, keepConnected)
            }
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
private fun SyncModeTab(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(7.dp)).background(if (active) D.cyan10 else Color.Transparent).clickable(onClick = onClick).padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, color = if (active) D.cyanBright else D.dim, size = 13.sp, weight = if (active) FontWeight.SemiBold else FontWeight.Normal)
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
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(11.dp)).padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 15.sp)
                inner()
            }
        },
    )
}
