package app.skerry.ui.design

import app.skerry.shared.ssh.HostKeyMismatch
import app.skerry.shared.ssh.KnownHost
import app.skerry.ui.known.KnownHostEntry
import app.skerry.ui.known.KnownHostStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** Чистая логика мобильного экрана Known hosts: проекция строки, иконка статуса, баннер смены ключа. */
class MobileKnownTest {

    private fun entry(
        keyType: String = "ssh-ed25519",
        fingerprint: String = "SHA256:8c3F1a2bQzABCDEFGHIJKLMNpK9R",
        status: KnownHostStatus = KnownHostStatus.Verified,
    ): KnownHostEntry = KnownHostEntry(
        KnownHost("prod-web-01", 22, keyType, fingerprint, "2026-01-12T09:00:00Z"),
        status,
    )

    private fun mismatch(host: String = "nas-truenas", keyType: String = "ssh-ed25519"): HostKeyMismatch =
        HostKeyMismatch(host, 22, keyType, "SHA256:old", "SHA256:new", "2026-06-22T08:00:00Z")

    // Подпись строки: тип + короткий отпечаток (verified) / тип + «changed» (сменился)

    @Test
    fun subtitle_shows_type_and_short_fingerprint_when_verified() {
        // ssh- отбрасывается, отпечаток укорачивается (как shortFingerprint): первые 10 … последние 4.
        assertEquals("ed25519 · 8c3F1a2bQz…pK9R", mobileKnownSubtitle(entry()))
        assertEquals("rsa · 5fG1hKp8sX…vB3n", mobileKnownSubtitle(entry(keyType = "ssh-rsa", fingerprint = "SHA256:5fG1hKp8sXYZ0123456789vB3n")))
    }

    @Test
    fun subtitle_says_changed_when_status_changed() {
        // У сменившегося ключа точного отпечатка не показываем — он под вопросом.
        assertEquals("ed25519 · changed", mobileKnownSubtitle(entry(status = KnownHostStatus.Changed)))
    }

    // Иконка статуса строки

    @Test
    fun status_icon_verified_or_error() {
        assertEquals("verified", mobileKnownStatusIcon(KnownHostStatus.Verified))
        assertEquals("error", mobileKnownStatusIcon(KnownHostStatus.Changed))
    }

    // Баннер смены ключа

    @Test
    fun banner_title_names_the_host() {
        assertEquals("Key changed: nas-truenas", mobileKnownBannerTitle(mismatch()))
    }

    @Test
    fun banner_body_names_key_type_uppercased_and_warns() {
        assertEquals(
            "The ED25519 fingerprint differs from the one recorded. Verify before reconnecting.",
            mobileKnownBannerBody(mismatch()),
        )
        assertEquals(
            "The RSA fingerprint differs from the one recorded. Verify before reconnecting.",
            mobileKnownBannerBody(mismatch(keyType = "ssh-rsa")),
        )
    }
}
