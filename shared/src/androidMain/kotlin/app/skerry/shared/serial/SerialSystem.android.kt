package app.skerry.shared.serial

/**
 * Android-реализация serial. Последовательный доступ на Android идёт через USB-OTG (USB Host API),
 * что требует выбора конкретного устройства и runtime-разрешения от системы — это отдельный шаг
 * (см. роадмап Phase 3). Пока порт недоступен: список пуст, открытие сообщает о неподдержке, а UI
 * показывает serial как desktop-функцию. Реализация держит контракт [SerialSystem], чтобы включение
 * USB-OTG позже не трогало общий стек транспорта/терминала.
 */
actual object SerialSystem {
    actual fun listPorts(): List<SerialPortInfo> = emptyList()

    actual fun open(config: SerialConfig): SerialPortHandle =
        throw SerialUnavailableException(
            "Последовательные порты на Android (USB-OTG) пока не поддерживаются",
        )
}
