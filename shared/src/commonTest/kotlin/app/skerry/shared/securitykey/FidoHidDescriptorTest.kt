package app.skerry.shared.securitykey

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FidoHidDescriptorTest {
    @Test fun onlyFidoApplicationCollectionWithoutReportIdsIsAccepted() {
        assertTrue(isFidoHidDescriptor(byteArrayOf(0x06, 0xd0.toByte(), 0xf1.toByte(), 0x09, 1, 0xa1.toByte(), 1, 0xc0.toByte())))
        assertFalse(isFidoHidDescriptor(byteArrayOf(0x05, 1, 0x09, 6, 0xa1.toByte(), 1, 0xc0.toByte())))
        assertFalse(isFidoHidDescriptor(byteArrayOf(0x06, 0xd0.toByte(), 0xf1.toByte(), 0x09, 1, 0xa1.toByte(), 1, 0x85.toByte(), 1)))
        assertFalse(isFidoHidDescriptor(byteArrayOf(0x06, 0xd0.toByte())))
        assertFalse(isFidoHidDescriptor(ByteArray(4097)))
    }
}
