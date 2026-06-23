package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.SshPublicKeyInfo
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.shortFingerprint
import app.skerry.ui.vault.VaultCategoryKind
import app.skerry.ui.vault.VaultPresentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Корневой таб Vault мобильного макета `docs/new/Skerry Mobile.html`: заголовок + баннер E2E +
 * секция «SSH keys» со списком ключей. Экран read-only (в макете нет FAB/кнопок добавления —
 * генерация/импорт остаются desktop-фичей до соответствующего мобильного слайса).
 *
 * Живой путь ([LocalCredentials] != null, за гейтом vault) рисует реальные SSH-ключи открытого
 * keychain: отпечаток считается инспектором [LocalSshKeyGenerator], «used by N hosts» — из
 * [LocalHosts] (паритет live-карточек desktop [VaultView]). Превью/офскрин ([LocalCredentials] ==
 * null) — статичные карточки ровно из макета (DEFAULT/ROTATE) для сверки 1:1.
 */
@Composable
fun MobileVaultScreen() {
    when (val credentials = LocalCredentials.current) {
        null -> MobileVaultMock()
        else -> MobileVaultLive(credentials)
    }
}

// ──────────────────────────────────────── живой путь ────────────────────────────────────────

@Composable
private fun MobileVaultLive(credentials: CredentialManagerController) {
    val mono = LocalFonts.current.mono
    val generator = LocalSshKeyGenerator.current
    val hosts = LocalHosts.current?.hosts ?: emptyList()
    val keys = VaultPresentation.credentialsIn(VaultCategoryKind.SSH_KEYS, credentials.credentials)

    // «used by N hosts» по каждому ключу — пересчёт только при смене набора ключей/каталога хостов.
    val usedByById = remember(keys, hosts) {
        keys.associate { it.id to VaultPresentation.usedByLabel(VaultPresentation.hostsUsing(it.id, hosts).size) }
    }

    // Отпечатки считаем инспектором вне главного потока: разбор PEM (BouncyCastle) — CPU-bound, и в
    // списке это набегало бы на каждый ключ при первом показе таба, фризя UI. До готовности строка
    // ключа рисуется без отпечатка (только used-by). [keys] включает secret (data class), поэтому
    // смена ключа на тот же id переинспектирует. Приватный ключ/passphrase в UI не утекают — наружу
    // идёт лишь публичный [SshPublicKeyInfo] (отпечаток/тип/публичная строка).
    val infoById = remember { mutableStateMapOf<String, SshPublicKeyInfo>() }
    LaunchedEffect(keys, generator) {
        keys.forEach { credential ->
            val secret = credential.secret as? CredentialSecret.PrivateKey ?: return@forEach
            val info = withContext(Dispatchers.Default) { generator?.inspect(secret.privateKeyPem, secret.passphrase) }
            if (info != null) infoById[credential.id] = info
        }
    }

    MobileVaultScaffold {
        if (keys.isEmpty()) {
            MobileVaultEmpty()
        } else {
            keys.forEach { credential ->
                key(credential.id) {
                    MobileKeyCard(credential.label, infoById[credential.id], usedByById[credential.id].orEmpty(), mono)
                }
            }
        }
    }
}

/** Карточка SSH-ключа: иконка + имя (+ бейдж типа) + строка «отпечаток · used by N hosts». */
@Composable
private fun MobileKeyCard(
    label: String,
    info: SshPublicKeyInfo?,
    usedBy: String,
    mono: FontFamily,
) {
    val meta = if (info != null) "${shortFingerprint(info.fingerprintSha256)} · $usedBy" else usedBy
    MobileKeyCardShell(
        iconColor = D.cyanBright,
        cardBg = D.cyan.copy(alpha = 0.05f),
        cardBorder = D.cyan.copy(alpha = 0.16f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Txt(label, color = D.text, size = 14.5.sp, weight = FontWeight.SemiBold)
            info?.keyTypeLabel?.let { Badge(it, bg = D.moss.copy(alpha = 0.16f), fg = D.moss, size = 9.sp) }
        }
        Txt(meta, color = D.dim, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun MobileVaultEmpty() {
    Box(Modifier.fillMaxWidth().padding(top = 50.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym("key", size = 26.sp, color = D.faint)
            Txt("No SSH keys yet", color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
            Txt("Generate a key on desktop — it's sealed in your vault and syncs here.", color = D.faint, size = 11.5.sp)
        }
    }
}

// ──────────────────────────────────────── превью (макет) ────────────────────────────────────────

/** Статичный макет таба Vault (офскрин/превью без открытого keychain) — карточки 1:1 с `Skerry Mobile.html`. */
@Composable
private fun MobileVaultMock() {
    val mono = LocalFonts.current.mono
    MobileVaultScaffold {
        MobileKeyCardShell(D.cyanBright, D.cyan.copy(alpha = 0.05f), D.cyan.copy(alpha = 0.16f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Txt("id_ed25519", color = D.text, size = 14.5.sp, weight = FontWeight.SemiBold)
                Badge("DEFAULT", bg = D.cyan.copy(alpha = 0.14f), fg = D.cyanBright, size = 9.sp)
            }
            Txt("SHA256:8c3F1a…Qz9pK", color = D.dim, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 3.dp))
        }
        MobileKeyCardShell(D.dim, Color(0x08FFFFFF), D.cyan.copy(alpha = 0.08f)) {
            Txt("id_rsa_legacy", color = D.text, size = 14.5.sp, weight = FontWeight.SemiBold)
            Txt("SHA256:2dE7b…Lm4xR", color = D.dim, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 3.dp))
        }
        MobileKeyCardShell(D.sunset, D.sunset.copy(alpha = 0.05f), D.sunset.copy(alpha = 0.22f), icon = "warning") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Txt("deploy_ci", color = D.text, size = 14.5.sp, weight = FontWeight.SemiBold)
                Badge("ROTATE", bg = D.sunset.copy(alpha = 0.16f), fg = D.sunset, size = 9.sp)
            }
            Txt("created 412 days ago", color = D.dim, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

// ──────────────────────────────────────── общий каркас ────────────────────────────────────────

/** Заголовок + баннер E2E + секция «SSH KEYS», в которую [content] кладёт карточки ключей. */
@Composable
private fun MobileVaultScaffold(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(D.bg).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 10.dp)) {
            Txt("Vault", color = D.text, size = 28.sp, weight = FontWeight.Bold, letterSpacing = (-0.5).sp)
        }
        Row(
            Modifier.padding(horizontal = 22.dp).padding(bottom = 14.dp)
                .clip(RoundedCornerShape(12.dp)).background(D.moss.copy(alpha = 0.06f))
                .border(1.dp, D.moss.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Sym("lock", size = 19.sp, color = D.moss)
            Txt("End-to-end encrypted · sealed with your master password", color = D.dim, size = 12.sp)
        }
        Txt(
            "SSH KEYS",
            color = D.faint,
            size = 12.sp,
            weight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 4.dp),
        )
        Column(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            content()
        }
        Spacer(Modifier.height(96.dp))
    }
}

/** Скелет карточки ключа: квадратная иконка-плитка слева + колонка [content] справа. */
@Composable
private fun MobileKeyCardShell(
    iconColor: Color,
    cardBg: Color,
    cardBorder: Color,
    icon: String = "key",
    content: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(iconColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Sym(icon, size = 20.sp, color = iconColor)
        }
        Column(Modifier.weight(1f)) { content() }
    }
}
