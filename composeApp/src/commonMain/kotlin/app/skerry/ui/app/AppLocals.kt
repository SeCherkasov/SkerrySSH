package app.skerry.ui.app

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.vault.SshCertificateInspector
import app.skerry.shared.vault.SshKeyGenerator
import app.skerry.shared.vault.SecurityLog
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.ui.ai.AiAssistantController
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.session.SessionsController
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.tunnel.TunnelManager

/**
 * Фича-флаги отображения дизайн-слоя. Поставляются параметром в [DesktopDesignApp] и доступны
 * любому composable через [LocalFeatures]. Незавершённые фичи прячутся за флагом (а не удаляются
 * из макета), чтобы вернуть их одним переключателем, когда бэкенд готов.
 *
 * [ai] — AI-ассистент (Phase 2 / MVP2): нижний AI-bar, suggestion-карточки в терминале, выбор
 * AI-политики в New connection и таб «AI» в настройках. По умолчанию выключен — в MVP1 этих
 * элементов в UI нет, реализация остаётся заглушкой до MVP2.
 */
@Immutable
data class FeatureFlags(
    val ai: Boolean = false,
)

/** Текущие фича-флаги; дефолт — всё незавершённое выключено (мок-путь/превью и MVP1). */
val LocalFeatures: ProvidableCompositionLocal<FeatureFlags> = staticCompositionLocalOf { FeatureFlags() }

/**
 * Настройки двухпанельного SFTP, переживающие перезапуск. [showHidden] — показывать ли скрытые
 * объекты (dotfiles), как в mc; [setShowHidden] меняет значение И персистит его. Поставляется
 * [DesktopDesignApp] из платформенного хранилища; дефолт — показывать, без персиста (мок/превью).
 */
@Immutable
data class SftpPrefs(
    val showHidden: Boolean = true,
    val setShowHidden: (Boolean) -> Unit = {},
)

/** Текущие SFTP-настройки; дефолт — скрытые показаны, изменения никуда не пишутся (мок-путь/превью). */
val LocalSftpPrefs: ProvidableCompositionLocal<SftpPrefs> = staticCompositionLocalOf { SftpPrefs() }

/**
 * Живые бэкенды, подаваемые в дизайн-слой через CompositionLocal (тем же приёмом, что [LocalFonts]) —
 * чтобы не протаскивать контроллеры параметрами через каждый composable. `null` означает
 * мок-путь (офскрин-рендер/превью): composable рисует статичные данные [DesktopMockData].
 */
val LocalHosts: ProvidableCompositionLocal<HostManagerController?> = staticCompositionLocalOf { null }

/**
 * Менеджер keychain-секретов (ключи/пароли/сертификаты в открытом vault) — сырой материал, на
 * который ссылаются хосты по `credentialId`. `null` — мок-путь/превью без vault: разделы keychain
 * в [app.skerry.ui.vault.VaultView] рисуют статичный макет. Поставляется [DesktopDesignApp] за
 * гейтом мастер-пароля (там же, где списки перечитываются).
 */
val LocalCredentials: ProvidableCompositionLocal<CredentialManagerController?> = staticCompositionLocalOf { null }

/**
 * Генератор/инспектор SSH-ключей (создание пары в разделе Vault, вычисление отпечатка/типа уже
 * сохранённых ключей). `null` — мок-путь/превью без платформенной крипты: [app.skerry.ui.vault.VaultView]
 * рисует статичный макет, кнопка генерации недоступна. Поставляется [DesktopDesignApp] за гейтом vault.
 */
val LocalSshKeyGenerator: ProvidableCompositionLocal<SshKeyGenerator?> = staticCompositionLocalOf { null }

/**
 * Инспектор SSH-сертификатов (разбор метаданных импортированного `*-cert.pub`: principals, срок,
 * serial, CA). `null` — мок-путь/превью без платформенной реализации: раздел Certificates в
 * [app.skerry.ui.vault.VaultView] показывает заглушку. Поставляется [DesktopDesignApp] за гейтом vault.
 */
val LocalSshCertificateInspector: ProvidableCompositionLocal<SshCertificateInspector?> = staticCompositionLocalOf { null }

/**
 * Менеджер открытых сессий (вкладки + живые соединения). `null` — мок-путь без бэкенда соединений:
 * титулбар и терминал рисуют статичные данные макета.
 */
val LocalSessions: ProvidableCompositionLocal<SessionsController?> = staticCompositionLocalOf { null }

/**
 * Менеджер known-hosts (доверенные ключи хостов + незакрытые события смены ключа). `null` — мок-путь
 * без бэкенда: [app.skerry.ui.known.KnownHostsView] рисует статичную таблицу и панель смены ключа
 * из макета. Поставляется [DesktopDesignApp] за гейтом vault.
 */
val LocalKnownHosts: ProvidableCompositionLocal<KnownHostsController?> = staticCompositionLocalOf { null }

/**
 * Действие «подключиться к хосту»: резолвит секрет (keychain-секрет из vault или запрос пароля) и
 * открывает сессию. Поставляется корнем chrome ([DesktopDesignApp]); дефолт — no-op (мок-путь/превью).
 */
val LocalConnectHost: ProvidableCompositionLocal<(Host) -> Unit> = staticCompositionLocalOf { {} }

/**
 * Действие «открыть хост в split-панели активной вкладки»: тот же резолв секрета, что и
 * [LocalConnectHost], но открывает НОВУЮ независимую вторичную сессию рядом, а не
 * новую вкладку. Поставляется [DesktopDesignApp]; дефолт — no-op (мок-путь/превью).
 */
