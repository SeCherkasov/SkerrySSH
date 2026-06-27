package app.skerry.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.vault.SshCertificateInspector
import app.skerry.shared.vault.SshKeyGenerator
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.session.SessionsController
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
 * Живые бэкенды, подаваемые в дизайн-слой через CompositionLocal (тем же приёмом, что [LocalFonts]) —
 * чтобы не протаскивать контроллеры параметрами через каждый composable макета. `null` означает
 * мок-путь (офскрин-рендер/превью): composable рисует статичные данные [DesktopMockData].
 *
 * [DesktopDesignApp] поставляет их за гейтом vault; по мере разводки бэкендов сюда добавятся
 * SFTP/форвардинг следующими слайсами.
 */
val LocalHosts: ProvidableCompositionLocal<HostManagerController?> = staticCompositionLocalOf { null }

/**
 * Менеджер keychain-секретов (ключи/пароли/сертификаты в открытом vault) — сырой материал, на
 * который ссылаются хосты по `credentialId`. `null` — мок-путь/превью без vault: разделы keychain
 * в [app.skerry.ui.design.VaultView] рисуют статичный макет. Поставляется [DesktopDesignApp] за
 * гейтом мастер-пароля (там же, где списки перечитываются).
 */
val LocalCredentials: ProvidableCompositionLocal<CredentialManagerController?> = staticCompositionLocalOf { null }

/**
 * Генератор/инспектор SSH-ключей (создание пары в разделе Vault, вычисление отпечатка/типа уже
 * сохранённых ключей). `null` — мок-путь/превью без платформенной крипты: [app.skerry.ui.design.VaultView]
 * рисует статичный макет, кнопка генерации недоступна. Поставляется [DesktopDesignApp] за гейтом vault.
 */
val LocalSshKeyGenerator: ProvidableCompositionLocal<SshKeyGenerator?> = staticCompositionLocalOf { null }

/**
 * Инспектор SSH-сертификатов (разбор метаданных импортированного `*-cert.pub`: principals, срок,
 * serial, CA). `null` — мок-путь/превью без платформенной реализации: раздел Certificates в
 * [app.skerry.ui.design.VaultView] показывает заглушку. Поставляется [DesktopDesignApp] за гейтом vault.
 */
val LocalSshCertificateInspector: ProvidableCompositionLocal<SshCertificateInspector?> = staticCompositionLocalOf { null }

/**
 * Менеджер открытых сессий (вкладки + живые соединения). `null` — мок-путь без бэкенда соединений:
 * титулбар и терминал рисуют статичные данные макета.
 */
val LocalSessions: ProvidableCompositionLocal<SessionsController?> = staticCompositionLocalOf { null }

/**
 * Менеджер known-hosts (доверенные ключи хостов + незакрытые события смены ключа). `null` — мок-путь
 * без бэкенда: [app.skerry.ui.design.KnownHostsView] рисует статичную таблицу и панель смены ключа
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
 * [LocalConnectHost], но открывает НОВУЮ независимую вторичную сессию рядом (модель Termius), а не
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
 * [app.skerry.ui.design.NewConnectionModal].
 */
val LocalTestTransport: ProvidableCompositionLocal<SshTransport?> = staticCompositionLocalOf { null }

/**
 * Действие «открыть SFTP хоста»: тот же путь подключения, что и [LocalConnectHost] (резолв секрета,
 * возобновление живой сессии), но в конце ведёт на таб Files (Remote-браузер), а не на терминал —
 * чтобы кнопка SFTP детали хоста сразу показывала файлы без отдельного Connect. Дефолт — no-op (превью).
 */
val LocalOpenSftp: ProvidableCompositionLocal<(Host) -> Unit> = staticCompositionLocalOf { {} }

/**
 * Менеджер глобальных сохранённых туннелей (модель Termius): список пробросов + включение/выключение,
 * каждый сам открывает соединение к хосту. `null` — мок-путь/превью без бэкенда:
 * [app.skerry.ui.design.TunnelsView] рисует статичный макет. Поставляется [DesktopDesignApp] за гейтом
 * vault (резолв секрета хоста требует открытого vault).
 */
val LocalTunnels: ProvidableCompositionLocal<TunnelManager?> = staticCompositionLocalOf { null }

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
