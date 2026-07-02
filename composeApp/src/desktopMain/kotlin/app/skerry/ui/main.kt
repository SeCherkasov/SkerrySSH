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
import app.skerry.shared.ai.OpenAiProvider
import app.skerry.ui.ai.AiAssistantController
import app.skerry.ui.sync.SyncCoordinator
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
import app.skerry.ui.terminal.TerminalTheme
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
import java.nio.charset.StandardCharsets
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

/**
 * Видимость info-панели терминала, переживающая перезапуск: хранится как один символ в файле
 * `info_panel` (`0`/`1`) рядом с прочей конфигурацией. Отсутствует/нечитаем → дефолт `true`
 * (панель показана по умолчанию). Запись best-effort: сбой персиста не должен ронять UI.
 */
private fun readInfoPanel(dir: Path): Boolean {
    val file = dir.resolve("info_panel")
    return runCatching { Files.readString(file).trim() != "0" }.getOrDefault(true)
}

private fun writeInfoPanel(dir: Path, visible: Boolean) {
    runCatching {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("info_panel"), if (visible) "1" else "0")
    }
}

/**
 * Свёрнутые папки хостов сайдбара, переживающие перезапуск: имена групп хранятся в файле
 * `collapsed_groups` по одному на строку рядом с прочей конфигурацией. Отсутствует/нечитаем →
 * пусто (все папки развёрнуты по умолчанию). Запись best-effort: сбой персиста не роняет UI.
 */
private fun readCollapsedGroups(dir: Path): Set<String> {
    val file = dir.resolve("collapsed_groups")
    return runCatching {
        Files.readAllLines(file, StandardCharsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }.getOrDefault(emptySet())
}

private fun writeCollapsedGroups(dir: Path, groups: Set<String>) {
    runCatching {
        Files.createDirectories(dir)
        // Имена с переносами строк не хранимы построчно — исключаем их, чтобы файл не «расщепился»
        // при readAllLines (он рвёт строки и по \n, и по \r, и по \r\n).
        Files.writeString(dir.resolve("collapsed_groups"), groups.filterNot { it.contains('\n') || it.contains('\r') }.joinToString("\n"))
    }
}

/**
 * Недавние подключения (секция RECENT сайдбара), переживающие перезапуск: id хостов в файле
 * `recent_connections` по одному на строку, новейший — первым (порядок значим). Отсутствует/нечитаем →
 * пусто. Запись best-effort: сбой персиста не роняет UI. Стейл-id (удалённые хосты) безвредны —
 * при рендере они отсеиваются резолвом к каталогу, а из файла вытесняются новыми коннектами (кап 8).
 */
private fun readRecentHostIds(dir: Path): List<String> {
    val file = dir.resolve("recent_connections")
    return runCatching {
        Files.readAllLines(file, StandardCharsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())
}

private fun writeRecentHostIds(dir: Path, ids: List<String>) {
    runCatching {
        Files.createDirectories(dir)
        // id — UUID без переносов, но фильтруем на всякий случай, чтобы файл не «расщепился» построчно.
        Files.writeString(dir.resolve("recent_connections"), ids.filterNot { it.contains('\n') || it.contains('\r') }.joinToString("\n"))
    }
}

/**
 * Видимость секции RECENT сайдбара (Settings → Appearance → Interface): файл `recent_show` (`0`/`1`).
 * Отсутствует/нечитаем → `true` (показывать по умолчанию). Запись best-effort — сбой не роняет UI.
 */
private fun readRecentShow(dir: Path): Boolean {
    val file = dir.resolve("recent_show")
    return runCatching { Files.readString(file).trim() != "0" }.getOrDefault(true)
}

private fun writeRecentShow(dir: Path, visible: Boolean) {
    runCatching {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("recent_show"), if (visible) "1" else "0")
    }
}

/**
 * Число показываемых недавних хостов (Settings → Appearance → Interface): файл `recent_limit`.
 * Отсутствует/нечитаем/невалиден → кап [DesktopDesignState.MAX_RECENT_HOSTS]. Диапазон зажимает сам
 * state при чтении initial-значения; запись best-effort — сбой не роняет UI.
 */
private fun readRecentLimit(dir: Path): Int {
    val file = dir.resolve("recent_limit")
    return runCatching { Files.readString(file).trim().toInt() }
        .getOrDefault(app.skerry.ui.app.DesktopDesignState.MAX_RECENT_HOSTS)
}

private fun writeRecentLimit(dir: Path, limit: Int) {
    runCatching {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("recent_limit"), limit.toString())
    }
}

