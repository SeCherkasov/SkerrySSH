package app.skerry.shared.securitykey

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/** The libfido2 C ABI. Supported desktop distributions are 64-bit (size_t = uint64). */
// JNA binds these exact C ABI symbols and argument layouts; Kotlin renaming/grouping breaks native calls.
@Suppress("FunctionNaming", "LongParameterList")
internal interface LibFido2 : Library {
    fun fido_init(flags: Int)
    fun fido_dev_info_new(count: Long): Pointer?
    fun fido_dev_info_free(info: PointerByReference, count: Long)
    fun fido_dev_info_manifest(info: Pointer, count: Long, found: Pointer): Int
    fun fido_dev_info_ptr(info: Pointer, index: Long): Pointer
    fun fido_dev_info_path(info: Pointer): String?
    fun fido_dev_new(): Pointer?
    fun fido_dev_free(dev: PointerByReference)
    fun fido_dev_open(dev: Pointer, path: String): Int
    fun fido_dev_close(dev: Pointer): Int
    fun fido_dev_cancel(dev: Pointer): Int
    fun fido_dev_set_timeout(dev: Pointer, milliseconds: Int): Int
    fun fido_dev_has_pin(dev: Pointer): Byte
    fun fido_cred_new(): Pointer?
    fun fido_cred_free(cred: PointerByReference)
    fun fido_cred_set_type(cred: Pointer, type: Int): Int
    fun fido_cred_set_clientdata_hash(cred: Pointer, hash: ByteArray, size: Long): Int
    fun fido_cred_set_rp(cred: Pointer, id: String, name: String): Int
    fun fido_cred_set_user(cred: Pointer, id: ByteArray, size: Long, name: String, display: String, icon: String?): Int
    fun fido_cred_set_rk(cred: Pointer, option: Int): Int
    fun fido_cred_set_uv(cred: Pointer, option: Int): Int
    fun fido_dev_make_cred(dev: Pointer, cred: Pointer, pin: Pointer?): Int
    fun fido_cred_pubkey_ptr(cred: Pointer): Pointer?
    fun fido_cred_pubkey_len(cred: Pointer): Long
    fun fido_cred_id_ptr(cred: Pointer): Pointer?
    fun fido_cred_id_len(cred: Pointer): Long
    fun fido_cred_x5c_len(cred: Pointer): Long
    fun fido_cred_verify(cred: Pointer): Int
    fun fido_cred_verify_self(cred: Pointer): Int
    fun fido_assert_new(): Pointer?
    fun fido_assert_free(assertion: PointerByReference)
    fun fido_assert_set_rp(assertion: Pointer, application: String): Int
    fun fido_assert_set_clientdata_hash(assertion: Pointer, hash: ByteArray, size: Long): Int
    fun fido_assert_allow_cred(assertion: Pointer, handle: ByteArray, size: Long): Int
    fun fido_assert_set_up(assertion: Pointer, option: Int): Int
    fun fido_assert_set_uv(assertion: Pointer, option: Int): Int
    fun fido_dev_get_assert(dev: Pointer, assertion: Pointer, pin: Pointer?): Int
    fun fido_assert_count(assertion: Pointer): Long
    fun fido_assert_sig_ptr(assertion: Pointer, index: Long): Pointer?
    fun fido_assert_sig_len(assertion: Pointer, index: Long): Long
    fun fido_assert_flags(assertion: Pointer, index: Long): Byte
    fun fido_assert_sigcount(assertion: Pointer, index: Long): Int
    companion object {
        fun load(): LibFido2 = try {
            check(Native.SIZE_T_SIZE == 8)
            val resources = System.getProperty("compose.application.resources.dir")
            val library = if (!resources.isNullOrBlank()) {
                val platform = when {
                    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows"
                    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "macos"
                    else -> "linux"
                }
                val arch = when (System.getProperty("os.arch")) {
                    "aarch64", "arm64" -> "aarch64"
                    "amd64", "x86_64" -> "x86_64"
                    else -> throw SecurityKeyException(SecurityKeyFailure.Unavailable)
                }
                val filename = when (platform) { "windows" -> "fido2.dll"; "macos" -> "libfido2.dylib"; else -> "libfido2.so" }
                val root = java.nio.file.Path.of(resources).toAbsolutePath().normalize()
                val path = root.resolve("fido2/$platform-$arch/$filename").normalize()
                if (!java.nio.file.Files.isRegularFile(path)) throw SecurityKeyException(SecurityKeyFailure.Unavailable)
                path.toString()
            } else "fido2" // Development builds use the system package; packaged apps use only their bundled library.
            Native.load(library, LibFido2::class.java).also { it.fido_init(0) }
        } catch (_: LinkageError) { throw SecurityKeyException(SecurityKeyFailure.Unavailable) }
    }
}
