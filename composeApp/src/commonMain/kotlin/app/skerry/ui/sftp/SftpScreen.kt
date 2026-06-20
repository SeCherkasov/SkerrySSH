package app.skerry.ui.sftp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.sftp.SftpEntry
import app.skerry.shared.sftp.SftpEntryType
import app.skerry.ui.desktop.ChromeIconButton
import app.skerry.ui.desktop.SkerryIcon
import app.skerry.ui.desktop.SkerryIconKind
import app.skerry.ui.theme.SkerryColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Этап открытия SFTP-канала: пока открываем, готово или не удалось. */
private sealed interface SftpOpen {
    data object Opening : SftpOpen
    data class Ready(val client: SftpClient) : SftpOpen
    data class Failed(val message: String) : SftpOpen
}

/**
 * SFTP-панель активной сессии: открывает отдельный канал через [openSftp], держит поверх него
 * [SftpController] и рендерит [SftpScreen]. Канал — собственность панели: открывается при входе в
 * композицию и закрывается в [DisposableEffect] при уходе (переключение на терминал/закрытие сессии).
 *
 * [scope] — долгоживущий scope экрана (а не [androidx.compose.runtime.rememberCoroutineScope]):
 * операции контроллера и фоновое закрытие канала ([NonCancellable]) должны переживать уход панели
 * из композиции, иначе закрытие не успело бы выполниться. Само SSH-соединение остаётся за
 * [app.skerry.ui.connection.ConnectionController].
 */
@Composable
fun RemoteSftpPane(
    openSftp: suspend () -> SftpClient,
    scope: CoroutineScope,
    mono: FontFamily,
    modifier: Modifier = Modifier,
) {
    val opened by produceState<SftpOpen>(SftpOpen.Opening, openSftp) {
        value = try {
            SftpOpen.Ready(openSftp())
        } catch (e: CancellationException) {
            throw e // тихая отмена (ушли с панели до открытия канала) — не показываем как ошибку
        } catch (e: Exception) {
            SftpOpen.Failed(e.message ?: "Не удалось открыть SFTP")
        }
    }

    when (val state = opened) {
        SftpOpen.Opening -> PaneCentered(modifier) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text("Открываем SFTP…", color = SkerryColors.textDim, fontSize = 13.sp)
        }

        is SftpOpen.Failed -> PaneCentered(modifier) {
            Text("SFTP недоступен", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
            Text(state.message, color = SkerryColors.textDim, fontSize = 12.sp)
        }

        is SftpOpen.Ready -> {
            val client = state.client
            // Дочерний scope панели: операции контроллера живут на нём, в onDispose мы его
            // отменяем — висящие list/мутации не упадут на уже закрытом канале. Закрытие самого
            // канала идёт следом на [scope] под NonCancellable (его отмена нас бы прервала).
            val paneScope = remember(client) {
                CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
            }
            val controller = remember(client) { SftpController(client, paneScope) }
            LaunchedEffect(controller) { controller.start() }
            DisposableEffect(client) {
                onDispose {
                    paneScope.cancel()
                    scope.launch(NonCancellable) { runCatching { client.close() } }
                }
            }
            SftpScreen(controller, mono, modifier)
        }
    }
}

/**
 * Рендер одной [SftpController]: тулбар (вверх/обновить/загрузить/новый каталог) с текущим путём и
 * список записей (каталоги первыми). Клик по каталогу — вход внутрь; меню записи —
 * скачать (для файлов)/переименовать/удалить. Передача файлов идёт потоком с прогресс-баннером;
 * локальный путь выбирается нативным диалогом ([pickUploadSource]/[pickDownloadTarget]).
 */
