package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.identity.CredentialDraft
import app.skerry.ui.identity.CredentialKind
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.shortFingerprint
import app.skerry.ui.vault.VaultCategoryKind
import app.skerry.ui.vault.VaultPresentation
import app.skerry.ui.vault.exportTextFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Корневой таб Vault мобильного макета. Шаблон `docs/new/Skerry Mobile.html` показывал лишь read-only
 * список SSH-ключей — по запросу доведён до функционального паритета с desktop ([VaultView]): три
 * keychain-категории (SSH keys/Passwords/Certificates) переключаются пилюлями (на телефоне вместо
 * sidebar), генерация ключа/добавление пароля/импорт сертификата — теми же диалогами, что и desktop;
 * тап по секрету открывает лист деталей (публичный ключ/отпечаток/principals, used-by-хосты) с
 * Copy/Export/Delete.
 *
 * Живой путь ([LocalCredentials] != null) рисует реальный открытый keychain; превью/офскрин без
 * keychain ([LocalCredentials] == null) — статичные карточки из макета для сверки.
 */
@Composable
fun MobileVaultScreen(state: MobileDesignState) {
    when (val credentials = LocalCredentials.current) {
        null -> MobileVaultMock()
        else -> MobileVaultLive(state, credentials)
    }
}

// ──────────────────────────────────────── живой путь ────────────────────────────────────────

@Composable
private fun MobileVaultLive(state: MobileDesignState, credentials: CredentialManagerController) {
    val mono = LocalFonts.current.mono
    val generator = LocalSshKeyGenerator.current
    val inspector = LocalSshCertificateInspector.current
    val hostsController = LocalHosts.current
    val hosts = hostsController?.hosts ?: emptyList()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val allCreds = credentials.credentials

    var category by remember { mutableStateOf(VaultCategoryKind.SSH_KEYS) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showGenerate by remember { mutableStateOf(false) }
    var showAddPassword by remember { mutableStateOf(false) }
    var showImportCert by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Credential?>(null) }

    val credItems = VaultPresentation.credentialsIn(category, allCreds)
    val selectedCred = credItems.firstOrNull { it.id == selectedId }

    // Любой открытый оверлей (диалоги создания/удаления + лист деталей) прячет таб-бар: иначе он
    // плавает поверх центрированного диалога и перекрывает нижние поля ввода над клавиатурой.
    // DisposableEffect снимает флаг при уходе с таба, чтобы таб-бар не остался скрытым.
    val modalOpen = showGenerate || showAddPassword || showImportCert || pendingDelete != null || selectedCred != null
    SideEffect { state.modalOpen = modalOpen }
    DisposableEffect(Unit) { onDispose { state.modalOpen = false } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(D.bg).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 10.dp)) {
                Txt("Vault", color = D.text, size = 28.sp, weight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            }
            MobileVaultBanner()
            MobileCategoryPills(category, allCreds) { category = it; selectedId = null }
            MobileVaultAction(
                category = category,
                canGenerate = generator != null,
                canImportCert = inspector != null,
                onGenerate = { showGenerate = true },
                onAddPassword = { showAddPassword = true },
                onImportCert = { showImportCert = true },
            )
            Column(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                if (credItems.isEmpty()) {
                    MobileVaultEmpty(category)
                } else {
                    credItems.forEach { credential ->
                        MobileSecretCard(
                            credential = credential,
                            usedByCount = VaultPresentation.hostsUsing(credential.id, hosts).size,
                            mono = mono,
                            onClick = { selectedId = credential.id },
                        )
                    }
                }
            }
            Spacer(Modifier.height(96.dp))
        }

        if (showGenerate && generator != null) {
            GenerateKeyDialog(
                onDismiss = { showGenerate = false },
                onCreate = { name, type ->
                    showGenerate = false
                    category = VaultCategoryKind.SSH_KEYS
                    // Генерация (особенно RSA-4096) дорогая — уводим с main-потока; save трогает state.
                    scope.launch {
                        val key = withContext(Dispatchers.Default) { generator.generate(type, comment = name) }
                        selectedId = credentials.save(
                            CredentialDraft(label = name, kind = CredentialKind.PRIVATE_KEY, privateKeyPem = key.privateKeyPem),
                        )
                    }
                },
            )
        }
        if (showAddPassword) {
            AddPasswordDialog(
                onDismiss = { showAddPassword = false },
                onCreate = { name, password ->
                    selectedId = credentials.save(CredentialDraft(label = name, kind = CredentialKind.PASSWORD, password = password))
                    category = VaultCategoryKind.PASSWORDS
                    showAddPassword = false
                },
            )
        }
        if (showImportCert && inspector != null) {
            ImportCertificateDialog(
                inspector = inspector,
                onDismiss = { showImportCert = false },
                onCreate = { name, pem, cert, passphrase ->
                    selectedId = credentials.save(
                        CredentialDraft(
                            label = name,
                            kind = CredentialKind.CERTIFICATE,
                            privateKeyPem = pem,
                            certificate = cert,
                            passphrase = passphrase ?: "",
                        ),
                    )
                    category = VaultCategoryKind.CERTIFICATES
                    showImportCert = false
                },
            )
        }
        pendingDelete?.let { victim ->
            val bound = VaultPresentation.hostsUsing(victim.id, hosts)
            DeleteSecretDialog(
                label = victim.label,
                boundHostCount = bound.size,
                onDismiss = { pendingDelete = null },
                onConfirm = {
                    // Каскад целостен только при живом hostsController (за гейтом он всегда есть): сперва
                    // развязываем хосты, чтобы они не ссылались на удалённый секрет, затем удаляем секрет.
                    val hc = hostsController
                    if (hc != null) {
                        bound.forEach { host -> hc.save(host.unbindCredential()) }
                        credentials.delete(victim.id)
                        if (selectedId == victim.id) selectedId = null
                    }
                    pendingDelete = null
                },
            )
        }
        selectedCred?.let { credential ->
            MobileSecretDetailSheet(
                credential = credential,
                hosts = VaultPresentation.hostsUsing(credential.id, hosts),
                mono = mono,
                onCopy = { clipboard.setText(AnnotatedString(it)) },
                onExport = { name, content -> scope.launch { exportTextFile(name, content) } },
                onDelete = { pendingDelete = credential },
                onDismiss = { selectedId = null },
            )
        }
    }
}

