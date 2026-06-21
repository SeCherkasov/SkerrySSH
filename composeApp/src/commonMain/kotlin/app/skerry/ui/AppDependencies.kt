package app.skerry.ui

import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.identity.IdentityManagerController
import app.skerry.ui.known.KnownHostsController

/**
 * Граф зависимостей приложения, собираемый платформенной точкой входа и подаваемый в [App].
 *
 * Единый держатель вместо россыпи nullable-аргументов [App]: новая подсистема — это поле здесь,
 * а не ещё один параметр корневого composable. `null` означает «подсистема ещё не реализована на
 * этой платформе» (паритет): desktop собирает полный граф (sshj-транспорт, файловый менеджер
 * хостов, файловый vault), мобильные таргеты пока подают пустой граф и показывают плейсхолдер.
 */
data class AppDependencies(
    val transport: SshTransport? = null,
    val hosts: HostManagerController? = null,
    val vault: Vault? = null,
    val identities: IdentityManagerController? = null,
    /** Менеджер known-hosts (доверенные ключи + события смены ключа); `null` — подсистема не подключена. */
    val knownHosts: KnownHostsController? = null,
    /** Биометрическая разблокировка vault; `null` — платформа без биометрии (desktop MVP). */
    val biometrics: VaultBiometrics? = null,
)