@Composable
fun SftpScreen(
    controller: SftpController,
    mono: FontFamily,
    modifier: Modifier = Modifier,
) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SftpEntry?>(null) }
    var deleting by remember { mutableStateOf<SftpEntry?>(null) }
    // UI-scope для модальных диалогов выбора файла: их отмена при уходе с панели безопасна
    // (сама передача живёт на scope контроллера и переживёт закрытие диалога).
    val uiScope = rememberCoroutineScope()

    Column(modifier.fillMaxSize().background(SkerryColors.nightSea)) {
        SftpToolbar(
            path = controller.path,
            mono = mono,
            onUp = controller::goUp,
            onRefresh = controller::refresh,
            onNewFolder = { creating = true },
            onUpload = {
                uiScope.launch {
                    // Имя файла и staging-путь инкапсулированы в источнике: desktop отдаёт реальный
                    // путь, Android — копию выбранного Uri во временном файле.
                    pickUploadSource()?.let { source -> controller.upload(source) }
                }
            },
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(SkerryColors.line))
        TransferBanner(controller.transfer, mono, onDismiss = controller::clearTransfer)

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (val state = controller.state) {
                SftpPaneState.Loading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)

                is SftpPaneState.Error -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text("Ошибка SFTP", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                    Text(state.message, color = SkerryColors.textDim, fontSize = 12.sp)
                    TextButton(onClick = controller::refresh) { Text("Повторить") }
                }

                is SftpPaneState.Loaded ->
                    if (state.entries.isEmpty()) {
                        Text("Каталог пуст", color = SkerryColors.textFaint, fontSize = 13.sp)
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(state.entries, key = { it.path }) { entry ->
                                SftpRow(
                                    entry = entry,
                                    mono = mono,
                                    onOpen = { controller.open(entry) },
                                    onDownload = {
                                        uiScope.launch {
                                            pickDownloadTarget(entry.name)?.let { target ->
                                                controller.download(entry, target)
                                            }
                                        }
                                    },
                                    onRename = { renaming = entry },
                                    onDelete = { deleting = entry },
                                )
                            }
                        }
                    }
            }
        }
    }

    if (creating) {
        SftpNameDialog(
            title = "Новый каталог",
            confirmLabel = "Создать",
            initial = "",
            onConfirm = { name -> controller.mkdir(name); creating = false },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { entry ->
        SftpNameDialog(
            title = "Переименовать",
            confirmLabel = "Переименовать",
            initial = entry.name,
            onConfirm = { name -> controller.rename(entry, name); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { entry ->
        val isDir = entry.type == SftpEntryType.Directory
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (isDir) "Удалить каталог?" else "Удалить файл?") },
            text = { Text("«${entry.name}» будет удалён без возможности восстановления.") },
            confirmButton = {
                TextButton(onClick = { controller.delete(entry); deleting = null }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun SftpToolbar(
    path: String,
    mono: FontFamily,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onNewFolder: () -> Unit,
    onUpload: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SkerryColors.nightSeaSoft)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Вверх: chevron, повёрнутый «домиком» — родительский каталог.
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onUp),
            contentAlignment = Alignment.Center,
        ) {
            SkerryIcon(SkerryIconKind.Chevron, tint = SkerryColors.textDim, size = 18.dp, modifier = Modifier.rotate(180f))
        }
        Text(
            path,
            color = SkerryColors.text,
            fontFamily = mono,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
        )
        ChromeIconButton(SkerryIconKind.Upload, onClick = onUpload)
        ChromeIconButton(SkerryIconKind.Refresh, onClick = onRefresh)
        ChromeIconButton(SkerryIconKind.Add, onClick = onNewFolder, tint = SkerryColors.cyan)
    }
}

/**
 * Полоса состояния передачи под тулбаром: прогресс при [SftpTransferState.Active] (детерминированный
 * бар, если известен размер, иначе бесконечный) и ошибка при [SftpTransferState.Failed] с кнопкой
 * закрытия. При [SftpTransferState.Idle] не рисует ничего.
 */
@Composable
private fun TransferBanner(
    transfer: SftpTransferState,
    mono: FontFamily,
    onDismiss: () -> Unit,
) {
    when (transfer) {
        SftpTransferState.Idle -> Unit

        is SftpTransferState.Active -> Column(
            Modifier.fillMaxWidth().background(SkerryColors.nightSeaSoft).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val verb = if (transfer.direction == TransferDirection.Download) "Скачивание" else "Загрузка"
            val tail = if (transfer.total > 0) {
                " — ${formatSize(transfer.transferred)} / ${formatSize(transfer.total)}"
            } else {
                ""
            }
            Text(
                "$verb «${transfer.name}»$tail",
                color = SkerryColors.text,
                fontFamily = mono,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (transfer.total > 0) {
                LinearProgressIndicator(
                    progress = { (transfer.transferred.toFloat() / transfer.total).coerceIn(0f, 1f) },
                    color = SkerryColors.cyan,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(color = SkerryColors.cyan, modifier = Modifier.fillMaxWidth())
            }
        }

        is SftpTransferState.Failed -> Row(
            Modifier.fillMaxWidth().background(SkerryColors.nightSeaSoft).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Не удалось передать «${transfer.name}»: ${transfer.message}",
                color = MaterialTheme.colorScheme.error,
                fontSize = 11.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ChromeIconButton(SkerryIconKind.Close, onClick = onDismiss)
        }
    }
}

@Composable
private fun SftpRow(
    entry: SftpEntry,
    mono: FontFamily,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val isDir = entry.type == SftpEntryType.Directory
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SkerryIcon(
            if (isDir) SkerryIconKind.Folder else SkerryIconKind.File,
            tint = if (isDir) SkerryColors.cyan else SkerryColors.textDim,
            size = 16.dp,
        )
        Text(
            entry.name,
            color = SkerryColors.text,
            fontFamily = mono,
            fontSize = 12.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!isDir) {
            Text(formatSize(entry.size), color = SkerryColors.textFaint, fontSize = 11.sp)
        }
        // Скачивание только для файлов: каталоги рекурсивно качать пока не умеем.
        SftpRowMenu(onDownload = onDownload.takeUnless { isDir }, onRename = onRename, onDelete = onDelete)
    }
}

@Composable
private fun SftpRowMenu(onDownload: (() -> Unit)?, onRename: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ChromeIconButton(SkerryIconKind.MoreVert, onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onDownload != null) {
                DropdownMenuItem(
                    text = { Text("Скачать") },
                    onClick = { expanded = false; onDownload() },
                )
            }
            DropdownMenuItem(
                text = { Text("Переименовать") },
                onClick = { expanded = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                onClick = { expanded = false; onDelete() },
            )
        }
    }
}

@Composable
private fun SftpNameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() && "/" !in trimmed && trimmed != "." && trimmed != ".."
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Имя") },
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(trimmed) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun PaneCentered(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().background(SkerryColors.nightSea), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

/** Человекочитаемый размер файла без зависимостей от платформенного форматирования. */
private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val units = listOf("КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val rounded = (value * 10).toLong() / 10.0
    return "$rounded ${units[unit]}"
}
