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
            code = "abc-123_XYZ", // claim-код url-safe base64 (с -/_)
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
        assertNull(PairingPayload.decode("just some text"))          // не наш формат
        assertNull(PairingPayload.decode("sk1.onlytwo"))              // мало полей
        assertNull(PairingPayload.decode("sk9.a.b.c"))               // чужой префикс/версия
        assertNull(PairingPayload.decode("sk1.@@@.@@@.@@@"))         // не base64
        assertNull(PairingPayload.decode(""))
    }

    @Test
    fun `decode rejects a wrong-length transfer key before any network call`() {
        // Обрезанный QR: код структурно валиден, но ключ короче 32 байт — должен быть null (иначе
        // claimPairing сжёг бы одноразовый код). Собираем строку с 16-байтным ключом.
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
