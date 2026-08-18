package app.skerry.ui.tunnel

import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ports_autostart_failed
import app.skerry.ui.generated.resources.ports_count_active
import app.skerry.ui.generated.resources.ports_count_stopped
import app.skerry.ui.generated.resources.ports_tunnel_count
import app.skerry.ui.i18n.withLocale
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getPluralString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Plural agreement of the tunnel counts. Lives in the desktop source set, not `commonTest`: it
 * drives `java.util.Locale`, which is a platform API, and it mutates a process-global.
 *
 * The autostart card's tunnel count. Pinned because the obvious implementation — `if (count == 1)`
 * — is right in English and wrong in Russian, where 2–4 take their own form ("3 туннеля") and only
 * 5 and up take the one that looks like a plural ("5 туннелей").
 */
class TunnelCountPluralTest {

    @Test
    fun `russian picks a different form for one, a few and many`() = runTest {
        withLocale("ru") {
            assertEquals("1 туннель", getPluralString(Res.plurals.ports_tunnel_count, 1, 1))
            assertEquals("3 туннеля", getPluralString(Res.plurals.ports_tunnel_count, 3, 3))
            assertEquals("7 туннелей", getPluralString(Res.plurals.ports_tunnel_count, 7, 7))
            // 21 is "one" in Russian, not "many" — the rule is about the last digit, not the size.
            assertEquals("21 туннель", getPluralString(Res.plurals.ports_tunnel_count, 21, 21))
        }
    }

    @Test
    fun `the header tally agrees with its own numbers in russian`() = runTest {
        // Regression: the tally was one flat template, so a single active tunnel read
        // "1 активных". English never showed it — the adjective there doesn't inflect.
        withLocale("ru") {
            assertEquals("1 активный", getPluralString(Res.plurals.ports_count_active, 1, 1))
            assertEquals("3 активных", getPluralString(Res.plurals.ports_count_active, 3, 3))
            assertEquals("21 активный", getPluralString(Res.plurals.ports_count_active, 21, 21))
            assertEquals("0 остановленных", getPluralString(Res.plurals.ports_count_stopped, 0, 0))
            assertEquals("1 остановленный", getPluralString(Res.plurals.ports_count_stopped, 1, 1))
        }
    }

    @Test
    fun `the autostart failure banner agrees with its own count in russian`() = runTest {
        withLocale("ru") {
            assertEquals("1 туннель автостарта не поднялся", getPluralString(Res.plurals.ports_autostart_failed, 1, 1))
            assertEquals("2 туннеля автостарта не поднялись", getPluralString(Res.plurals.ports_autostart_failed, 2, 2))
            assertEquals("5 туннелей автостарта не поднялись", getPluralString(Res.plurals.ports_autostart_failed, 5, 5))
        }
    }

    @Test
    fun `english keeps the singular for one and the plural for everything else`() = runTest {
        withLocale("en") {
            assertEquals("1 tunnel", getPluralString(Res.plurals.ports_tunnel_count, 1, 1))
            assertEquals("3 tunnels", getPluralString(Res.plurals.ports_tunnel_count, 3, 3))
        }
    }
}
