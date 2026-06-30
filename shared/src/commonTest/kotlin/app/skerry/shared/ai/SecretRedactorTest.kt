package app.skerry.shared.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretRedactorTest {

    @Test
    fun `masks keyed password while keeping the key visible`() {
        val out = SecretRedactor.redact("run with password=hunter2 please")
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("password"))
        assertTrue(out.contains(SecretRedactor.MASK))
    }

    @Test
    fun `masks bearer tokens and authorization headers`() {
        val out = SecretRedactor.redact("Authorization: Bearer abcDEF123456ghiJKL7890mnoPQRst")
        assertFalse(out.contains("abcDEF123456ghiJKL7890mnoPQRst"))
    }

    @Test
    fun `masks long high-entropy tokens`() {
        val token = "AKIA1234567890ABCDEFghijklmnopqrstuvwxyz"
        val out = SecretRedactor.redact("key is $token here")
        assertFalse(out.contains(token))
        assertTrue(out.contains(SecretRedactor.MASK))
    }

    @Test
    fun `masks pem private key blocks`() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAAsecretbytes\n-----END OPENSSH PRIVATE KEY-----"
        val out = SecretRedactor.redact("here: $pem")
        assertFalse(out.contains("secretbytes"))
        assertTrue(out.contains(SecretRedactor.MASK))
    }

    @Test
    fun `masks secrets in underscore and hyphen joined identifiers`() {
        // Регрессия: `\b` не срабатывал на составных ключах (подчёркивание — словесный символ).
        listOf("DB_PASSWORD=hunter2", "client_secret: abc123", "api-key=xyz789", "MY_TOKEN=zzz").forEach { line ->
            val out = SecretRedactor.redact("connect $line now")
            assertTrue(out.contains(SecretRedactor.MASK), "should redact: $line")
            listOf("hunter2", "abc123", "xyz789", "zzz").forEach { secret ->
                if (line.contains(secret)) assertFalse(out.contains(secret), "leaked $secret from $line")
            }
        }
    }

    @Test
    fun `does not mask a plain word followed by another word`() {
        // Разделитель строго :/= — «password for prod» не должно маскировать «for».
        val out = SecretRedactor.redact("the password for prod is set")
        assertEquals("the password for prod is set", out)
    }

    @Test
    fun `leaves ordinary text untouched`() {
        val text = "find files larger than 100MB in /var/log"
        assertEquals(text, SecretRedactor.redact(text))
    }
}
