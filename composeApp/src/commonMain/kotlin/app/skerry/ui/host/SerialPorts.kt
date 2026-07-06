package app.skerry.ui.host

import app.skerry.shared.serial.SerialPortInfo

/**
 * List of discovered serial ports for the picker in the New Connection form. Implemented per
 * platform over `SerialSystem` (lives in shared's jvmShared node, not visible directly from
 * commonMain UI): desktop uses jSerialComm, Android uses USB-OTG (enumerate without permission).
 * Empty list means no ports / platform unsupported, the form stays with a plain Device text field.
 */
expect fun listSerialPorts(): List<SerialPortInfo>
