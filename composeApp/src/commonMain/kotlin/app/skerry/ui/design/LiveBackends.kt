package app.skerry.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import app.skerry.shared.host.Host
import app.skerry.shared.vault.SshCertificateInspector
import app.skerry.shared.vault.SshKeyGenerator
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.identity.IdentityManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.session.SessionsController

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
 * Менеджер переиспользуемых identity (пароли/ключи в открытом vault). `null` — мок-путь/превью без
 * vault: форма «New connection» не предлагает сохранённых секретов и не создаёт новые. Поставляется
 * [DesktopDesignApp] за гейтом мастер-пароля — там же, где список перечитывается ([reload]).
 */
val LocalIdentities: ProvidableCompositionLocal<IdentityManagerController?> = staticCompositionLocalOf { null }

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
 * Действие «подключиться к хосту»: резолвит секрет (identity из vault или запрос пароля) и открывает
 * сессию. Поставляется корнем chrome ([DesktopDesignApp]); дефолт — no-op (мок-путь/превью).
 */
val LocalConnectHost: ProvidableCompositionLocal<(Host) -> Unit> = staticCompositionLocalOf { {} }