// ──────────────────────────────────────── шапка/категории/действие ──────────────────────────

@Composable
private fun MobileVaultBanner() {
    Row(
        Modifier.padding(horizontal = 22.dp).padding(bottom = 12.dp)
            .clip(RoundedCornerShape(12.dp)).background(D.moss.copy(alpha = 0.06f))
            .border(1.dp, D.moss.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym("lock", size = 19.sp, color = D.moss)
        Txt("End-to-end encrypted · sealed with your master password", color = D.dim, size = 12.sp)
    }
}

/** Пилюли-переключатели keychain-категорий (на телефоне замена desktop-sidebar) с живыми счётчиками. */
@Composable
private fun MobileCategoryPills(active: VaultCategoryKind, credentials: List<Credential>, onSelect: (VaultCategoryKind) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VaultPresentation.sidebarCategories.forEach { kind ->
            val on = kind == active
            val count = VaultPresentation.count(kind, credentials)
            Row(
                Modifier.clip(RoundedCornerShape(20.dp))
                    .background(if (on) D.cyan.copy(alpha = 0.12f) else Color(0x0AFFFFFF))
                    .border(1.dp, if (on) D.cyan.copy(alpha = 0.3f) else Color.Transparent, RoundedCornerShape(20.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(kind) }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Sym(kind.icon, size = 15.sp, color = if (on) D.cyanBright else D.dim)
                Txt(kind.title, color = if (on) D.cyanBright else D.dim, size = 12.5.sp, weight = FontWeight.Medium)
                Txt(count.toString(), color = D.faint, size = 10.sp)
            }
        }
    }
}

/** Контекстная кнопка действия категории — генерация ключа / добавить пароль / импорт сертификата. */
@Composable
private fun MobileVaultAction(
    category: VaultCategoryKind,
    canGenerate: Boolean,
    canImportCert: Boolean,
    onGenerate: () -> Unit,
    onAddPassword: () -> Unit,
    onImportCert: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
        when (category) {
            VaultCategoryKind.SSH_KEYS -> if (canGenerate) PrimaryButton("Generate key", onClick = onGenerate, icon = "add", modifier = Modifier.fillMaxWidth())
            VaultCategoryKind.PASSWORDS -> PrimaryButton("Add password", onClick = onAddPassword, icon = "add", modifier = Modifier.fillMaxWidth())
            VaultCategoryKind.CERTIFICATES -> if (canImportCert) PrimaryButton("Import certificate", onClick = onImportCert, icon = "add", modifier = Modifier.fillMaxWidth())
        }
    }
}

// ──────────────────────────────────────── карточка секрета ──────────────────────────────────

