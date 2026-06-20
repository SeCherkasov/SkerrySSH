package app.skerry.ui.sftp

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Android-реализация выбора файла через Storage Access Framework. SAF отдаёт `content://` Uri, а sshj
 * умеет работать только с путём файловой системы, поэтому передача идёт через временный файл в
 * приватном кэше (staging), а копирование staging↔Uri инкапсулировано в handle:
 * - download: создаём документ (`CreateDocument`), sshj пишет в staging, [DownloadTarget.finalize]
 *   копирует staging→Uri; при ошибке [DownloadTarget.discard] чистит staging и пустой документ;
 * - upload: открываем документ (`OpenDocument`), сразу копируем Uri→staging (Uri может стать
 *   недоступен позже), sshj читает staging, [UploadSource.cleanup] удаляет его.
 *
 * Запуск самих SAF-диалогов делегирован [SafBridge], который Activity заполняет в `onCreate`
 * (`ActivityResultLauncher` требует Activity-контекста и регистрации до STARTED).
 */
actual suspend fun pickDownloadTarget(suggestedName: String): DownloadTarget? {
    val ctx = SafBridge.context() ?: return null
    val uri = SafBridge.createDocument(suggestedName) ?: return null
    val staging = File(stagingDir(ctx), "dl-${stagingStamp()}.tmp")
    return SafDownloadTarget(ctx, uri, suggestedName, staging)
}

actual suspend fun pickUploadSource(): UploadSource? {
    val ctx = SafBridge.context() ?: return null
    val uri = SafBridge.openDocument() ?: return null
    val name = queryDisplayName(ctx, uri) ?: "upload.bin"
    val staging = File(stagingDir(ctx), "ul-${stagingStamp()}.tmp")
    // Копируем содержимое выбранного документа в staging сразу: разрешение на Uri живёт недолго, а
    // передача может начаться позже. Ловим широкий [Exception] (а не только IOException) — у SAF Uri
    // помимо IO бывают SecurityException/IllegalState (отозванный доступ); трактуем как «выбор не
    // удался» (null) и удаляем staging. [CancellationException] пробрасываем (с очисткой), чтобы не
    // глушить отмену scope.
    return try {
        withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("Не удалось открыть выбранный файл")
        }
        SafUploadSource(name, staging)
    } catch (e: CancellationException) {
        staging.delete()
        throw e
    } catch (e: Exception) {
        staging.delete()
        null
    }
}

/** Цель скачивания поверх SAF-документа: sshj пишет в [staging], [finalize] копирует его в [uri]. */
private class SafDownloadTarget(
    private val ctx: Context,
    private val uri: Uri,
    override val displayName: String,
    private val staging: File,
) : DownloadTarget {
    override val stagingPath: String = staging.absolutePath

    override suspend fun finalize(): Unit = withContext(Dispatchers.IO) {
        // staging удаляем в finally: даже при сбое openOutputStream/copyTo временный файл не осиротеет
        // (пустой/частичный документ в SAF добьёт discard, который контроллер зовёт при ошибке finalize).
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { output ->
                staging.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("Не удалось открыть цель для записи")
        } finally {
            staging.delete()
        }
    }

    override suspend fun discard(): Unit = withContext(Dispatchers.IO) {
        staging.delete()
        // CreateDocument уже создал пустой документ в выбранном месте — удалим, чтобы не плодить мусор.
        runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
        Unit
    }
}

/** Источник загрузки: содержимое Uri уже скопировано в [staging], sshj читает оттуда. */
private class SafUploadSource(
    override val name: String,
    private val staging: File,
) : UploadSource {
    override val stagingPath: String = staging.absolutePath

    override suspend fun cleanup(): Unit = withContext(Dispatchers.IO) {
        staging.delete()
        Unit
    }
}

/** Приватный каталог под временные файлы передач (вне видимости пользователя). */
private fun stagingDir(ctx: Context): File = File(ctx.cacheDir, "sftp").apply { mkdirs() }

/** Уникальный суффикс имени staging-файла — UUID без риска коллизий при параллельных передачах. */
private fun stagingStamp(): String = UUID.randomUUID().toString()

/**
 * Человекочитаемое имя выбранного документа из `OpenableColumns.DISPLAY_NAME`. Любой сбой запроса
 * (SecurityException на отозванном Uri, кривой провайдер) гасим в null — имя не критично, выше есть
 * запасное «upload.bin».
 */
private fun queryDisplayName(ctx: Context, uri: Uri): String? = runCatching {
    ctx.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
}.getOrNull()

/**
 * Мост между top-level suspend-пикерами и Activity-уровневым SAF. Activity регистрирует
 * `ActivityResultLauncher` для Create/Open Document в `onCreate` и передаёт сюда лямбды запуска через
 * [install]; пикеры дёргают их и ждут результат через [CompletableDeferred].
 *
 * Потокобезопасность: выборы сериализованы [lock] (SAF-диалог модален, параллельно держать два смысла
 * нет), поэтому хватает одного [pending]. Поля `@Volatile` — пишутся из корутины пикера, читаются из
 * `ActivityResultCallback` (Main) и из `onCreate`. При пересоздании Activity [install] завершает
 * «зависший» pending значением null, иначе ожидающая его корутина повисла бы навсегда. Держит только
 * `applicationContext` (без утечки Activity). Лямбды запуска должны вызываться на главном потоке —
 * вызывающий код (Compose `rememberCoroutineScope`) уже работает на Main.
 */
object SafBridge {
    private val lock = Mutex()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var launchCreate: ((String) -> Unit)? = null

    @Volatile
    private var launchOpen: (() -> Unit)? = null

    @Volatile
    private var pending: CompletableDeferred<Uri?>? = null

    /** Вызывается из `MainActivity.onCreate`: [launchCreate] получает имя файла, [launchOpen] — без аргументов. */
    fun install(context: Context, launchCreate: (String) -> Unit, launchOpen: () -> Unit) {
        // Освободить выбор, начатый прошлой (уничтоженной) Activity — иначе его await зависнет навсегда.
        pending?.complete(null)
        pending = null
        appContext = context.applicationContext
        this.launchCreate = launchCreate
        this.launchOpen = launchOpen
    }

    fun context(): Context? = appContext

    /** Запустить CreateDocument с [suggestedName] и дождаться выбранного Uri (или null при отмене). */
    suspend fun createDocument(suggestedName: String): Uri? = lock.withLock {
        val launch = launchCreate ?: return null
        val deferred = CompletableDeferred<Uri?>()
        pending = deferred
        launch(suggestedName)
        deferred.await()
    }

    /** Запустить OpenDocument и дождаться выбранного Uri (или null при отмене). */
    suspend fun openDocument(): Uri? = lock.withLock {
        val launch = launchOpen ?: return null
        val deferred = CompletableDeferred<Uri?>()
        pending = deferred
        launch()
        deferred.await()
    }

    /** Колбэк результата CreateDocument (вызывается Activity на главном потоке). */
    fun onCreateResult(uri: Uri?) = completePending(uri)

    /** Колбэк результата OpenDocument (вызывается Activity на главном потоке). */
    fun onOpenResult(uri: Uri?) = completePending(uri)

    private fun completePending(uri: Uri?) {
        pending?.complete(uri)
        pending = null
    }
}
