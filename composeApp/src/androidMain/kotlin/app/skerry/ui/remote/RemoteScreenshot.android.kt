package app.skerry.ui.remote

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import app.skerry.ui.sftp.SafBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Puts the frame in the gallery under `Pictures/Skerry`, through MediaStore on Android 10+ (no
 * storage permission needed, and the picture shows up in the gallery). On 8 and 9 MediaStore's
 * relative path does not exist yet and writing to shared storage needs a permission this app never
 * asks for, so the file goes to the app's own pictures directory instead.
 *
 * A write that fails — or is cancelled — takes its half-written file with it: a partial PNG under a
 * name that says "screenshot" is worse than no file at all.
 */
actual suspend fun saveRemoteScreenshot(image: ImageBitmap, baseName: String): String? = withContext(Dispatchers.IO) {
    val ctx = SafBridge.context() ?: return@withContext null
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val name = screenshotFileName(baseName, stamp)
    val bitmap = try {
        image.asAndroidBitmap()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return@withContext null
    }

    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Skerry")
        }
        val resolver = ctx.contentResolver
        val uri = try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        return@withContext try {
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            "${Environment.DIRECTORY_PICTURES}/Skerry/$name"
        } catch (e: CancellationException) {
            deleteQuietly(ctx.contentResolver, uri)
            throw e
        } catch (_: Exception) {
            deleteQuietly(ctx.contentResolver, uri)
            null
        }
    }

    val file = File(File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Skerry"), name)
    try {
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.absolutePath
    } catch (e: CancellationException) {
        runCatching { file.delete() }
        throw e
    } catch (_: Exception) {
        runCatching { file.delete() }
        null
    }
}

private fun deleteQuietly(resolver: android.content.ContentResolver, uri: Uri) {
    runCatching { resolver.delete(uri, null, null) }
}
