package app.skerry.ui.host

import app.skerry.shared.serial.SerialPortInfo
import app.skerry.shared.serial.SerialSystem

/** Android: enumerates connected USB-OTG serial devices via `SerialSystem` (no permission required). */
actual fun listSerialPorts(): List<SerialPortInfo> =
    runCatching { SerialSystem.listPorts() }.getOrDefault(emptyList())
