package app.skerry.shared.team

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultCrypto
import okio.FileSystem
import okio.Path

/**
 * Файловые vault'ы команд: `<dir>/<teamId>.vault`, dataKey = teamKey (blob'ы записей
 * wire-совместимы между участниками — сервер и другие участники расшифровывают тем же ключом).
 * Ключ живёт записью TEAM в аккаунтном vault; здесь только открытие/создание файлов.
 * Инстансы кэшируются — один [Vault] на команду за процесс (у FileVault своя внутренняя блокировка).
 */
class TeamVaults(
    private val dir: Path,
    private val crypto: VaultCrypto,
    private val deviceId: String,
    private val fileSystem: FileSystem,
    private val harden: (Path) -> Unit = {},
    private val now: () -> String,
) {

    private val open = mutableMapOf<String, Vault>()

    /**
     * Открыть (создав при необходимости) vault команды. null — ключ не подошёл к существующему
     * файлу (например, команда пересоздана с новым ключом) — файл нужно сбросить через [reset].
     */
    fun open(teamId: String, teamKey: DataKey): Vault? {
        require(isSafeTeamId(teamId)) { "unsafe teamId" }
        open[teamId]?.let { cached ->
            if (cached.isUnlocked) return cached
        }
        // FileVault забирает владение переданным ключом (и затирает его при lock) — отдаём ему
        // копию, чтобы инстанс вызывающего пережил повторные open/lock-циклы.
        val ownedKey = DataKey(teamKey.bytes.copyOf())
        val vault = FileVault(
            path = dir / "$teamId.vault",
            crypto = crypto,
            deviceId = deviceId,
            fileSystem = fileSystem,
            harden = harden,
            now = now,
        )
        if (!vault.exists()) {
            fileSystem.createDirectories(dir)
            vault.createWithDataKey(ownedKey)
        } else {
            if (vault.unlockWithDataKey(ownedKey) != UnlockResult.Success) return null
            // unlockWithDataKey ключ не проверяет (в meta team-vault нет обёртки) — валидируем пробной
            // расшифровкой первой живой записи. Пустой vault принимает любой ключ: портить нечего.
            val probe = vault.records().firstOrNull { !it.deleted }
            if (probe != null && vault.openPayload(probe.id) == null) {
                vault.lock()
                return null
            }
        }
        open[teamId] = vault
        return vault
    }

    /** Залочить и забыть все открытые vault'ы (например, при локе аккаунтного vault). */
    fun lockAll() {
        open.values.forEach { it.lock() }
        open.clear()
    }

    /** Удалить файл команды (выход/удаление/отзыв доступа): локальная копия больше не нужна. */
    fun reset(teamId: String) {
        require(isSafeTeamId(teamId)) { "unsafe teamId" }
        open.remove(teamId)?.lock()
        fileSystem.delete(dir / "$teamId.vault", mustExist = false)
    }

    private companion object {
        /** teamId — клиентский UUID: только [a-z0-9-], иначе имя файла = инъекция пути. */
        fun isSafeTeamId(teamId: String): Boolean =
            teamId.isNotEmpty() && teamId.length <= 64 &&
                teamId.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
    }
}
