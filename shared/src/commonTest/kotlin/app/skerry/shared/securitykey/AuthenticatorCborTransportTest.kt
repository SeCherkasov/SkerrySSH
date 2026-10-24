package app.skerry.shared.securitykey

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class AuthenticatorCborTransportTest {
    private val bomb = byteArrayOf(0, 0xa1.toByte(), 1, 0x5a, 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
    @Test fun hidRejectsAllocationBombBeforePublishingItsFirstPacket() {
        val frame = hid(bomb)
        assertFailsWith<SecurityKeyException> { validatedHidCborFrames(frame, 4) { error("unexpected continuation") } }
    }
    @Test fun nfcValidatesTheWholeChainedResponseBeforeReturningItToSdk() {
        val first = bomb.copyOfRange(0, 4) + byteArrayOf(0x61, 0)
        val last = bomb.copyOfRange(4, bomb.size) + byteArrayOf(0x90.toByte(), 0)
        assertFailsWith<SecurityKeyException> { validatedCtapApduResponse(first, 4) { last } }
    }
    @Test fun validFramesAndApduChainsArePreserved() {
        val body = byteArrayOf(0, 0xa1.toByte(), 1, 0x81.toByte(), 0x68) + "FIDO_2_0".encodeToByteArray()
        val frame = hid(body)
        assertContentEquals(frame, validatedHidCborFrames(frame, 4) { error("unexpected continuation") }.single())
        assertContentEquals(body + byteArrayOf(0x90.toByte(), 0), validatedCtapApduResponse(body.copyOfRange(0, 4) + byteArrayOf(0x61, 0), 4) {
            body.copyOfRange(4, body.size) + byteArrayOf(0x90.toByte(), 0)
        })
    }
    private fun hid(payload: ByteArray) = ByteArray(64).apply {
        this[0] = 1; this[4] = 0x90.toByte(); this[6] = payload.size.toByte(); payload.copyInto(this, 7)
    }
}