/**
 * Пользовательские (пока пустые) группы хостов сайдбара, переживающие перезапуск: имена групп в файле
 * `custom_groups` по одному на строку (порядок значим — папки идут в этом порядке). Отсутствует/нечитаем
 * → пусто. Запись best-effort: сбой персиста не роняет UI.
 */
private fun readCustomGroups(dir: Path): List<String> {
    val file = dir.resolve("custom_groups")
    return runCatching {
        Files.readAllLines(file, StandardCharsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())
}

private fun writeCustomGroups(dir: Path, groups: List<String>) {
    runCatching {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("custom_groups"), groups.filterNot { it.contains('\n') || it.contains('\r') }.joinToString("\n"))
    }
}

/**
 * Показ скрытых объектов (dotfiles) в SFTP (тоггл Ctrl+H), переживающий перезапуск: один символ в
 * файле `sftp_show_hidden` (`0`/`1`). Отсутствует/нечитаем → дефолт `true` (показывать, как в mc).
 * Запись best-effort: сбой персиста не роняет UI.
 */
private fun readSftpShowHidden(dir: Path): Boolean {
    val file = dir.resolve("sftp_show_hidden")
    return runCatching { Files.readString(file).trim() != "0" }.getOrDefault(true)
}

private fun writeSftpShowHidden(dir: Path, show: Boolean) {
    runCatching {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("sftp_show_hidden"), if (show) "1" else "0")
    }
}

/**
 * Шрифт терминала (Appearance → Font), переживающий перезапуск: стабильный id ([TerminalFont.id]) в
 * файле `terminal_font`. Отсутствует/нечитаем/неизвестен → дефолт ([TerminalFont.DEFAULT] = Hack).
 * Запись best-effort: сбой персиста не роняет UI.
 */
private fun readTerminalFont(dir: Path): TerminalFont {
    val file = dir.resolve("terminal_font")
    return runCatching { TerminalFont.fromId(Files.readString(file).trim()) }.getOrDefault(TerminalFont.DEFAULT)
}

private fun writeTerminalFont(dir: Path, font: TerminalFont) {
    runCatching {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("terminal_font"), font.id)
    }
}

/**
 * Кегль шрифта терминала, px (Appearance → Font size), переживающий перезапуск: число в файле
 * `terminal_font_size`. Отсутствует/нечитаем/вне [TERMINAL_FONT_SIZE_RANGE] → дефолт
 * ([DEFAULT_TERMINAL_FONT_SIZE]). Запись best-effort: сбой персиста не роняет UI.
 */
private fun readTerminalFontSize(dir: Path): Int {
    val file = dir.resolve("terminal_font_size")
    val px = runCatching { Files.readString(file).trim().toInt() }.getOrDefault(DEFAULT_TERMINAL_FONT_SIZE)
    return if (px in TERMINAL_FONT_SIZE_RANGE) px else DEFAULT_TERMINAL_FONT_SIZE
}

private fun writeTerminalFontSize(dir: Path, px: Int) {
    runCatching {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("terminal_font_size"), px.toString())
    }
}

/**
 * Высота строки терминала (множитель, Appearance → Line height), переживающая перезапуск: число в
 * файле `terminal_line_height`. Отсутствует/нечитаем → дефолт ([DEFAULT_TERMINAL_LINE_HEIGHT]); любое
 * прочитанное значение приводится к диапазону ([clampTerminalLineHeight]). Запись best-effort.
 */
private fun readTerminalLineHeight(dir: Path): Float {
    val file = dir.resolve("terminal_line_height")
    val v = runCatching { Files.readString(file).trim().toFloat() }.getOrNull() ?: return DEFAULT_TERMINAL_LINE_HEIGHT
    return clampTerminalLineHeight(v)
}

