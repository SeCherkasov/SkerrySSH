package app.skerry.ui.vault.keyring

import app.skerry.shared.vault.DeviceSecretStore
import app.skerry.shared.vault.DeviceSecretStoreException
import java.nio.file.Path

/**
 * The OS keyring behind the trusted-device unlock of issue #398: Secret Service on Linux, DPAPI on
 * Windows, the login keychain on macOS. `null` on any other OS — the feature then does not exist here.
 * [dir] is Skerry's config directory; only DPAPI keeps its sealed blob there.
 */
internal fun desktopDeviceSecretStore(dir: Path): DeviceSecretStore? {
    val os = System.getProperty("os.name")?.lowercase().orEmpty()
    val store = when {
        os.contains("linux") -> LibsecretStore()
        os.contains("windows") -> DpapiSecretStore(dir.resolve("device-keys"))
        os.contains("mac") -> MacKeychainStore()
        else -> null
    }
    return store?.let(::LinkageGuard)
}

/**
 * A JNA binding fails with [LinkageError] — an `Error`, which no `catch (Exception)` above sees — when
 * a symbol is missing from the library the OS actually ships. That is a keyring that failed.
 */
internal class LinkageGuard(private val store: DeviceSecretStore) : DeviceSecretStore {
    override fun isAvailable(): Boolean = linked { store.isAvailable() }

    override fun read(name: String): ByteArray? = linked { store.read(name) }

    override fun write(name: String, secret: ByteArray) = linked { store.write(name, secret) }

    override fun delete(name: String) = linked { store.delete(name) }

    private inline fun <T> linked(block: () -> T): T = try {
        block()
    } catch (e: LinkageError) {
        throw DeviceSecretStoreException("keyring library mismatch", e)
    }
}

/**
 * Entry names reach a file name (DPAPI) and keyring attributes; they are Skerry's own aliases, so
 * anything else is a programming error rather than input to escape.
 */
internal fun requireEntryName(name: String): String {
    require(ENTRY_NAME.matches(name)) { "unexpected keyring entry name" }
    return name
}

private val ENTRY_NAME = Regex("[A-Za-z0-9._-]{1,128}")

/**
 * Availability is a native probe (a D-Bus round trip on Linux) and the UI asks for it in composition,
 * so it is taken once per process: a keyring that appears later is picked up on the next start.
 */
internal class ProbeOnce(private val probe: () -> Boolean) {
    private val result by lazy {
        try {
            probe()
        } catch (_: Throwable) {
            // UnsatisfiedLinkError (no library), NoClassDefFoundError — same as having no keyring.
            false
        }
    }

    fun get(): Boolean = result
}
