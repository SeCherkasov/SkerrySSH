package app.skerry.ui.sync

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Quick-pairing payload (variant B): a signed-in device shows it
 * as a QR/code, a new device reads it via camera or manual entry. Carries exactly what the new
 * device needs to claim the session and decrypt the account key bypassing the server:
 *
 *  - [serverUrl] — where to send the claim (the new device doesn't yet know the server address);
 *  - [code] — one-time claim code for the pairing session, checked by the server;
 *  - [transferKey] — one-time key that seals the dataKey on the server ([VaultCrypto.newTransferKey]);
 *    it travels in the QR, not to the server, which stores only a useless envelope without it.
 *
 * Format: `"sk1"` plus three fields, each base64url-no-padding, joined by `.` — the dot isn't in
 * the base64url alphabet (`A-Za-z0-9-_`), so `split('.')` is unambiguous even when
 * [serverUrl]/[code] contain `:`/`/`/`-`/`_`. The `sk1` version prefix rejects garbage/unrelated
 * QR codes. Compact format (QR density matters) with no kotlinx.serialization dependency.
 */
class PairingPayload(
    val serverUrl: String,
    val code: String,
    val transferKey: ByteArray,
) {
    @OptIn(ExperimentalEncodingApi::class)
    fun encode(): String {
        val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        return listOf(
            PREFIX,
            b64.encode(serverUrl.encodeToByteArray()),
            b64.encode(code.encodeToByteArray()),
            b64.encode(transferKey),
        ).joinToString(SEP)
    }

    // Plain class, not data: a data class's auto-generated copy() would share the mutable
    // transferKey by reference, so wiping one copy would silently zero the other. equals/hashCode
    // are structural and hand-written for round-trip tests.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingPayload) return false
        return serverUrl == other.serverUrl && code == other.code && transferKey.contentEquals(other.transferKey)
    }

    override fun hashCode(): Int =
        (serverUrl.hashCode() * 31 + code.hashCode()) * 31 + transferKey.contentHashCode()

    override fun toString(): String = "PairingPayload(serverUrl=$serverUrl, code=***, transferKey=***)"

    companion object {
        private const val PREFIX = "sk1"
        private const val SEP = "."

        /** Length of transferKey (AEAD key size for XChaCha20); checked in decode. */
        private const val TRANSFER_KEY_SIZE = 32

        /**
         * True when [raw] decodes to a pairing payload whose [serverUrl] is plain `http://`. Drives the
         * insecure-transport warning on the pairing-join screen (the server address is baked into the QR,
         * so unlike the sync form the user never types it and can't see the scheme otherwise). Returns
         * false for anything that doesn't decode (the "malformed code" path handles that separately).
         */
        fun isInsecureServerUrl(raw: String): Boolean =
            decode(raw)?.serverUrl?.startsWith("http://") == true

        /**
         * Parses a string from a QR/manual entry. Returns null for anything not ours or
         * malformed (unrelated QR, truncation, wrong prefix, non-base64); the caller shows
         * "doesn't look like a pairing code" instead of crashing. Surrounding whitespace is
         * trimmed (manual paste/camera newline).
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun decode(raw: String): PairingPayload? {
            val parts = raw.trim().split(SEP)
            if (parts.size != 4 || parts[0] != PREFIX) return null
            val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            return runCatching {
                val serverUrl = b64.decode(parts[1]).decodeToString()
                val code = b64.decode(parts[2]).decodeToString()
                val transferKey = b64.decode(parts[3])
                // Structural validation happens before any network call: otherwise claimPairing
                // would burn a one-time code on a truncated QR. A wrong key length or invalid URL
                // scheme (a tampered QR) is a decode failure, not a burned code.
                if (transferKey.size != TRANSFER_KEY_SIZE) return@runCatching null
                if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) return@runCatching null
                PairingPayload(serverUrl, code, transferKey)
            }.getOrNull()
        }
    }
}
