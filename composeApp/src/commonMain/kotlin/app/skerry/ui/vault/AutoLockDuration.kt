package app.skerry.ui.vault

/**
 * Порог автоблокировки по простою (Настройки → Безопасность → Автоблокировка). Управляет idle-таймером
 * в [app.skerry.ui.vault.VaultGate]: бездействие дольше [idleMs] запирает vault. [idleMs] == `null`
 * ([Never]) выключает таймер простоя (блокировка при уходе в фон остаётся — это отдельная политика
 * платформы, см. `deviceMandatesAutoLock`). Стабильный [id] переживает перезапуск (персист в файле).
 */
enum class AutoLockDuration(val id: String, val idleMs: Long?) {
    OneMinute("1m", 60_000L),
    FiveMinutes("5m", 5 * 60_000L),
    FifteenMinutes("15m", 15 * 60_000L),
    ThirtyMinutes("30m", 30 * 60_000L),
    Never("never", null);

    companion object {
        /** Значение по умолчанию — 5 минут (как прежний зашитый порог idle-таймера). */
        val DEFAULT: AutoLockDuration = FiveMinutes

        /** Разбор стабильного [id] из персиста; неизвестный/пустой → [DEFAULT]. */
        fun fromId(id: String?): AutoLockDuration = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
