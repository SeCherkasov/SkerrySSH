package app.skerry.shared.vault

import app.skerry.shared.host.FileHostStore
import app.skerry.shared.host.VaultHostStore
import app.skerry.shared.snippet.FileSnippetStore
import app.skerry.shared.snippet.VaultSnippetStore
import app.skerry.shared.ssh.FileKnownHostsStore
import app.skerry.shared.ssh.KnownHost
import app.skerry.shared.ssh.VaultKnownHostsStore
import app.skerry.shared.tunnel.FileTunnelStore
import app.skerry.shared.tunnel.VaultTunnelStore
import java.nio.file.Files
import java.nio.file.Path

/**
 * Разовый перенос локального рабочего пространства (хосты, сниппеты, туннели) из открытых файловых
 * сторов в зашифрованный [Vault] — Phase A «как у Termius»: всё workspace E2E-синкается, а не только
 * секреты. До неё хосты/сниппеты/туннели жили в JSON-файлах `~/.config/skerry` (или `filesDir` на Android)
 * и в облако не уходили; после — это записи vault, которые тащит существующий `SyncEngine`.
 *
 * Идемпотентна и самоопределяющаяся: признак «уже мигрировано» — отсутствие legacy-файла. Каждый тип
 * переносится отдельно: читаем файл, докладываем в vault только отсутствующие id (не перетираем то,
 * что уже синканулось/смигрировалось), затем удаляем файл. Падение на одном типе не мешает другим
 * (каждый под `runCatching` у вызывающего). Требует разблокированного [vault] (зовётся при unlock).
 *
 * Не трогает per-device UI-префы (`collapsed_groups`, `recent_connections`) — они локальные и не
 * синкаются. Known-hosts (TOFU) мигрируются отдельно по политике (синк доверенных ключей опционален).
 */
class WorkspaceMigration(
    private val vault: Vault,
    private val configDir: Path,
) {

    /** Выполнить миграцию всех типов; возвращает `true`, если что-то перенесено (для логов/тестов). */
    fun migrate(): Boolean {
        var changed = false
        changed = migrateHosts() || changed
        changed = migrateSnippets() || changed
        changed = migrateTunnels() || changed
        changed = migrateKnownHosts() || changed
        changed = migrateCustomGroups() || changed
        return changed
    }

    /**
     * Пустые папки хостов: были в построчном файле `custom_groups`, теперь — в синкаемой записи-макете
     * ([WorkspaceLayout.groups]). Объединяем со списком в vault (на случай уже синканутых имён), затем
     * удаляем файл, подтвердив, что все имена попали в макет. Пустой/нечитаемый файл не удаляем — повтор
     * безвреден. collapsed_groups/recent_connections НЕ трогаем — это per-device UI, остаётся локальным.
     */
    private fun migrateCustomGroups(): Boolean {
        val file = configDir.resolve("custom_groups")
        if (!Files.exists(file)) return false
        val legacy = runCatching {
            Files.readAllBytes(file).decodeToString().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
        if (legacy.isEmpty()) return false
        val layoutStore = WorkspaceLayoutStore(vault)
        val current = layoutStore.read()
        layoutStore.write(current.copy(groups = (current.groups + legacy).distinct()))
        if (layoutStore.read().groups.containsAll(legacy)) Files.delete(file)
        return true
    }

    private fun migrateKnownHosts(): Boolean {
        val file = configDir.resolve("known_hosts")
        if (!Files.exists(file)) return false
        val legacy = FileKnownHostsStore(file).all()
        val target = VaultKnownHostsStore(vault)
        val existing = target.all().mapTo(mutableSetOf()) { keyOf(it) }
        legacy.filter { keyOf(it) !in existing }.forEach { target.add(it) }
        deleteIfConfirmed(file, legacy.map { keyOf(it) }, target.all().mapTo(mutableSetOf()) { keyOf(it) })
        return legacy.isNotEmpty()
    }

    /** Идентичность доверенного ключа (host, port, keyType) — для дедупликации/подтверждения миграции. */
    private fun keyOf(h: KnownHost): String = "${h.host}\u001F${h.port}\u001F${h.keyType}"

    private fun migrateHosts(): Boolean {
        val file = configDir.resolve("hosts.json")
        if (!Files.exists(file)) return false
        val legacy = FileHostStore(file).all()
        val target = VaultHostStore(vault)
        val existing = target.all().mapTo(mutableSetOf()) { it.id }
        legacy.filter { it.id !in existing }.forEach { target.put(it) }
        deleteIfConfirmed(file, legacy.map { it.id }, target.all().mapTo(mutableSetOf()) { it.id })
        return legacy.isNotEmpty()
    }

    private fun migrateSnippets(): Boolean {
        val file = configDir.resolve("snippets.json")
        if (!Files.exists(file)) return false
        val legacy = FileSnippetStore(file).all()
        val target = VaultSnippetStore(vault)
        val existing = target.all().mapTo(mutableSetOf()) { it.id }
        legacy.filter { it.id !in existing }.forEach { target.put(it) }
        deleteIfConfirmed(file, legacy.map { it.id }, target.all().mapTo(mutableSetOf()) { it.id })
        return legacy.isNotEmpty()
    }

    private fun migrateTunnels(): Boolean {
        val file = configDir.resolve("tunnels.json")
        if (!Files.exists(file)) return false
        val legacy = FileTunnelStore(file).all()
        val target = VaultTunnelStore(vault)
        val existing = target.all().mapTo(mutableSetOf()) { it.id }
        legacy.filter { it.id !in existing }.forEach { target.put(it) }
        deleteIfConfirmed(file, legacy.map { it.id }, target.all().mapTo(mutableSetOf()) { it.id })
        return legacy.isNotEmpty()
    }

    /**
     * Удалить legacy-файл ТОЛЬКО когда каждый прочитанный из него id подтверждён в vault. Файловые
     * сторы при повреждённом/недописанном JSON молча отдают пусто (`runCatching` глотает ошибку
     * разбора) — тогда [legacyIds] пуст, а файл мог содержать данные. Не удаляем его: иначе потеряем
     * единственную копию необратимо (security M1). Повтор миграции на следующем unlock идемпотентен;
     * валидно-пустой файл (`[]`) безвреден — переоткрывается каждый раз, пустой no-op.
     *
     * Содержимое было открытым и ДО Phase A — здесь только удаляем ссылку (unlink). Перезапись секторов
     * на CoW/SSD-ФС ненадёжна и не даёт гарантий, поэтому secure-erase не делаем (принятый риск: доступ
     * к сырым секторам требует root/физического доступа, данные и так лежали в открытом виде).
     */
    private fun deleteIfConfirmed(file: Path, legacyIds: List<String>, migratedIds: Set<String>) {
        if (legacyIds.isNotEmpty() && legacyIds.all { it in migratedIds }) Files.delete(file)
    }
}