/** Карточка keychain-секрета (ключ/пароль/сертификат) в списке категории. */
@Composable
private fun MobileSecretCard(credential: Credential, usedByCount: Int, mono: FontFamily, onClick: () -> Unit) {
    val generator = LocalSshKeyGenerator.current
    val inspector = LocalSshCertificateInspector.current
    val usedBy = VaultPresentation.usedByLabel(usedByCount)
    val (icon, iconColor) = when (credential.secret) {
        is CredentialSecret.Certificate -> "workspace_premium" to D.moss
        is CredentialSecret.PrivateKey -> "key" to D.cyanBright
        is CredentialSecret.Password -> "password" to D.dim
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x08FFFFFF))
            .border(1.dp, D.cyan.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(iconColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Sym(icon, size = 20.sp, color = iconColor) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Txt(credential.label, color = D.text, size = 14.5.sp, weight = FontWeight.SemiBold)
                when (credential.secret) {
                    is CredentialSecret.PrivateKey -> {
                        val info = rememberKeyInfo(credential, generator)
                        info?.keyTypeLabel?.let { Badge(it, bg = D.moss.copy(alpha = 0.16f), fg = D.moss, size = 9.sp) }
                    }
                    is CredentialSecret.Certificate -> {
                        val info = rememberCertInfo(credential, inspector)
                        info?.keyTypeLabel?.let { Badge(it, bg = D.moss.copy(alpha = 0.16f), fg = D.moss, size = 9.sp) }
                        if (info?.expired == true) Badge("EXPIRED", bg = D.sunset.copy(alpha = 0.16f), fg = D.sunset, size = 9.sp)
                    }
                    is CredentialSecret.Password -> Unit
                }
            }
            val meta = when (credential.secret) {
                is CredentialSecret.PrivateKey -> {
                    val info = rememberKeyInfo(credential, generator)
                    if (info != null) "${shortFingerprint(info.fingerprintSha256)} · $usedBy" else usedBy
                }
                is CredentialSecret.Certificate -> {
                    val info = rememberCertInfo(credential, inspector)
                    when {
                        info == null -> "Certificate · $usedBy"
                        info.principals.isEmpty() -> "any principal · $usedBy"
                        else -> "${info.principals.joinToString(", ")} · $usedBy"
                    }
                }
                is CredentialSecret.Password -> "Password · $usedBy"
            }
            Txt(meta, color = D.dim, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 3.dp))
        }
        Sym("chevron_right", size = 20.sp, color = D.faint)
    }
}

@Composable
private fun MobileVaultEmpty(category: VaultCategoryKind) {
    Box(Modifier.fillMaxWidth().padding(top = 50.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym(category.icon, size = 26.sp, color = D.faint)
            val (title, hint) = when (category) {
                VaultCategoryKind.SSH_KEYS -> "No SSH keys yet" to "Generate a key — it's sealed in your vault and ready to attach to hosts."
                VaultCategoryKind.PASSWORDS -> "No passwords yet" to "Add a password — it's stored encrypted and reusable across hosts."
                VaultCategoryKind.CERTIFICATES -> "No certificates yet" to "Import a CA-signed certificate with its private key — sealed in your vault."
            }
            Txt(title, color = D.text, size = 13.sp, weight = FontWeight.SemiBold)
            Txt(hint, color = D.faint, size = 11.5.sp)
        }
    }
}

// ──────────────────────────────────────── лист деталей секрета ──────────────────────────────

/**
 * Нижний лист деталей выбранного секрета — мобильный аналог desktop-панели [VaultView] LiveSecretDetail:
 * шапка (иконка/имя/подтип), публичный ключ + отпечаток (ключ) либо тело сертификата, used-by-хосты,
 * кнопки Copy/Export/Delete. Наружу отдаём только публичный материал (открытый ключ/cert), приватный
 * ключ/пароль — лишь в Copy/Export по явному действию пользователя.
 */
