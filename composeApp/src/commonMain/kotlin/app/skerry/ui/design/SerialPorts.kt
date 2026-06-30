package app.skerry.ui.design

import app.skerry.shared.serial.SerialPortInfo

/**
 * Список обнаруженных последовательных портов для пикера в форме New Connection. Реализуется
 * платформенно поверх `SerialSystem` (живёт в jvmShared-узле shared и напрямую из commonMain UI не
 * виден): desktop — jSerialComm, Android — USB-OTG (enumerate без разрешения). Пустой список — портов
 * нет/платформа не поддерживает: форма остаётся с текстовым полем Device.
 */
expect fun listSerialPorts(): List<SerialPortInfo>