private fun writeTerminalLineHeight(dir: Path, ratio: Float) {
    runCatching {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("terminal_line_height"), ratio.toString())
    }
}

/**
 * Межбуквенный интервал терминала (sp, Appearance → Letter spacing), переживающий перезапуск: число в
 * файле `terminal_letter_spacing`. Отсутствует/нечитаем → дефолт ([DEFAULT_TERMINAL_LETTER_SPACING]);
 * прочитанное значение приводится к диапазону ([clampTerminalLetterSpacing]). Запись best-effort.
 */
private fun readTerminalLetterSpacing(dir: Path): Float {
    val file = dir.resolve("terminal_letter_spacing")
    val v = runCatching { Files.readString(file).trim().toFloat() }.getOrNull() ?: return DEFAULT_TERMINAL_LETTER_SPACING
    return clampTerminalLetterSpacing(v)
}

private fun writeTerminalLetterSpacing(dir: Path, sp: Float) {
    runCatching {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("terminal_letter_spacing"), sp.toString())
    }
}

/**
 * Тема терминала (Appearance → карточки), переживающая перезапуск: стабильный id ([TerminalTheme.id])
 * в файле `terminal_theme`. Отсутствует/нечитаем/неизвестен → дефолт ([TerminalThemes.DEFAULT] =
 * Night Sea). Запись best-effort: сбой персиста не роняет UI.
 */
private fun readTerminalTheme(dir: Path): TerminalTheme {
    val file = dir.resolve("terminal_theme")
    return runCatching { TerminalThemes.fromId(Files.readString(file).trim()) }.getOrDefault(TerminalThemes.DEFAULT)
}

private fun writeTerminalTheme(dir: Path, theme: TerminalTheme) {
    runCatching {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("terminal_theme"), theme.id)
    }
}

/**
 * Язык интерфейса (Appearance → Language), переживающий перезапуск: стабильный id ([UiLanguage.id]) в
 * файле `ui_language`. Отсутствует/нечитаем/неизвестен → дефолт ([UiLanguage.DEFAULT] = System —
 * автоопределение по локали ОС). Запись best-effort: сбой персиста не роняет UI.
 */
private fun readUiLanguage(dir: Path): UiLanguage {
    val file = dir.resolve("ui_language")
    return runCatching { UiLanguage.fromId(Files.readString(file).trim()) }.getOrDefault(UiLanguage.DEFAULT)
}

private fun writeUiLanguage(dir: Path, language: UiLanguage) {
    runCatching {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("ui_language"), language.id)
    }
}

/**
 * Глубина scrollback новой сессии (Terminal → Буфер прокрутки), переживающая перезапуск: число в
 * файле `terminal_scrollback`. Отсутствует/нечитаемо/вне [TERMINAL_SCROLLBACK_OPTIONS] → дефолт
 * ([DEFAULT_TERMINAL_SCROLLBACK] = 10 000). Запись best-effort: сбой персиста не роняет UI.
 */
private fun readTerminalScrollback(dir: Path): Int {
    val file = dir.resolve("terminal_scrollback")
    val lines = runCatching { Files.readString(file).trim().toInt() }.getOrDefault(DEFAULT_TERMINAL_SCROLLBACK)
    return if (lines in TERMINAL_SCROLLBACK_OPTIONS) lines else DEFAULT_TERMINAL_SCROLLBACK
}

private fun writeTerminalScrollback(dir: Path, lines: Int) {
    runCatching {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("terminal_scrollback"), lines.toString())
    }
}

/**
 * Порог автоблокировки по простою (Settings → Безопасность), переживающий перезапуск: стабильный id
 * ([AutoLockDuration.id]) в файле `auto_lock`. Отсутствует/нечитаем/неизвестен → дефолт (5 минут).
 * Запись best-effort: сбой персиста не роняет UI.
 */
private fun readAutoLock(dir: Path): AutoLockDuration {
    val file = dir.resolve("auto_lock")
    return runCatching { AutoLockDuration.fromId(Files.readString(file).trim()) }.getOrDefault(AutoLockDuration.DEFAULT)
}

