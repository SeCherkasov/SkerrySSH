package app.skerry.shared.tunnel

/**
 * Персистентное хранилище сохранённых туннелей (port forwarding). Платформенная реализация —
 * файловая (jvmShared), как у [app.skerry.shared.host.HostStore]. Контракт синхронный: мутации редки
 * и инициируются из UI. Реализации обязаны быть потокобезопасными.
 */
interface TunnelStore {
    /** Все туннели в порядке вставки/обновления. */
    fun all(): List<Tunnel>

    /** Создать новую запись или заменить существующую с тем же [Tunnel.id] (upsert). */
    fun put(tunnel: Tunnel)

    /** Удалить запись по id; отсутствующий id — no-op. */
    fun remove(id: String)
}
