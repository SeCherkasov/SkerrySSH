package app.skerry.ui.host

import app.skerry.shared.serial.SerialPortInfo
import app.skerry.shared.serial.SerialSystem

/** Desktop: перечисление портов через `SerialSystem` (jSerialComm). */
actual fun listSerialPorts(): List<SerialPortInfo> =
    runCatching { SerialSystem.listPorts() }.getOrDefault(emptyList())
