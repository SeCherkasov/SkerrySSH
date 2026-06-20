package app.skerry.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.ui.desktop.ChromeIconButton
import app.skerry.ui.desktop.SkerryIcon
import app.skerry.ui.desktop.SkerryIconKind
import app.skerry.ui.sftp.TransferDirection
import app.skerry.ui.theme.SkerryColors

/**
 * Двухпанельный SFTP (Total Commander): слева локальная ФС, справа удалённый хост — две [FilePane]
 * поверх [TransferCoordinator.local]/[TransferCoordinator.remote], между ними кнопки направления
 * передачи, снизу — полоса прогресса. Клик по каталогу входит внутрь, по файлу — добавляет/убирает
 * из выделения; «Загрузить» шлёт выделение локальной панели в каталог удалённой, «Скачать» — наоборот.
 */
@Composable
fun DualPaneSftpScreen(
    coordinator: TransferCoordinator,
    mono: FontFamily,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(SkerryColors.nightSea)) {
        DualTransferBar(
            uploadEnabled = coordinator.local.selection.isNotEmpty(),
            downloadEnabled = coordinator.remote.selection.isNotEmpty(),
            onUpload = coordinator::uploadSelection,
            onDownload = coordinator::downloadSelection,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(SkerryColors.line))

        Row(Modifier.weight(1f).fillMaxWidth()) {
            FilePane(coordinator.local, mono, Modifier.weight(1f).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(SkerryColors.line))
            FilePane(coordinator.remote, mono, Modifier.weight(1f).fillMaxHeight())
        }

        TransferStrip(coordinator.transfer, mono, onDismiss = coordinator::clearTransfer)
    }
}

/** Верхняя полоса с кнопками направления передачи между панелями. */
@Composable
private fun DualTransferBar(
    uploadEnabled: Boolean,
    downloadEnabled: Boolean,
    onUpload: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(SkerryColors.nightSeaSoft).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DirectionButton(SkerryIconKind.Upload, "Загрузить →", uploadEnabled, onUpload)
        DirectionButton(SkerryIconKind.Download, "← Скачать", downloadEnabled, onDownload)
    }
}

@Composable
private fun DirectionButton(icon: SkerryIconKind, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) SkerryColors.cyan else SkerryColors.textFaint
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SkerryIcon(icon, tint = tint, size = 15.dp)
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** Одна панель: заголовок (метка источника + путь), тулбар, колонки и список с выделением. */
@Composable
private fun FilePane(controller: FilePaneController, mono: FontFamily, modifier: Modifier = Modifier) {
    var creating by remember { mutableStateOf(false) }

    Column(modifier.background(SkerryColors.nightSea)) {
        Row(
            Modifier.fillMaxWidth().background(SkerryColors.nightSeaSoft).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SkerryIcon(SkerryIconKind.Folder, tint = SkerryColors.cyan, size = 15.dp)
            Text(controller.label, color = SkerryColors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(
                controller.path,
                color = SkerryColors.textDim,
                fontFamily = mono,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = controller::goUp),
                contentAlignment = Alignment.Center,
            ) {
                SkerryIcon(SkerryIconKind.Chevron, tint = SkerryColors.textDim, size = 16.dp, modifier = Modifier.rotate(180f))
            }
            ChromeIconButton(SkerryIconKind.Refresh, onClick = controller::refresh)
            ChromeIconButton(SkerryIconKind.Add, onClick = { creating = true }, tint = SkerryColors.cyan)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(SkerryColors.line))

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (val state = controller.state) {
                FilePaneState.Loading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)

                is FilePaneState.Error -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(20.dp),
                ) {
                    Text("Ошибка", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                    Text(state.message, color = SkerryColors.textDim, fontSize = 12.sp)
                    TextButton(onClick = controller::refresh) { Text("Повторить") }
                }

                is FilePaneState.Loaded -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.entries, key = { it.path }) { entry ->
                        FilePaneRow(
                            entry = entry,
                            mono = mono,
                            selected = entry.path in controller.selection,
                            onClick = {
                                if (entry.type == FileItemType.Directory) controller.open(entry)
                                else controller.toggle(entry)
                            },
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "Новый каталог",
            onConfirm = { name -> controller.mkdir(name); creating = false },
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun FilePaneRow(entry: FileItem, mono: FontFamily, selected: Boolean, onClick: () -> Unit) {
    val isDir = entry.type == FileItemType.Directory
    val rowBg = if (selected) SkerryColors.cyan.copy(alpha = 0.18f) else SkerryColors.nightSea
    Row(
        Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SkerryIcon(
            if (isDir) SkerryIconKind.Folder else SkerryIconKind.File,
            tint = if (isDir) SkerryColors.cyan else SkerryColors.textDim,
            size = 15.dp,
        )
        Text(
            entry.name,
            color = if (selected) SkerryColors.cyan else SkerryColors.text,
            fontFamily = mono,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!isDir) Text(formatSize(entry.size), color = SkerryColors.textFaint, fontSize = 11.sp)
    }
}

/** Нижняя полоса прогресса передачи: счётчик файлов, бар и ошибка с кнопкой закрытия. */
@Composable
private fun TransferStrip(transfer: TransferState, mono: FontFamily, onDismiss: () -> Unit) {
    when (transfer) {
        TransferState.Idle -> Unit

        is TransferState.Active -> Column(
            Modifier.fillMaxWidth().background(SkerryColors.nightSeaSoft).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val verb = if (transfer.direction == TransferDirection.Download) "Скачивание" else "Загрузка"
            val counter = if (transfer.fileCount > 1) " (${transfer.fileIndex}/${transfer.fileCount})" else ""
            val tail = if (transfer.total > 0) " — ${formatSize(transfer.transferred)} / ${formatSize(transfer.total)}" else ""
            Text(
                "$verb «${transfer.name}»$counter$tail",
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

        is TransferState.Failed -> Row(
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
private fun NameDialog(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() && "/" !in trimmed && trimmed != "." && trimmed != ".."
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Имя") })
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(trimmed) }) { Text("Создать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
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
