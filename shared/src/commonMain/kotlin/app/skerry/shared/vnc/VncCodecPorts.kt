package app.skerry.shared.vnc

/**
 * Platform capabilities the pure `commonMain` [RfbCodec] needs but can't implement itself
 * (zlib, DES). They are injected as small interfaces — the same testability pattern the project
 * already uses (e.g. `ConnectionController` injecting `newSessionScope`) — so the codec stays 100%
 * in `commonMain` and tests supply fakes, while the real implementations live in `jvmSharedMain`
 * (`JvmInflater`, `VncDesCipher`).
 */

/**
 * One persistent zlib inflate stream. RFB reuses a stream's sliding window/dictionary across
 * messages, so ONE [Inflater] instance corresponds to ONE logical zlib stream for the whole
 * connection: ZRLE uses a single stream; Tight uses up to four independent ones. Feed it the
 * compressed bytes of a message and it returns all output currently available.
 */
interface Inflater {
    fun inflate(input: ByteArray): ByteArray
    fun close()
}

/** Creates fresh persistent [Inflater] streams (one per ZRLE connection / per Tight zlib slot). */
fun interface InflaterFactory {
    fun create(): Inflater
}

/**
 * Answers the VNC Authentication challenge: DES-encrypt the 16-byte [challenge] with a key derived
 * from [password] (truncated/zero-padded to 8 bytes, each byte bit-reversed — the load-bearing VNC
 * quirk). Implemented in `jvmSharedMain` via JCE (`VncDesCipher`); a fake is used in codec tests.
 */
fun interface VncChallengeResponder {
    fun respond(password: String, challenge: ByteArray): ByteArray
}

/**
 * Decodes a JPEG rectangle (Tight JPEG compression) to ARGB. Platform-backed (ImageIO on desktop,
 * BitmapFactory on Android); injected so the codec stays platform-neutral. Only Tight needs it, so
 * it's nullable in the codec until the Tight slice wires it in.
 */
fun interface VncImageDecoder {
    /** Decode [jpeg] and return ([argb], width, height); `argb[y*width + x]`. */
    fun decodeToArgb(jpeg: ByteArray): DecodedImage
}

/** A decoded raster image: ARGB pixels plus dimensions. */
data class DecodedImage(val argb: IntArray, val width: Int, val height: Int) {
    override fun equals(other: Any?): Boolean =
        other is DecodedImage && width == other.width && height == other.height && argb.contentEquals(other.argb)

    override fun hashCode(): Int = (width * 31 + height) * 31 + argb.contentHashCode()
}

/** Base type for RFB errors surfaced by the codec/transport. */
sealed class VncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The handshake was structurally wrong (bad version banner, unknown message type, malformed rect). */
class VncProtocolException(message: String, cause: Throwable? = null) : VncException(message, cause)

/** Authentication failed: wrong password, or the server offered no security type we support. */
class VncAuthException(message: String, cause: Throwable? = null) : VncException(message, cause)
