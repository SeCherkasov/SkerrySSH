package app.skerry.ui.design

import app.skerry.shared.serial.SerialPortInfo
import app.skerry.shared.serial.SerialSystem

/** Android: перечисление подключённых USB-OTG serial устройств через `SerialSystem` (без разрешения). */
actual fun listSerialPorts(): List<SerialPortInfo> =
    runCatching { SerialSystem.listPorts() }.getOrDefault(emptyList())
