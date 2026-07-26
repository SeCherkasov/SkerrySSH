package app.skerry.server.cli

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Formatting helpers: boundaries, and independence from the JVM's locale and timezone. */
class CliOutputTest {

    private val originalLocale: Locale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() = Locale.setDefault(originalLocale)

    @Test
    fun `bytes are formatted in binary units`() {
        assertEquals("0 B", humanBytes(0))
        assertEquals("1023 B", humanBytes(1023))
        assertEquals("1.0 KiB", humanBytes(1024))
        assertEquals("1.5 KiB", humanBytes(1536))
        assertEquals("100 KiB", humanBytes(102_400))
        assertEquals("1.0 MiB", humanBytes(1024L * 1024))
        assertEquals("1.0 TiB", humanBytes(1024L * 1024 * 1024 * 1024))
        // Beyond the last unit it keeps scaling in TiB rather than inventing one.
        assertEquals("1024 TiB", humanBytes(1024L * 1024 * 1024 * 1024 * 1024))
    }

    /** A comma decimal separator would break both greppability and comparison against `du`. */
    @Test
    fun `byte formatting ignores the jvm locale`() {
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))
        assertEquals("1.5 KiB", humanBytes(1536))
    }

    @Test
    fun `relative time covers its boundaries`() {
        val now = 1_800_000_000_000L
        assertEquals("—", relativeTime(null, now))
        assertEquals("just now", relativeTime(now, now))
        assertEquals("just now", relativeTime(now - 59_000, now))
        assertEquals("1m ago", relativeTime(now - 60_000, now))
        assertEquals("59m ago", relativeTime(now - 59 * 60_000, now))
        assertEquals("1h ago", relativeTime(now - 60 * 60_000, now))
        assertEquals("47h ago", relativeTime(now - 47 * 60 * 60_000L, now))
        assertEquals("2d ago", relativeTime(now - 48 * 60 * 60_000L, now))
        // Clock skew between the server and the CLI host must not print a negative age.
        assertEquals("in the future", relativeTime(now + 600_000, now))
    }

    @Test
    fun `timestamps are printed in utc regardless of the default timezone`() {
        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals("2027-01-15 08:00 UTC", utcMinutes(1_800_000_000_000L))
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `table pads columns and never leaves trailing spaces`() {
        val rendered = table(listOf("ID", "NAME"), listOf(listOf("a", "short"), listOf("longer-id", "x")))
        assertEquals(
            listOf(
                "ID         NAME",
                "a          short",
                "longer-id  x",
            ),
            rendered.lines(),
        )
    }

    @Test
    fun `table tolerates missing cells and no rows`() {
        assertEquals("ID  NAME", table(listOf("ID", "NAME"), emptyList()))
        assertEquals(listOf("ID  NAME", "a"), table(listOf("ID", "NAME"), listOf(listOf("a"))).lines())
    }

    @Test
    fun `key values align on the widest key`() {
        assertEquals(
            listOf("Accounts  1", "Storage   0 B"),
            keyValues(listOf("Accounts" to "1", "Storage" to "0 B")).lines(),
        )
    }
}
