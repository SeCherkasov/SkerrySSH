package app.skerry.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.skerry.ui.desktop.optimalWindowSize
import java.awt.GraphicsEnvironment
import app.skerry.shared.host.VaultHostStore
import app.skerry.shared.io.PrivateConfig
import app.skerry.shared.ssh.FileHostKeyMismatchStore
import app.skerry.shared.ssh.VaultKnownHostsStore
import app.skerry.shared.ssh.ProbeHostKeyVerifier
import app.skerry.shared.ssh.RoutingTransport
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.ssh.SshjTransport
import app.skerry.shared.ssh.TofuHostKeyVerifier
import app.skerry.shared.vault.BouncyCastleSshKeyGenerator
import app.skerry.shared.vault.FileSecurityLog
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.CredentialStore
import app.skerry.shared.vault.VaultMigration
import app.skerry.shared.vault.WorkspaceLayoutStore
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.SshjCertificateInspector
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.shared.snippet.VaultSnippetStore
import app.skerry.shared.sync.FileSyncStateStore
import app.skerry.shared.sync.KtorSyncClient
import app.skerry.shared.tunnel.VaultTunnelStore
import app.skerry.ui.sync.FileSyncConfigStore
import app.skerry.shared.ai.AiSettingsStore
import app.skerry.shared.ai.local.LlamatikRuntime
import app.skerry.shared.ai.local.LocalModelStore
import app.skerry.shared.ai.local.ModelDownloader
import app.skerry.ui.ai.LocalAiDeps
import app.skerry.ui.ai.aiProviderFactory
import app.skerry.ui.ai.AiAssistantController
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.i18n.AppLocaleProvider
import app.skerry.ui.i18n.UiLanguage
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LETTER_SPACING
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LINE_HEIGHT
import app.skerry.ui.terminal.DEFAULT_TERMINAL_SCROLLBACK
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_RANGE
import app.skerry.ui.terminal.TERMINAL_SCROLLBACK_OPTIONS
import app.skerry.ui.terminal.clampTerminalLetterSpacing
import app.skerry.ui.terminal.clampTerminalLineHeight
import app.skerry.ui.terminal.TerminalCursorStyle
import app.skerry.ui.terminal.TerminalFont
import app.skerry.ui.terminal.TerminalThemes
import app.skerry.ui.tunnel.TunnelManager
import app.skerry.ui.tunnel.resolveTunnel
import app.skerry.ui.vault.AutoLockDuration
import app.skerry.ui.vault.ResetScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Каталог конфигурации Skerry. По умолчанию `~/.config/skerry`; уважает XDG_CONFIG_HOME.
 * Создаётся с правами 0700 (и апгрейдится, если со старой установки остался 0755), чтобы UI-префы
 * и конфиг-файлы внутри не были доступны другим локальным пользователям независимо от их прав.
 */
private fun configDir(): Path {
    val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
    val base = xdg?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".config")
    return base.resolve("skerry").also { PrivateConfig.ensureDir(it) }
}

/**
 * Каталог данных Skerry (не конфиг): большие артефакты — скачанные GGUF-модели локального AI.
 * По умолчанию `~/.local/share/skerry`; уважает XDG_DATA_HOME (в Flatpak-песочнице это
 * каталог данных приложения). Модели — публичные веса, hardening 0600 не требуется.
 */
private fun dataDir(): Path {
    val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
    val base = xdg?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".local", "share")
    return base.resolve("skerry").also { Files.createDirectories(it) }
}

/**
 * Стабильный идентификатор устройства для записей vault (provenance + LWW будущего sync).
 * Генерируется один раз и персистится в `device_id`, чтобы переживать перезапуски.
 */
private fun deviceId(dir: Path): String {
    val file = dir.resolve("device_id")
    if (Files.exists(file)) return Files.readString(file).trim()
    Files.createDirectories(dir)
    val id = UUID.randomUUID().toString()
    Files.writeString(file, id)
    return id
}

// Чтение UI-префов с валидацией диапазона/каталога опций — дефолт при невалидном значении
// (сам ввод-вывод и дефолты при нечитаемости — в FilePrefs).

