package app.skerry.ui.forward

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForwardInputTest {

    @Test
    fun `parses a well-formed forward and trims the host`() {
        val req = parseForwardInput(bindPort = "8080", destHost = "  10.0.0.5 ", destPort = "80")
        assertEquals(ForwardRequest(bindPort = 8080, destHost = "10.0.0.5", destPort = 80), req)
    }

    @Test
    fun `allows bind port zero (OS-assigned listener)`() {
        val req = parseForwardInput(bindPort = "0", destHost = "db.internal", destPort = "5432")
        assertEquals(ForwardRequest(bindPort = 0, destHost = "db.internal", destPort = 5432), req)
    }

    @Test
    fun `rejects a blank destination host`() {
        assertNull(parseForwardInput(bindPort = "8080", destHost = "   ", destPort = "80"))
    }

    @Test
    fun `rejects a non-numeric bind port`() {
        assertNull(parseForwardInput(bindPort = "abc", destHost = "host", destPort = "80"))
    }

    @Test
    fun `rejects a bind port above the valid range`() {
        assertNull(parseForwardInput(bindPort = "70000", destHost = "host", destPort = "80"))
    }

    @Test
    fun `rejects destination port zero`() {
        // У слушателя 0 = «выбери сам», но порт назначения 0 бессмыслен — туда некуда подключаться.
        assertNull(parseForwardInput(bindPort = "8080", destHost = "host", destPort = "0"))
    }

    @Test
    fun `rejects a destination port above the valid range`() {
        assertNull(parseForwardInput(bindPort = "8080", destHost = "host", destPort = "65536"))
    }

    @Test
    fun `rejects an empty bind port`() {
        assertNull(parseForwardInput(bindPort = "", destHost = "host", destPort = "80"))
    }

    @Test
    fun `rejects a negative bind port`() {
        // UI фильтрует ввод до цифр, но функция публична — фиксируем контракт явно.
        assertNull(parseForwardInput(bindPort = "-1", destHost = "host", destPort = "80"))
    }
}