private fun writeAutoLock(dir: Path, duration: AutoLockDuration) {
    runCatching {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("auto_lock"), duration.id)
    }
}

/**
 * Стиль курсора по умолчанию (Terminal → Стиль курсора), переживающий перезапуск: стабильный id
 * ([TerminalCursorStyle.id]) в файле `terminal_cursor_style`. Отсутствует/нечитаем/неизвестен →
 * дефолт ([TerminalCursorStyle.DEFAULT] = мигающий блок). Запись best-effort.
 */
private fun readTerminalCursorStyle(dir: Path): TerminalCursorStyle {
    val file = dir.resolve("terminal_cursor_style")
    return runCatching { TerminalCursorStyle.fromId(Files.readString(file).trim()) }.getOrDefault(TerminalCursorStyle.DEFAULT)
}

private fun writeTerminalCursorStyle(dir: Path, style: TerminalCursorStyle) {
    runCatching {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("terminal_cursor_style"), style.id)
    }
}

/**
 * Показ живого OSC-заголовка терминала на вкладках (Terminal → Показывать заголовок…), переживающий
 * перезапуск: `1`/`0` в файле `terminal_show_title`. Отсутствует/нечитаемо → дефолт (выкл). Best-effort.
 */
private fun readShowTerminalTitle(dir: Path): Boolean {
    val file = dir.resolve("terminal_show_title")
    return runCatching { Files.readString(file).trim() == "1" }.getOrDefault(false)
}

private fun writeShowTerminalTitle(dir: Path, show: Boolean) {
    runCatching {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("terminal_show_title"), if (show) "1" else "0")
    }
}