/** Кегль шрифта терминала, px: вне [TERMINAL_FONT_SIZE_RANGE] → дефолт. */
private fun readTerminalFontSize(prefs: FilePrefs): Int =
    prefs.int("terminal_font_size", DEFAULT_TERMINAL_FONT_SIZE)
        .takeIf { it in TERMINAL_FONT_SIZE_RANGE } ?: DEFAULT_TERMINAL_FONT_SIZE

/** Глубина scrollback новой сессии: вне пресетов [TERMINAL_SCROLLBACK_OPTIONS] → дефолт (10 000). */
private fun readTerminalScrollback(prefs: FilePrefs): Int =
    prefs.int("terminal_scrollback", DEFAULT_TERMINAL_SCROLLBACK)
        .takeIf { it in TERMINAL_SCROLLBACK_OPTIONS } ?: DEFAULT_TERMINAL_SCROLLBACK

/**
 * Живой граф зависимостей desktop-приложения, собранный до `application {}` — обычной функцией,
 * без чтения Compose-состояния: vault/транспорты/менеджеры/sync/AI и колбэки жизненного цикла vault.
 */
private class DesktopGraph(
    val deps: AppDependencies,
    val securityLog: FileSecurityLog,
    val probeTransport: SshTransport,
    val workspaceLayout: WorkspaceLayoutStore,
    val ai: AiAssistantController,
    val onVaultUnlocked: () -> Unit,
    val onVaultReset: (ResetScope) -> Unit,
)

