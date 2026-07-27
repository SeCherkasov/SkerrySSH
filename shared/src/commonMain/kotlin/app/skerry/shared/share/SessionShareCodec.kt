package app.skerry.shared.share

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.VaultCrypto

/**
 * Seals and opens the frames of one shared session (share id [shareId]).
 *
 * The relay only forwards bytes: it never holds the key, so terminal output, keystrokes and screen
 * geometry are end-to-end encrypted between the host and the viewers under the team key. Each frame
 * carries its own random nonce, so the relay may buffer and replay frames to a late viewer without
 * the key ever being reused across a fixed nonce.
 *
 * Every failure mode of untrusted input — a frame from another share, from the other direction,
 * under a superseded team key, truncated, or simply garbage — comes back as `null`. Callers drop
 * the frame and keep reading: one bad frame must not end a live session.
 */
class SessionShareCodec(
    private val crypto: VaultCrypto,
    private val shareId: String,
) {
    private val hostAad = shareAad(shareId, ShareDirection.HOST_TO_GUEST)
    private val guestAad = shareAad(shareId, ShareDirection.GUEST_TO_HOST)

    fun seal(key: DataKey, frame: ShareFrame, direction: ShareDirection): ByteArray =
        crypto.seal(key, encodeShareFrame(frame), aad(direction))

    /** Opens a frame received from the relay, or `null` — see the class doc. */
    fun open(key: DataKey, blob: ByteArray, direction: ShareDirection): ShareFrame? {
        // The AEAD throws on structurally impossible input (no room for nonce+tag); the relay is
        // untrusted, so the size is checked here rather than letting that reach the caller.
        if (!isPlausibleSealedFrame(blob)) return null
        val plaintext = crypto.open(key, blob, aad(direction)) ?: return null
        return decodeShareFrame(plaintext)
    }

    private fun aad(direction: ShareDirection): ByteArray =
        if (direction == ShareDirection.HOST_TO_GUEST) hostAad else guestAad
}
