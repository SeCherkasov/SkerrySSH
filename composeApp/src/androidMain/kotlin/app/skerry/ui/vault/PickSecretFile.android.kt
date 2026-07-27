package app.skerry.ui.vault

import android.content.Intent
import app.skerry.ui.sftp.SafBridge

/**
 * Android location picker: Storage Access Framework via [SafBridge], with the read grant *persisted*
 * ([android.content.ContentResolver.takePersistableUriPermission]). Without that the Uri would only
 * be readable until the process dies, and a credential meant to be re-read on every connection for
 * months would start failing after a restart.
 *
 * A provider that refuses to persist the grant (SecurityException) still yields the Uri: the
 * credential works for this session, and the connection reports a denied read plainly if the grant
 * lapses later — better than dropping a pick the user just made.
 */
actual suspend fun pickSecretFileRef(title: String): String? {
    // title is unused: the Storage Access Framework picker has no custom-title hook.
    val uri = SafBridge.openDocument() ?: return null
    SafBridge.context()?.let { ctx ->
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    return uri.toString()
}
