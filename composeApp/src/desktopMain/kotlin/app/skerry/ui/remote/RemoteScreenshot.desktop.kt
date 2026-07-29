package app.skerry.ui.remote

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException

/**
 * Writes the frame into the user's pictures folder under `Skerry/`, falling back to the home
 * directory on a system that has no such folder (a headless Linux box has none).
 */
actual suspend fun saveRemoteScreenshot(image: ImageBitmap, baseName: String): String? = withContext(Dispatchers.IO) {
    val png = try {
        Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    } ?: return@withContext null

    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val file = pictureDir().resolve(screenshotFileName(baseName, stamp))
    try {
        Files.createDirectories(file.parent)
        Files.write(file, png)
        file.toString()
    } catch (e: CancellationException) {
        // A write cut short leaves however many bytes reached the disk; a half-written PNG under a
        // name that says "screenshot" is worse than no file at all.
        deleteQuietly(file)
        throw e
    } catch (_: Exception) {
        deleteQuietly(file)
        null
    }
}

private fun deleteQuietly(file: Path) {
    runCatching { Files.deleteIfExists(file) }
}

private fun pictureDir(): Path {
    val home = Path.of(System.getProperty("user.home").orEmpty().ifEmpty { "." })
    val pictures = home.resolve("Pictures")
    return if (Files.isDirectory(pictures)) pictures.resolve("Skerry") else home.resolve("Skerry")
}
