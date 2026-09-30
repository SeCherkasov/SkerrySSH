package app.skerry.ui.vault.keyring

import app.skerry.shared.vault.DeviceSecretStore
import app.skerry.shared.vault.DeviceSecretStoreException
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * macOS: a generic password in the user's login keychain, which the session unlocks at sign-in. The
 * keychain ties the item to the app that wrote it, so a rebuilt, unsigned app may be asked once
 * whether it may read the item.
 */
internal class MacKeychainStore : DeviceSecretStore {

    private val natives by lazy { Natives() }

    private val native: Natives
        get() = if (available.get()) natives else throw DeviceSecretStoreException("no keychain")

    private val available = ProbeOnce {
        // Looking up an item that doesn't exist needs a default keychain and prompts for nothing.
        val status = natives.security.SecKeychainFindGenericPassword(
            null, SERVICE.size, SERVICE, PROBE.size, PROBE, null, null, null,
        )
        status == ERR_ITEM_NOT_FOUND || status == NO_ERR
    }

    override fun isAvailable(): Boolean = available.get()

    override fun read(name: String): ByteArray? {
        val account = account(name)
        val length = IntByReference()
        val data = PointerByReference()
        val status = native.security.SecKeychainFindGenericPassword(
            null, SERVICE.size, SERVICE, account.size, account, length, data, null,
        )
        if (status == ERR_ITEM_NOT_FOUND) return null
        check(status, "find")
        val secret = data.value
        return try {
            secret.getByteArray(0, length.value)
        } finally {
            secret.clear(length.value.toLong())
            native.security.SecKeychainItemFreeContent(null, secret)
        }
    }

    override fun write(name: String, secret: ByteArray) {
        val account = account(name)
        val data = Memory(secret.size.toLong().coerceAtLeast(1))
        try {
            data.write(0, secret, 0, secret.size)
            val item = PointerByReference()
            val found = native.security.SecKeychainFindGenericPassword(
                null, SERVICE.size, SERVICE, account.size, account, null, null, item,
            )
            if (found == NO_ERR) {
                try {
                    check(native.security.SecKeychainItemModifyAttributesAndData(item.value, null, secret.size, data), "update")
                } finally {
                    native.core.CFRelease(item.value)
                }
            } else {
                if (found != ERR_ITEM_NOT_FOUND) check(found, "find")
                check(
                    native.security.SecKeychainAddGenericPassword(
                        null, SERVICE.size, SERVICE, account.size, account, secret.size, data, null,
                    ),
                    "add",
                )
            }
        } finally {
            data.clear()
            data.close()
        }
    }

    override fun delete(name: String) {
        val account = account(name)
        val item = PointerByReference()
        val found = native.security.SecKeychainFindGenericPassword(
            null, SERVICE.size, SERVICE, account.size, account, null, null, item,
        )
        if (found == ERR_ITEM_NOT_FOUND) return
        check(found, "find")
        try {
            check(native.security.SecKeychainItemDelete(item.value), "delete")
        } finally {
            native.core.CFRelease(item.value)
        }
    }

    private fun account(name: String): ByteArray = requireEntryName(name).encodeToByteArray()

    private fun check(status: Int, operation: String) {
        if (status != NO_ERR) throw DeviceSecretStoreException("keychain $operation failed: OSStatus $status")
    }

    // The SecKeychain* calls are deprecated in favour of SecItem*, whose queries are CFDictionaries —
    // far more JNA for the same generic-password item. They still work on every supported macOS.
    @Suppress("FunctionName", "LongParameterList")
    private interface Security : Library {
        fun SecKeychainFindGenericPassword(
            keychainOrArray: Pointer?, serviceNameLength: Int, serviceName: ByteArray, accountNameLength: Int,
            accountName: ByteArray, passwordLength: IntByReference?, passwordData: PointerByReference?,
            itemRef: PointerByReference?,
        ): Int
        fun SecKeychainAddGenericPassword(
            keychain: Pointer?, serviceNameLength: Int, serviceName: ByteArray, accountNameLength: Int,
            accountName: ByteArray, passwordLength: Int, passwordData: Pointer, itemRef: PointerByReference?,
        ): Int
        fun SecKeychainItemModifyAttributesAndData(itemRef: Pointer, attrList: Pointer?, length: Int, data: Pointer): Int
        fun SecKeychainItemFreeContent(attrList: Pointer?, data: Pointer?): Int
        fun SecKeychainItemDelete(itemRef: Pointer): Int
    }

    @Suppress("FunctionName")
    private interface CoreFoundation : Library {
        fun CFRelease(ref: Pointer)
    }

    private class Natives {
        val security: Security = Native.load("Security", Security::class.java)
        val core: CoreFoundation = Native.load("CoreFoundation", CoreFoundation::class.java)
    }

    private companion object {
        val SERVICE = "app.skerry".encodeToByteArray()
        val PROBE = "skerry.keyring-probe".encodeToByteArray()
        const val NO_ERR = 0
        const val ERR_ITEM_NOT_FOUND = -25300
    }
}
