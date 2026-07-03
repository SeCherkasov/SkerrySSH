package app.skerry.ui

import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.vault.SecurityLog
import app.skerry.ui.ai.LocalAiDeps
import app.skerry.shared.vault.SshCertificateInspector
import app.skerry.shared.vault.SshKeyGenerator
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.tunnel.TunnelManager

/**
 * Граф зависимостей приложения, собираемый платформенной точкой входа (desktop `main`) и подаваемый
 * в корневой composable (`DesktopDesignApp`/`MobileDesignApp`).
 *
 * Единый держатель вместо россыпи nullable-аргументов: новая подсистема — это поле здесь,
 * а не ещё один параметр корневого composable. `null` означает «подсистема ещё не реализована на
 * этой платформе» (паритет): desktop собирает полный граф (sshj-транспорт, файловый менеджер
 * хостов, файловый vault), мобильные таргеты пока подают пустой граф и показывают плейсхолдер.
 */
data class AppDependencies(
    val transport: SshTransport? = null,
    val hosts: HostManagerController? = null,
    val vault: Vault? = null,
    /** Менеджер keychain-секретов (ключи/пароли/сертификаты); `null` — подсистема не подключена. */
    val credentials: CredentialManagerController? = null,
    /** Менеджер known-hosts (доверенные ключи + события смены ключа); `null` — подсистема не подключена. */
    val knownHosts: KnownHostsController? = null,
    /** Генератор/инспектор SSH-ключей (раздел Vault); `null` — платформа без крипты ключей. */
    val keyGenerator: SshKeyGenerator? = null,
    /** Инспектор SSH-сертификатов (раздел Vault → Certificates); `null` — платформа без разбора cert. */
    val certificateInspector: SshCertificateInspector? = null,
    /** Менеджер глобальных сохранённых туннелей (раздел Tunnels); `null` — подсистема не подключена. */
    val tunnels: TunnelManager? = null,
    /** Менеджер сохранённых сниппетов (раздел Snippets); `null` — подсистема не подключена. */
    val snippets: SnippetManager? = null,
    /** Биометрическая разблокировка vault; `null` — платформа без биометрии (desktop MVP). */
    val biometrics: VaultBiometrics? = null,
    /** Координатор self-hosted синхронизации (Phase 2); `null` — sync не подключён на платформе. */
    val sync: SyncCoordinator? = null,
    /** Локальный журнал событий безопасности (Settings → Безопасность); `null` — журнал не ведётся. */
    val securityLog: SecurityLog? = null,
    /** Локальный AI (Phase 3): стор моделей + загрузчик + рантайм; `null` — превью/мок без подсистемы. */
    val localAi: LocalAiDeps? = null,
)
