package app.skerry.ui.host

import app.skerry.shared.serial.SerialPortInfo

/**
 * List of discovered serial ports for the picker in the New Connection form. Implemented per
 * platform over `SerialSystem` (lives in shared's jvmShared node, not visible directly from
 * commonMain UI): desktop uses jSerialComm, Android uses USB-OTG (enumerate without permission).
 * Empty list means no ports / platform unsupported, the form stays with a plain Device text field.
 */
expect fun listSerialPorts(): List<SerialPortInfo>

/**
 * The discovered ports in the order the device menu offers them: adapters first, then the legacy
 * 8250 UARTs (`/dev/ttyS0`…`ttyS31`) the Linux kernel enumerates whether or not anything is wired to
 * them. They are kept rather than hidden — a real RS-232 header lives there on server boards — but a
 * plugged-in adapter must not sit below three dozen identical rows to reach. Duplicates are dropped
 * by port name, and the legacy ones sort by index so ttyS2 comes before ttyS10.
 */
fun serialPortOptions(ports: List<SerialPortInfo>): List<SerialPortInfo> =
    ports.distinctBy { it.systemName }
        .sortedWith(compareBy({ isLegacyUart(it.systemName) }, { portStem(it.systemName) }, { portIndex(it.systemName) }))

/** A legacy `/dev/ttyS<n>` UART — the ones the Linux kernel lists whether or not they exist. */
private fun isLegacyUart(systemName: String): Boolean =
    systemName.startsWith("/dev/ttyS") && portIndex(systemName) != null

/** The name without its trailing number, so ttyUSB and ttyACM stay apart while their indices sort. */
private fun portStem(systemName: String): String = systemName.dropLastWhile { it.isDigit() }

/** The trailing number of a port name, or null when it has none — COM10 must not sort before COM2. */
private fun portIndex(systemName: String): Int? = systemName.takeLastWhile { it.isDigit() }.toIntOrNull()