fun main() {
    // libsodium (ionspin) требует асинхронной инициализации до первого вызова VaultCrypto;
    // на старте desktop делаем это блокирующе, чтобы граф зависимостей строился уже готовым.
    runBlocking { initializeVaultCrypto() }
    application {
        val dir = configDir()
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
        // AI-ассистент (Phase 2, BYOK): ключ хранится зашифрованной записью SETTINGS в vault, вызовы
        // идут во внешний OpenAI-совместимый провайдер. На старте vault залочен (settings = дефолт),
        // после unlock контроллер перечитывает настройки в onVaultUnlocked.
        val aiSettingsStore = AiSettingsStore(vault)
        val ai = AiAssistantController(
            initialSettings = aiSettingsStore.load(),
            persist = aiSettingsStore::save,
            providerFactory = { cfg -> OpenAiProvider.pooled(cfg) },
            scope = tunnelScope,
            reload = aiSettingsStore::load,
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
            writeRecentHostIds(dir, emptyList())
            writeCollapsedGroups(dir, emptySet())
            writeCustomGroups(dir, emptyList())
            // Заводской сброс: дополнительно сносим доверенные ключи (не-vault) и настройки терминала.
            if (resetScope == ResetScope.Everything) {
                knownHosts.mismatches.toList().forEach { knownHosts.reject(it) }
                knownHosts.entries.toList().forEach { knownHosts.forget(it) }
                writeTerminalFont(dir, TerminalFont.DEFAULT)
                writeTerminalFontSize(dir, DEFAULT_TERMINAL_FONT_SIZE)
                writeTerminalLineHeight(dir, DEFAULT_TERMINAL_LINE_HEIGHT)
                writeTerminalLetterSpacing(dir, DEFAULT_TERMINAL_LETTER_SPACING)
                writeUiLanguage(dir, UiLanguage.DEFAULT)
                writeTerminalScrollback(dir, DEFAULT_TERMINAL_SCROLLBACK)
                writeTerminalCursorStyle(dir, TerminalCursorStyle.DEFAULT)
                writeShowTerminalTitle(dir, false)
                writeAutoLock(dir, AutoLockDuration.DEFAULT)
            }
            hosts.reload()
            snippets.reload()
            tunnels.reload()
        }
        val deps = AppDependencies(transport = transport, hosts = hosts, vault = vault, credentials = credentials, knownHosts = knownHosts, keyGenerator = keyGenerator, certificateInspector = certificateInspector, tunnels = tunnels, snippets = snippets, sync = sync)
        // Размер окна подбираем под доступную область экрана (без таскбара): ~90% экрана в рамках
        // MIN_WINDOW…MAX_WINDOW, не больше самого экрана. maximumWindowBounds учитывает панели ОС.
        // Язык интерфейса живёт в корне: провайдер локали над темой должен реагировать на смену из
        // настроек и рекомпозировать всё дерево. onUiLanguageChange (из DesktopDesignState) обновляет
        // это состояние и пишет персист; DesktopDesignState держит копию для отображения в дропдауне.
        val currentUiLanguage = remember { mutableStateOf(readUiLanguage(dir)) }
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
                    initialInfoPanel = readInfoPanel(dir),
                    onInfoPanelChange = { writeInfoPanel(dir, it) },
                    initialCollapsedGroups = readCollapsedGroups(dir),
                    onCollapsedGroupsChange = { writeCollapsedGroups(dir, it) },
                    initialRecentHostIds = readRecentHostIds(dir),
                    onRecentHostIdsChange = { writeRecentHostIds(dir, it) },
                    // Пустые папки синкаются в vault: стартуем пусто (vault залочен), читаем через
                    // customGroupsProvider после unlock, пишем изменения в запись-макет.
                    initialCustomGroups = emptyList(),
                    onCustomGroupsChange = { groups -> workspaceLayout.write(workspaceLayout.read().copy(groups = groups)) },
                    customGroupsProvider = { workspaceLayout.read().groups },
                    initialSftpShowHidden = readSftpShowHidden(dir),
                    onSftpShowHiddenChange = { writeSftpShowHidden(dir, it) },
                    initialTerminalFont = readTerminalFont(dir),
                    onTerminalFontChange = { writeTerminalFont(dir, it) },
                    initialTerminalFontSize = readTerminalFontSize(dir),
                    onTerminalFontSizeChange = { writeTerminalFontSize(dir, it) },
                    initialTerminalLineHeight = readTerminalLineHeight(dir),
                    onTerminalLineHeightChange = { writeTerminalLineHeight(dir, it) },
                    initialTerminalLetterSpacing = readTerminalLetterSpacing(dir),
                    onTerminalLetterSpacingChange = { writeTerminalLetterSpacing(dir, it) },
                    initialTerminalTheme = readTerminalTheme(dir),
                    onTerminalThemeChange = { writeTerminalTheme(dir, it) },
                    initialUiLanguage = currentUiLanguage.value,
                    onUiLanguageChange = { currentUiLanguage.value = it; writeUiLanguage(dir, it) },
                    initialTerminalScrollback = readTerminalScrollback(dir),
                    onTerminalScrollbackChange = { writeTerminalScrollback(dir, it) },
                    initialTerminalCursorStyle = readTerminalCursorStyle(dir),
                    onTerminalCursorStyleChange = { writeTerminalCursorStyle(dir, it) },
                    initialShowTerminalTitleOnTabs = readShowTerminalTitle(dir),
                    onShowTerminalTitleOnTabsChange = { writeShowTerminalTitle(dir, it) },
                    initialAutoLock = readAutoLock(dir),
                    onAutoLockChange = { writeAutoLock(dir, it) },
                    initialShowRecent = readRecentShow(dir),
                    onShowRecentChange = { writeRecentShow(dir, it) },
                    initialRecentLimit = readRecentLimit(dir),
                    onRecentLimitChange = { writeRecentLimit(dir, it) },
                    vault = deps.vault,
                    biometrics = deps.biometrics,
                    securityLog = securityLog,
                    hosts = deps.hosts,
                    transport = deps.transport,
                    testTransport = probeTransport,
                    credentials = deps.credentials,
                    knownHosts = deps.knownHosts,
                    keyGenerator = deps.keyGenerator,
                    certificateInspector = deps.certificateInspector,
                    tunnels = deps.tunnels,
                    snippets = deps.snippets,
                    sync = deps.sync,
                    ai = ai,
                    onVaultUnlocked = onVaultUnlocked,
                    onVaultReset = onVaultReset,
                )
              }
            }
        }
    }
}
