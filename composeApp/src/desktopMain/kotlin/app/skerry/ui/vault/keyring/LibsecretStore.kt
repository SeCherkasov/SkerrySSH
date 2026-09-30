package app.skerry.ui.vault.keyring

import app.skerry.shared.vault.DeviceSecretStore
import app.skerry.shared.vault.DeviceSecretStoreException
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.util.Base64

/**
 * Linux: the Secret Service (GNOME Keyring, KWallet's provider, KeePassXC) through libsecret, in the
 * default collection — the login keyring the session unlocks at sign-in. The secret goes in base64
 * (libsecret's text API) through native memory that is wiped after, never through a Java String.
 */
internal class LibsecretStore : DeviceSecretStore {

    private val natives by lazy { Natives() }

    // Past the probe, a missing library is the keyring being absent, not a crash in a click handler.
    private val native: Natives
        get() = if (available.get()) natives else throw DeviceSecretStoreException("no Secret Service")

    private val available = ProbeOnce {
        // A lookup that matches nothing: it needs a reachable service and prompts for nothing.
        natives.withAttributes(PROBE_NAME) { schema, attributes ->
            val error = PointerByReference()
            val found = libsecret.secret_password_lookupv_sync(schema, attributes, null, error)
            found?.let(libsecret::secret_password_free)
            error.value.also { if (it != null) glib.g_error_free(it) } == null
        }
    }

    override fun isAvailable(): Boolean = available.get()

    override fun read(name: String): ByteArray? = native.withAttributes(name) { schema, attributes ->
        val error = PointerByReference()
        val found = libsecret.secret_password_lookupv_sync(schema, attributes, null, error)
        throwOn(error, "lookup")
        found ?: return@withAttributes null
        try {
            val encoded = found.getByteArray(0, found.indexOf(0, 0).toInt())
            try {
                Base64.getDecoder().decode(encoded)
            } catch (e: IllegalArgumentException) {
                throw DeviceSecretStoreException("keyring entry is not Skerry's", e)
            } finally {
                encoded.fill(0)
            }
        } finally {
            libsecret.secret_password_free(found) // wipes before freeing
        }
    }

    override fun write(name: String, secret: ByteArray) {
        val encoded = Base64.getEncoder().encode(secret)
        val text = Memory(encoded.size + 1L)
        try {
            text.write(0, encoded, 0, encoded.size)
            text.setByte(encoded.size.toLong(), 0)
            native.withAttributes(name) { schema, attributes ->
                val error = PointerByReference()
                libsecret.secret_password_storev_sync(schema, attributes, null, LABEL, text, null, error)
                throwOn(error, "store")
            }
        } finally {
            encoded.fill(0)
            text.clear()
            text.close()
        }
    }

    override fun delete(name: String) = native.withAttributes(name) { schema, attributes ->
        val error = PointerByReference()
        libsecret.secret_password_clearv_sync(schema, attributes, null, error)
        throwOn(error, "clear")
    }

    /** Builds the schema and the `{application, alias}` attribute table, and frees both after [block]. */
    private inline fun <T> Natives.withAttributes(name: String, block: Natives.(schema: Pointer, attributes: Pointer) -> T): T {
        val entry = requireEntryName(name)
        val types = glib.g_hash_table_new_full(strHash, strEqual, free, null)
        val attributes = glib.g_hash_table_new_full(strHash, strEqual, free, free)
        var schema: Pointer? = null
        try {
            // SECRET_SCHEMA_ATTRIBUTE_STRING is 0: the value pointer is the type, cast.
            glib.g_hash_table_insert(types, glib.g_strdup(ATTR_APPLICATION), null)
            glib.g_hash_table_insert(types, glib.g_strdup(ATTR_ALIAS), null)
            schema = libsecret.secret_schema_newv(SCHEMA, 0, types)
            glib.g_hash_table_insert(attributes, glib.g_strdup(ATTR_APPLICATION), glib.g_strdup(APPLICATION))
            glib.g_hash_table_insert(attributes, glib.g_strdup(ATTR_ALIAS), glib.g_strdup(entry))
            return block(schema, attributes)
        } finally {
            schema?.let(libsecret::secret_schema_unref)
            glib.g_hash_table_unref(attributes)
            glib.g_hash_table_unref(types)
        }
    }

    private fun Natives.throwOn(error: PointerByReference, operation: String) {
        val gError = error.value ?: return
        // GError { GQuark domain; gint code; gchar *message; } — the message follows two 32-bit fields.
        val message = runCatching { gError.getPointer(8)?.getString(0) }.getOrNull()
        glib.g_error_free(gError)
        throw DeviceSecretStoreException("Secret Service $operation failed: ${message.orEmpty()}")
    }

    @Suppress("FunctionName")
    private interface Glib : Library {
        fun g_hash_table_new_full(hash: Pointer, equal: Pointer, keyDestroy: Pointer?, valueDestroy: Pointer?): Pointer
        fun g_hash_table_insert(table: Pointer, key: Pointer, value: Pointer?): Int
        fun g_hash_table_unref(table: Pointer)
        fun g_strdup(text: String): Pointer
        fun g_error_free(error: Pointer)
    }

    @Suppress("FunctionName", "LongParameterList")
    private interface Secret : Library {
        fun secret_schema_newv(name: String, flags: Int, attributeTypes: Pointer): Pointer
        fun secret_schema_unref(schema: Pointer)
        fun secret_password_storev_sync(
            schema: Pointer, attributes: Pointer, collection: String?, label: String, password: Pointer,
            cancellable: Pointer?, error: PointerByReference,
        ): Int
        fun secret_password_lookupv_sync(schema: Pointer, attributes: Pointer, cancellable: Pointer?, error: PointerByReference): Pointer?
        fun secret_password_clearv_sync(schema: Pointer, attributes: Pointer, cancellable: Pointer?, error: PointerByReference): Int
        fun secret_password_free(password: Pointer)
    }

    private class Natives {
        val glib: Glib = Native.load("glib-2.0", Glib::class.java)
        val libsecret: Secret = Native.load("secret-1", Secret::class.java)
        private val glibLibrary = NativeLibrary.getInstance("glib-2.0")
        val strHash: Pointer = glibLibrary.getFunction("g_str_hash")
        val strEqual: Pointer = glibLibrary.getFunction("g_str_equal")
        val free: Pointer = glibLibrary.getFunction("g_free")
    }

    private companion object {
        const val SCHEMA = "app.skerry.DeviceKey"
        const val ATTR_APPLICATION = "application"
        const val ATTR_ALIAS = "alias"
        const val APPLICATION = "app.skerry"
        const val LABEL = "Skerry vault key"
        const val PROBE_NAME = "skerry.keyring-probe"
    }
}