private fun buildDesktopGraph(dir: Path, prefs: FilePrefs): DesktopGraph {
    // Локальный зашифрованный vault создаётся ПЕРВЫМ: всё рабочее пространство (хосты/группы/
    // сниппеты/туннели/known-hosts) живёт его записями (Phase A) и E2E-синкается. Гейт мастер-пароля
    // (App → VaultGate) закрывает им весь UI, поэтому к моменту коннекта/чтения vault разблокирован.
    val vault = FileVault(
        dir.resolve("vault.json").toString().toPath(),
        IonspinVaultCrypto(),
        deviceId(dir),
        FileSystem.SYSTEM,
        now = { Instant.now().toString() },
        // Сам файл главных секретов — 0600, а не только каталог (как security_events.json ниже):
        // защита не должна быть однослойной на случай копирования/бэкапа с сохранением прав.
        harden = { PrivateConfig.harden(Path.of(it.toString())) },
    )
    // Локальный (не синкаемый) журнал событий безопасности: смена мастер-пароля, биометрия,
    // разблокировка. Пишется контроллером за гейтом; раздел Settings → Безопасность читает его.
    val securityLog = FileSecurityLog(
        dir.resolve("security_events.json").toString().toPath(),
        FileSystem.SYSTEM,
        harden = { PrivateConfig.harden(Path.of(it.toString())) },
    ) { Instant.now().toString() }
    // TOFU: первый ключ хоста запоминается в vault (RecordType.KNOWN_HOST — синкается между
    // устройствами, как в популярных SSH-клиентах), при смене ключа — отказ + запись события в локальный (НЕ
    // синкаемый) known_hosts_mismatches, чтобы менеджер мог показать предупреждение и дать
    // принять/отклонить. Часы штампуют firstSeen/observedAt.
    val knownHostsStore = VaultKnownHostsStore(vault)
    val mismatchStore = FileHostKeyMismatchStore(dir.resolve("known_hosts_mismatches"))
    // Живой транспорт сессий: маршрутизатор по типу подключения (SSH/Telnet/Serial). SSH несёт
    // TOFU-verifier/known-hosts; Telnet/Serial без состояния (создаются внутри по умолчанию).
    val transport = RoutingTransport(
        ssh = SshjTransport(
            TofuHostKeyVerifier(knownHostsStore, mismatchStore) { Instant.now().toString() },
        ),
    )
    // «Test connection» из формы — отдельный транспорт с read-only verifier: проба пускает
    // совпавший доверенный ключ, отвергает смену ключа у известного хоста, а новый хост принимает
    // БЕЗ записи в known_hosts. Постоянное доверие фиксирует только реальный коннект (TOFU выше).
    val probeTransport = SshjTransport(ProbeHostKeyVerifier(knownHostsStore))
    val knownHosts = KnownHostsController(knownHostsStore, mismatchStore) { Instant.now().toString() }
    // Менеджер хостов: профили — записи HOST в vault, порядок дерева — в записи-макете
    // ([VaultHostStore]/[WorkspaceLayout]). На старте vault залочен (список пуст), после unlock
    // контроллер перечитывает через reload(). id — случайный UUID.
    val hostStore = VaultHostStore(vault)
    val hosts = HostManagerController(hostStore) { UUID.randomUUID().toString() }
    // Макет рабочего пространства в vault (Phase A): пустые папки (и порядок дерева) синкаются как
    // одна запись. Пустые папки читаем после unlock (vault залочен на старте) и пишем при изменении.
    val workspaceLayout = WorkspaceLayoutStore(vault)
    // Одноуровневая модель vault: keychain-секреты (записи CREDENTIAL). Хост ссылается на секрет
    // напрямую через credentialId.
    val credentials = CredentialManagerController(CredentialStore(vault)) { UUID.randomUUID().toString() }
    // Self-hosted sync (Phase 2): координатор связывает сетевой клиент (Ktor+SRP), крипту и
    // локальный vault. Привязка к серверу персистится в sync.json (0600) — несекретное
    // (URL/accountId/deviceId) + опц. refresh-токен ЗАПЕЧАТАННЫЙ под dataKey (keep-connected).
    // deviceId переиспользуем стабильный. Курсор синка пока в памяти (re-pull при старте, LWW идемпотентен).
    // Перечитать менеджеры списков после синка/unlock. Отложенный var: tunnels/snippets создаются
    // ниже, а sync ссылается на reload через этот var (вызывается уже после полной инициализации).
    var reloadManagers: () -> Unit = {}
    val sync = SyncCoordinator(
        clientFactory = { url -> KtorSyncClient(url) },
        crypto = IonspinVaultCrypto(),
        vault = vault,
        configStore = FileSyncConfigStore(dir.resolve("sync.json")),
        // Персистентный курсор дельта-синка: переживает перезапуск, иначе каждый старт — full re-pull since 0.
        syncState = FileSyncStateStore(dir.resolve("sync-cursor.json")),
        deviceIdProvider = { deviceId(dir) },
        deviceName = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Skerry desktop",
        // Синк подтянул записи прямо в vault → обновить менеджеры, иначе данные не видны до перезахода.
        onSynced = { reloadManagers() },
    )
    // Генерация SSH-ключей в разделе Vault: BouncyCastle поверх sshj-формата (тот же, что читает транспорт).
    val keyGenerator = BouncyCastleSshKeyGenerator()
    // Разбор импортированных SSH-сертификатов (раздел Vault → Certificates) — sshj поверх ssh-wire.
    val certificateInspector = SshjCertificateInspector()
    // Глобальные туннели: сохранённые пробросы в tunnels.json. Активация ходит
    // через ОТДЕЛЬНЫЙ probe-транспорт (read-only verifier): включить можно только уже доверенный
    // хост — туннель открывается без терминала, поэтому тихого TOFU тут быть не должно. Резолв
    // хоста/секрета — через граф (hosts + credentials в открытом vault). Scope живёт всё приложение.
    val tunnelTransport = SshjTransport(ProbeHostKeyVerifier(knownHostsStore))
    val tunnelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tunnels = TunnelManager(
        store = VaultTunnelStore(vault),
        transport = tunnelTransport,
        resolve = { resolveTunnel(it, findHost = hosts::find, findCredential = credentials::find) },
        scope = tunnelScope,
    ) { UUID.randomUUID().toString() }
    // Сохранённые сниппеты: библиотека команд — записи SNIPPET в vault (команды могут содержать
    // inline-креды, поэтому под общим шифрованием и E2E-синком). Запуск идёт в активный терминал.
    val snippets = SnippetManager(VaultSnippetStore(vault)) { UUID.randomUUID().toString() }
    // AI-ассистент: настройки (провайдер/BYOK/локальная модель) — зашифрованная запись SETTINGS
    // в vault; запрос маршрутизируется в облако либо в локальный рантайм (Llamatik/llama.cpp).
    // Локальный AI: GGUF-модели в ~/.local/share/skerry/models (XDG), закачка с докачкой и sha256.
    // На старте vault залочен (settings = дефолт), после unlock контроллер перечитывает настройки.
    val aiSettingsStore = AiSettingsStore(vault)
    val localModelStore = LocalModelStore(FileSystem.SYSTEM, dataDir().resolve("models").toString().toPath())
    val localAi = LocalAiDeps(
        store = localModelStore,
        downloader = ModelDownloader(FileSystem.SYSTEM, localModelStore),
        runtime = LlamatikRuntime(contextLength = 4096),
    )
    val ai = AiAssistantController(
        initialSettings = aiSettingsStore.load(),
        persist = aiSettingsStore::save,
        providerFactory = aiProviderFactory(localAi),
        scope = tunnelScope,
        reload = aiSettingsStore::load,
        localInstalled = localAi::installed,
        models = localAi.modelsController(tunnelScope),
    )
    // Миграция секретов в vault (IDENTITY → CREDENTIAL, хост → прямой credentialId) при
    // разблокировке — идемпотентна. После — перечитываем менеджеры и бесшумно восстанавливаем
    // sync-сессию. Переноса старого локального workspace (hosts/snippets/tunnels.json) больше нет:
    // рабочее пространство живёт записями vault, до первого прод-релиза миграции не делаем.
    // Теперь все менеджеры существуют — подключаем reload (используется и из onSynced, и при unlock).
    reloadManagers = {
        hosts.reload()
        snippets.reload()
        tunnels.reload()
        knownHosts.refresh()
        // BYOK-настройки AI (ключ/модель) — тоже запись SETTINGS в vault: перечитываем и здесь,
        // чтобы правка, прилетевшая живым синком с другого устройства (onSynced зовёт reloadManagers),
        // сразу отражалась в UI, а не только после перезахода.
        ai.refresh()
    }
    val onVaultUnlocked: () -> Unit = {
        // Миграция идемпотентна и безопасно повторяется на следующем unlock — но её сбой не должен
        // исчезать бесследно (частично мигрированный vault: хосты без перепривязки credentialId).
        runCatching { VaultMigration(vault, hostStore).migrate() }
            .onFailure { System.err.println("vault migration failed (retries on next unlock): ${it.message}") }
        // Vault открыт → перечитать менеджеры (включая BYOK-настройки AI) из расшифрованных записей.
        reloadManagers()
        // keep-connected: vault открыт → есть dataKey, можно бесшумно восстановить sync-сессию.
        sync.restoreSession()
    }
    // Внешняя чистка при безвозвратном сбросе vault (забытый пароль / битый файл). Файл vault уже
    // стёрт контроллером (Vault.reset) и теперь заблокирован, поэтому credentials.reload() здесь НЕ
    // зовём (он требует открытого vault) — список секретов перечитается при создании нового vault.
    // Сброс vault (забытый пароль / битый файл). Phase A: хосты/сниппеты/туннели — записи vault,
    // поэтому Vault.reset() уже стёр их вместе с секретами (zero-knowledge: без мастер-пароля их не
    // восстановить — «только секреты, профили оставить» технически невозможно). Здесь чистим лишь
    // данные ВНЕ vault и отражаем опустевший vault в менеджерах. Vault на этот момент заблокирован.
    val onVaultReset: (ResetScope) -> Unit = { resetScope ->
        tunnels.closeAll()
        // Журнал событий безопасности относится к стёртому vault (смена пароля/биометрия/паринг) —
        // при любом сбросе он становится неактуальным и может выдать имена устройств; чистим всегда.
        securityLog.clear()
        // Сброс стёр dataKey → sealed refresh-токен sync обёрнут под мёртвым ключом. Рвём привязку
        // к серверу, иначе настройки висели бы «Linked» без возможности войти. (Биометрии на desktop
        // нет — deps.biometrics=null.) Чистый старт: заново создать vault и подключить sync.
        sync.disconnect()
        // Хосты/группы стёрты вместе с vault при ЛЮБОМ сбросе → чистим и их локальные UI-следы
        // (недавние, свёрнутость, пустые папки): иначе в открытом виде остались бы имена групп и
        // UUID хостов, которых уже нет (security L1/I1).
        prefs.setLines("recent_connections", emptyList())
        prefs.setLines("collapsed_groups", emptyList())
        prefs.setLines("custom_groups", emptyList())
        // Заводской сброс: дополнительно сносим доверенные ключи (не-vault) и настройки терминала.
        if (resetScope == ResetScope.Everything) {
            knownHosts.mismatches.toList().forEach { knownHosts.reject(it) }
            knownHosts.entries.toList().forEach { knownHosts.forget(it) }
            prefs.set("terminal_font", TerminalFont.DEFAULT.id)
            prefs.set("terminal_font_size", DEFAULT_TERMINAL_FONT_SIZE)
            prefs.set("terminal_line_height", DEFAULT_TERMINAL_LINE_HEIGHT.toString())
            prefs.set("terminal_letter_spacing", DEFAULT_TERMINAL_LETTER_SPACING.toString())
            prefs.set("ui_language", UiLanguage.DEFAULT.id)
            prefs.set("terminal_scrollback", DEFAULT_TERMINAL_SCROLLBACK)
            prefs.set("terminal_cursor_style", TerminalCursorStyle.DEFAULT.id)
            prefs.set("terminal_show_title", false)
            prefs.set("auto_lock", AutoLockDuration.DEFAULT.id)
        }
        hosts.reload()
        snippets.reload()
        tunnels.reload()
    }
    val deps = AppDependencies(transport = transport, hosts = hosts, vault = vault, credentials = credentials, knownHosts = knownHosts, keyGenerator = keyGenerator, certificateInspector = certificateInspector, tunnels = tunnels, snippets = snippets, sync = sync, localAi = localAi)
    return DesktopGraph(
        deps = deps,
        securityLog = securityLog,
        probeTransport = probeTransport,
        workspaceLayout = workspaceLayout,
        ai = ai,
        onVaultUnlocked = onVaultUnlocked,
        onVaultReset = onVaultReset,
    )
}

