package app.skerry.ui.host

import app.skerry.shared.serial.SerialPortInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class SerialPortOptionsTest {

    @Test
    fun adapters_come_before_the_legacy_uarts_the_kernel_always_lists() {
        // Linux enumerates /dev/ttyS0..31 from the 8250 driver whether or not anything is wired to
        // them, and they all describe themselves the same way. The adapter the user actually plugged
        // in has to be the first thing in the menu, not the 33rd.
        val ports = listOf(
            SerialPortInfo("/dev/ttyS4", "Serial Device (serial_8250)"),
            SerialPortInfo("/dev/ttyUSB0", "FT232R USB UART"),
            SerialPortInfo("/dev/ttyS0", "Serial Device (serial_8250)"),
            SerialPortInfo("/dev/ttyACM0", "STM32 Virtual ComPort"),
        )

        assertEquals(
            listOf("/dev/ttyACM0", "/dev/ttyUSB0", "/dev/ttyS0", "/dev/ttyS4"),
            serialPortOptions(ports).map { it.systemName },
        )
    }

    @Test
    fun legacy_uarts_are_ordered_by_number_not_by_text() {
        // ttyS10 sorts before ttyS2 as text, which reads as a shuffled list.
        val ports = listOf(
            SerialPortInfo("/dev/ttyS10", "Serial Device (serial_8250)"),
            SerialPortInfo("/dev/ttyS2", "Serial Device (serial_8250)"),
        )

        assertEquals(listOf("/dev/ttyS2", "/dev/ttyS10"), serialPortOptions(ports).map { it.systemName })
    }

    @Test
    fun a_port_listed_twice_appears_once() {
        val ports = listOf(
            SerialPortInfo("/dev/ttyUSB0", "FT232R USB UART"),
            SerialPortInfo("/dev/ttyUSB0", "FT232R USB UART"),
        )

        assertEquals(1, serialPortOptions(ports).size)
    }

    @Test
    fun windows_ports_sort_by_number_inside_the_adapter_group() {
        // COM ports carry no 8250 marker, so they stay in the group the user cares about — and a hub
        // of virtual ports must not read COM1, COM10, COM11, COM2.
        val ports = listOf(
            SerialPortInfo("COM3", "USB Serial Port"),
            SerialPortInfo("COM10", "USB Serial Port"),
            SerialPortInfo("COM1", "Communications Port"),
        )

        assertEquals(listOf("COM1", "COM3", "COM10"), serialPortOptions(ports).map { it.systemName })
    }

    @Test
    fun adapter_families_stay_together() {
        // ttyACM and ttyUSB are different devices; sorting by index alone would interleave them.
        val ports = listOf(
            SerialPortInfo("/dev/ttyUSB1", "FT232R USB UART"),
            SerialPortInfo("/dev/ttyACM0", "STM32 Virtual ComPort"),
            SerialPortInfo("/dev/ttyUSB0", "FT232R USB UART"),
        )

        assertEquals(
            listOf("/dev/ttyACM0", "/dev/ttyUSB0", "/dev/ttyUSB1"),
            serialPortOptions(ports).map { it.systemName },
        )
    }
}