val LocalConnectSplit: ProvidableCompositionLocal<(Host) -> Unit> = staticCompositionLocalOf { {} }

/**
 * SSH-транспорт ИСКЛЮЧИТЕЛЬНО для разовых проверок «Test connection» из формы (коннект без открытия
 * сессии). Намеренно отделён от транспорта живых сессий: за ним read-only verifier
 * ([app.skerry.shared.ssh.ProbeHostKeyVerifier]), который НЕ заносит ключ нового хоста в known_hosts —
 * проба не должна фиксировать постоянное доверие (это делает только реальный коннект через TOFU).
 * Поэтому НЕ использовать этот слот для открытия настоящих сессий. `null` — мок-путь/превью без живого
 * транспорта: кнопка Test недоступна. Поставляется [DesktopDesignApp]; единственный потребитель —
 * [app.skerry.ui.host.NewConnectionModal].
 */
val LocalTestTransport: ProvidableCompositionLocal<SshTransport?> = staticCompositionLocalOf { null }

/**
 * Действие «открыть SFTP хоста»: тот же путь подключения, что и [LocalConnectHost] (резолв секрета,
 * возобновление живой сессии), но в конце ведёт на таб Files (Remote-браузер), а не на терминал —
 * чтобы кнопка SFTP детали хоста сразу показывала файлы без отдельного Connect. Дефолт — no-op (превью).
 */
val LocalOpenSftp: ProvidableCompositionLocal<(Host) -> Unit> = staticCompositionLocalOf { {} }

/**
 * Менеджер глобальных сохранённых туннелей: список пробросов + включение/выключение,
 * каждый сам открывает соединение к хосту. `null` — мок-путь/превью без бэкенда:
 * [app.skerry.ui.tunnel.TunnelsView] рисует статичный макет. Поставляется [DesktopDesignApp] за гейтом
 * vault (резолв секрета хоста требует открытого vault).
 */
val LocalTunnels: ProvidableCompositionLocal<TunnelManager?> = staticCompositionLocalOf { null }

/**
 * Менеджер сохранённых сниппетов: библиотека команд + запуск в активном терминале.
 * `null` — мок-путь/превью без бэкенда: [app.skerry.ui.snippet.SnippetsView] рисует статичный макет.
 * Поставляется [DesktopDesignApp] (сниппеты — plain-конфиг, vault не требуют).
 */
val LocalSnippets: ProvidableCompositionLocal<SnippetManager?> = staticCompositionLocalOf { null }

/**
 * Действие «Run on host» сниппета: открыть/использовать сессию к [Host] и выполнить переданную команду
 * сразу после подключения (запуск на выбранном хосте, а не только в активном
 * терминале). Резолвит секрет тем же путём, что [LocalConnectHost] (keychain или запрос пароля).
 * Поставляется [DesktopDesignApp]; дефолт — no-op (мок-путь/превью).
 */
val LocalRunSnippetOnHost: ProvidableCompositionLocal<(Host, String) -> Unit> = staticCompositionLocalOf { { _, _ -> } }

/**
 * Открытый [Vault] за гейтом мастер-пароля — нужен экрану настроек (More), чтобы включить/выключить
 * разблокировку биометрией (обёртка `dataKey` под `bioKey`). `null` — мок-путь/превью без vault.
 */
val LocalVault: ProvidableCompositionLocal<Vault?> = staticCompositionLocalOf { null }

/**
 * Оркестратор биометрии vault. `null` — биометрия не сконфигурирована на платформе (desktop без
 * железа/офскрин): экран настроек прячет тумблер. Поставляется за гейтом vault теми же провайдерами.
 */
val LocalVaultBiometrics: ProvidableCompositionLocal<VaultBiometrics?> = staticCompositionLocalOf { null }

/**
 * Локальный журнал событий безопасности (Settings → Безопасность): недавние события + дериватив
 * «последняя смена пароля». `null` — мок/превью: секция рисует пустой журнал и нейтральную подпись.
 * Не синкается (аудит конкретного устройства). Поставляется за гейтом vault.
 */
val LocalSecurityLog: ProvidableCompositionLocal<SecurityLog?> = staticCompositionLocalOf { null }

/**
 * Координатор self-hosted синхронизации (Phase 2): register/login/syncNow/disconnect + поток статуса.
 * `null` — мок-путь/превью или платформа без sync: секция Sync в настройках рисует статичный макет.
 * Поставляется [DesktopDesignApp]/[MobileDesignApp] за гейтом vault (для серверной обёртки нужен dataKey).
 */
val LocalSync: ProvidableCompositionLocal<SyncCoordinator?> = staticCompositionLocalOf { null }

/**
 * Координатор Teams (шеринг хостов/секретов/сниппетов между аккаунтами поверх sync-сервера).
 * `null` — мок-путь/превью или sync не подключён: экран Teams рисует пустое состояние с
 * приглашением настроить синхронизацию. Поставляется вместе с [LocalSync].
 */
val LocalTeams: ProvidableCompositionLocal<app.skerry.ui.teams.TeamsCoordinator?> = staticCompositionLocalOf { null }

/**
 * Контроллер AI-ассистента (внешний OpenAI-совместимый провайдер, BYOK). `null` — мок-путь/превью
 * или платформа без AI: таб «AI» в настройках рисует статичный макет. Когда контроллер задан, таб
 * становится живым (ввод ключа/модели, быстрый чат), независимо от [FeatureFlags.ai], который
 * по-прежнему гейтит незавершённые AI-поверхности терминала. Поставляется за гейтом vault
 * (ключ хранится зашифрованным в vault).
 */
val LocalAi: ProvidableCompositionLocal<AiAssistantController?> = staticCompositionLocalOf { null }