fun main() {
    // libsodium (ionspin) требует асинхронной инициализации до первого вызова VaultCrypto;
    // на старте desktop делаем это блокирующе, чтобы граф зависимостей строился уже готовым.
    runBlocking { initializeVaultCrypto() }
    // Граф зависимостей строится до application{} обычной функцией: Compose-состояние внутри не
    // читается, а сборка не попадает в композицию (и не пересобралась бы при рекомпозиции корня).
    val dir = configDir()
    val prefs = FilePrefs(dir)
    val graph = buildDesktopGraph(dir, prefs)
    application {
        val deps = graph.deps
        val workspaceLayout = graph.workspaceLayout
        // Размер окна подбираем под доступную область экрана (без таскбара): ~90% экрана в рамках
        // MIN_WINDOW…MAX_WINDOW, не больше самого экрана. maximumWindowBounds учитывает панели ОС.
        // Язык интерфейса живёт в корне: провайдер локали над темой должен реагировать на смену из
        // настроек и рекомпозировать всё дерево. onUiLanguageChange (из DesktopDesignState) обновляет
        // это состояние и пишет персист; DesktopDesignState держит копию для отображения в дропдауне.
        val currentUiLanguage = remember { mutableStateOf(prefs.id("ui_language", UiLanguage.DEFAULT, UiLanguage::fromId)) }
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val windowState = rememberWindowState(
            size = optimalWindowSize(DpSize(screen.width.dp, screen.height.dp)),
            position = WindowPosition(Alignment.Center),
        )
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Skerry",
        ) {
            // Живой vault + хосты + сессии + known-hosts подключены: chrome закрыт гейтом мастер-пароля,
            // клик по хосту открывает живой SSH-терминал во вкладке (transport+identities из `deps`),
            // менеджер known-hosts работает поверх своих сторов (knownHosts из `deps`).
            AppLocaleProvider(currentUiLanguage.value) {
              app.skerry.ui.theme.SkerryTheme {
                app.skerry.ui.desktop.DesktopDesignApp(
                    initialInfoPanel = prefs.bool("info_panel", true),
                    onInfoPanelChange = { prefs.set("info_panel", it) },
                    initialCollapsedGroups = prefs.lines("collapsed_groups").toSet(),
                    onCollapsedGroupsChange = { prefs.setLines("collapsed_groups", it.toList()) },
                    initialRecentHostIds = prefs.lines("recent_connections"),
                    onRecentHostIdsChange = { prefs.setLines("recent_connections", it) },
                    // Пустые папки синкаются в vault: стартуем пусто (vault залочен), читаем через
                    // customGroupsProvider после unlock, пишем изменения в запись-макет.
                    initialCustomGroups = emptyList(),
                    onCustomGroupsChange = { groups -> workspaceLayout.write(workspaceLayout.read().copy(groups = groups)) },
                    customGroupsProvider = { workspaceLayout.read().groups },
                    initialSftpShowHidden = prefs.bool("sftp_show_hidden", true),
                    onSftpShowHiddenChange = { prefs.set("sftp_show_hidden", it) },
                    initialTerminalFont = prefs.id("terminal_font", TerminalFont.DEFAULT, TerminalFont::fromId),
                    onTerminalFontChange = { prefs.set("terminal_font", it.id) },
                    initialTerminalFontSize = readTerminalFontSize(prefs),
                    onTerminalFontSizeChange = { prefs.set("terminal_font_size", it) },
                    initialTerminalLineHeight = clampTerminalLineHeight(prefs.id("terminal_line_height", DEFAULT_TERMINAL_LINE_HEIGHT) { it.toFloat() }),
                    onTerminalLineHeightChange = { prefs.set("terminal_line_height", it.toString()) },
                    initialTerminalLetterSpacing = clampTerminalLetterSpacing(prefs.id("terminal_letter_spacing", DEFAULT_TERMINAL_LETTER_SPACING) { it.toFloat() }),
                    onTerminalLetterSpacingChange = { prefs.set("terminal_letter_spacing", it.toString()) },
                    initialTerminalTheme = prefs.id("terminal_theme", TerminalThemes.DEFAULT, TerminalThemes::fromId),
                    onTerminalThemeChange = { prefs.set("terminal_theme", it.id) },
                    initialUiLanguage = currentUiLanguage.value,
                    onUiLanguageChange = { currentUiLanguage.value = it; prefs.set("ui_language", it.id) },
                    initialTerminalScrollback = readTerminalScrollback(prefs),
                    onTerminalScrollbackChange = { prefs.set("terminal_scrollback", it) },
                    initialTerminalCursorStyle = prefs.id("terminal_cursor_style", TerminalCursorStyle.DEFAULT, TerminalCursorStyle::fromId),
                    onTerminalCursorStyleChange = { prefs.set("terminal_cursor_style", it.id) },
                    initialShowTerminalTitleOnTabs = prefs.bool("terminal_show_title", false),
                    onShowTerminalTitleOnTabsChange = { prefs.set("terminal_show_title", it) },
                    initialAutoLock = prefs.id("auto_lock", AutoLockDuration.DEFAULT, AutoLockDuration::fromId),
                    onAutoLockChange = { prefs.set("auto_lock", it.id) },
                    initialShowRecent = prefs.bool("recent_show", true),
                    onShowRecentChange = { prefs.set("recent_show", it) },
                    initialRecentLimit = prefs.int("recent_limit", DesktopDesignState.MAX_RECENT_HOSTS),
                    onRecentLimitChange = { prefs.set("recent_limit", it) },
                    vault = deps.vault,
                    biometrics = deps.biometrics,
                    securityLog = graph.securityLog,
                    hosts = deps.hosts,
                    transport = deps.transport,
                    testTransport = graph.probeTransport,
                    credentials = deps.credentials,
                    knownHosts = deps.knownHosts,
                    keyGenerator = deps.keyGenerator,
                    certificateInspector = deps.certificateInspector,
                    tunnels = deps.tunnels,
                    snippets = deps.snippets,
                    sync = deps.sync,
                    ai = graph.ai,
                    onVaultUnlocked = graph.onVaultUnlocked,
                    onVaultReset = graph.onVaultReset,
                )
              }
            }
        }
    }
}