@Composable
private fun MobileSecretDetailSheet(
    credential: Credential,
    hosts: List<Host>,
    mono: FontFamily,
    onCopy: (String) -> Unit,
    onExport: (name: String, content: String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val generator = LocalSshKeyGenerator.current
    val inspector = LocalSshCertificateInspector.current
    val secret = credential.secret
    val keyInfo = rememberKeyInfo(credential, generator)
    val certInfo = rememberCertInfo(credential, inspector)
    val (icon, color, tinted) = when (secret) {
        is CredentialSecret.Certificate -> Triple("workspace_premium", D.moss, true)
        is CredentialSecret.PrivateKey -> Triple("key", D.cyanBright, true)
        is CredentialSecret.Password -> Triple("password", D.dim, false)
    }
    val subtitle = when (secret) {
        is CredentialSecret.Certificate -> certInfo?.keyTypeLabel?.let { "$it certificate" } ?: "Certificate"
        is CredentialSecret.PrivateKey -> keyInfo?.keyTypeLabel ?: "Private key"
        is CredentialSecret.Password -> "Password"
    }
    // Скрим на весь экран; тап мимо листа закрывает. Сам лист гасит клик, чтобы не закрываться.
    Box(
        Modifier.fillMaxSize().background(Color(0xB3060E16))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.82f)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(D.surface2).border(1.dp, D.cyan14, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .imePadding(),
        ) {
            // Хват + закрыть
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(D.lineStrong))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(Modifier.padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    SecretIcon(icon, tinted = tinted, color = color, size = 40)
                    Column {
                        Txt(credential.label, color = D.text, size = 15.sp, weight = FontWeight.SemiBold)
                        Txt(subtitle, color = D.dim, size = 11.5.sp)
                    }
                }
                when (secret) {
                    is CredentialSecret.Certificate -> CertificateDetailBody(certInfo, mono)
                    is CredentialSecret.PrivateKey -> {
                        DetailLabel("Public key")
                        Box(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(7.dp)).background(D.terminalBg).border(1.dp, D.cyan.copy(alpha = 0.1f), RoundedCornerShape(7.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Txt(keyInfo?.publicKeyOpenSsh ?: "Key could not be read", color = D.dim, size = 10.5.sp, font = mono, lineHeight = 16.sp)
                        }
                        DetailLabel("Fingerprint")
                        Txt(keyInfo?.fingerprintSha256 ?: "—", color = D.textBright, size = 11.sp, font = mono, modifier = Modifier.padding(bottom = 16.dp))
                    }
                    is CredentialSecret.Password -> Unit
                }
                UsedByHosts(hosts, mono)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (secret) {
                        is CredentialSecret.Certificate -> {
                            PrimaryButton("Copy certificate", onClick = { onCopy(secret.certificate) }, icon = "content_copy", modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GhostButton("Export", onClick = { onExport("${credential.label}-cert.pub", secret.certificate) }, modifier = Modifier.weight(1f))
                                GhostButton("Delete", onClick = onDelete, fg = D.sunset, border = D.sunset.copy(alpha = 0.3f), modifier = Modifier.weight(1f))
                            }
                        }
                        is CredentialSecret.PrivateKey -> {
                            PrimaryButton("Copy public key", onClick = { keyInfo?.let { onCopy(it.publicKeyOpenSsh) } }, icon = "content_copy", modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GhostButton("Export", onClick = { keyInfo?.let { onExport("${credential.label}.pub", it.publicKeyOpenSsh) } }, modifier = Modifier.weight(1f))
                                GhostButton("Delete", onClick = onDelete, fg = D.sunset, border = D.sunset.copy(alpha = 0.3f), modifier = Modifier.weight(1f))
                            }
                        }
                        is CredentialSecret.Password -> {
                            PrimaryButton("Copy password", onClick = { onCopy(secret.password) }, icon = "content_copy", modifier = Modifier.fillMaxWidth())
                            GhostButton("Delete", onClick = onDelete, fg = D.sunset, border = D.sunset.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ──────────────────────────────────────── превью (макет) ────────────────────────────────────────

/** Статичный макет таба Vault (офскрин/превью без открытого keychain) — карточки 1:1 с `Skerry Mobile.html`. */
@Composable
private fun MobileVaultMock() {
    val mono = LocalFonts.current.mono
    Column(Modifier.fillMaxSize().background(D.bg).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 10.dp)) {
            Txt("Vault", color = D.text, size = 28.sp, weight = FontWeight.Bold, letterSpacing = (-0.5).sp)
        }
        MobileVaultBanner()
        Txt(
            "SSH KEYS",
            color = D.faint, size = 12.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 4.dp),
        )
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            MockKeyShell(D.cyanBright, D.cyan.copy(alpha = 0.05f), D.cyan.copy(alpha = 0.16f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Txt("id_ed25519", color = D.text, size = 14.5.sp, weight = FontWeight.SemiBold)
                    Badge("DEFAULT", bg = D.cyan.copy(alpha = 0.14f), fg = D.cyanBright, size = 9.sp)
                }
                Txt("SHA256:8c3F1a…Qz9pK", color = D.dim, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 3.dp))
            }
            MockKeyShell(D.dim, Color(0x08FFFFFF), D.cyan.copy(alpha = 0.08f)) {
                Txt("id_rsa_legacy", color = D.text, size = 14.5.sp, weight = FontWeight.SemiBold)
                Txt("SHA256:2dE7b…Lm4xR", color = D.dim, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 3.dp))
            }
            MockKeyShell(D.sunset, D.sunset.copy(alpha = 0.05f), D.sunset.copy(alpha = 0.22f), icon = "warning") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Txt("deploy_ci", color = D.text, size = 14.5.sp, weight = FontWeight.SemiBold)
                    Badge("ROTATE", bg = D.sunset.copy(alpha = 0.16f), fg = D.sunset, size = 9.sp)
                }
                Txt("created 412 days ago", color = D.dim, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun MockKeyShell(iconColor: Color, cardBg: Color, cardBorder: Color, icon: String = "key", content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(iconColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Sym(icon, size = 20.sp, color = iconColor)
        }
        Column(Modifier.weight(1f)) { content() }
    }
}
