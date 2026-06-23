package app.skerry.ui.vault

import android.provider.DocumentsContract
import app.skerry.ui.sftp.SafBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android-экспорт публичного ключа/сертификата через Storage Access Framework. Переиспользует
 * [SafBridge] (тот же мост, что и SFTP-скачивание): `CreateDocument` отдаёт `content://` Uri в
 * выбранном пользователем месте, после чего пишем туда [content] в UTF-8. Экспортируется только
 * публичный материал (открытый ключ/cert) — приватные ключи наружу не выгружаются.
 *
 * Возвращает `false`, если пользователь отменил выбор (Uri == null) или запись не удалась — UI
 * трактует это как «экспорт не состоялся» без ложного успеха. Любой сбой IO/доступа к Uri гасим в
 * `false` (а не пробрасываем), чтобы кнопка экспорта не валила экран. При сбое записи удаляем уже
 * созданный `CreateDocument`'ом (пустой/частичный) документ, чтобы не оставлять мусор у пользователя
 * (как [app.skerry.ui.sftp] `SafDownloadTarget.discard`).
 */
actual suspend fun exportTextFile(suggestedName: String, content: String): Boolean {
    val ctx = SafBridge.context() ?: return false
    val uri = SafBridge.createTextDocument(suggestedName) ?: return false
    return withContext(Dispatchers.IO) {
        runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                ?: error("no output stream for $uri")
            true
        }.getOrElse {
            runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
            false
        }
    }
}
