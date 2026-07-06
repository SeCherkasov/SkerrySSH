package app.skerry.ui.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingPayloadTest {

    @Test
    fun `round-trips through encode and decode`() {
        val original = PairingPayload(
            serverUrl = "https://sync.example.com:8443/base",
            code = "abc-123_XYZ", // claim code, url-safe base64 (with -/_)
            transferKey = ByteArray(32) { it.toByte() },
        )

        val decoded = PairingPayload.decode(original.encode())

        assertEquals(original, decoded)
    }

    @Test
    fun `decode trims surrounding whitespace from manual paste`() {
        val payload = PairingPayload("https://h", "code", ByteArray(32) { 7 })

        assertEquals(payload, PairingPayload.decode("  \n" + payload.encode() + " \n"))
    }

    @Test
    fun `decode rejects foreign or malformed strings`() {
        assertNull(PairingPayload.decode("just some text"))          // not our format
        assertNull(PairingPayload.decode("sk1.onlytwo"))              // too few fields
        assertNull(PairingPayload.decode("sk9.a.b.c"))               // wrong prefix/version
        assertNull(PairingPayload.decode("sk1.@@@.@@@.@@@"))         // not base64
        assertNull(PairingPayload.decode(""))
    }

    @Test
    fun `decode rejects a wrong-length transfer key before any network call`() {
        // Truncated QR: the code is structurally valid but the key is shorter than 32 bytes — must
        // be null (otherwise claimPairing would burn the one-time code). Build a string with a 16-byte key.
        val truncated = PairingPayload("https://h", "code", ByteArray(16) { 1 }).encode()
        assertNull(PairingPayload.decode(truncated))
    }

    @Test
    fun `decode rejects non-http server url schemes`() {
        val weird = PairingPayload("file:///etc/passwd", "code", ByteArray(32) { 1 }).encode()
        assertNull(PairingPayload.decode(weird))
    }

    @Test
    fun `toString does not leak the transfer key or code`() {
        val s = PairingPayload("https://h", "secret-code", ByteArray(32) { 1 }).toString()

        assertTrue("secret-code" !in s)
        assertTrue(s.contains("transferKey=***"))
    }
}
