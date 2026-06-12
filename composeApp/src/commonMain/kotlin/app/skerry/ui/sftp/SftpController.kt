package app.skerry.ui.sftp

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.sftp.SftpEntry
import app.skerry.shared.sftp.SftpEntryType
import app.skerry.shared.sftp.SftpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Состояние одной SFTP-панели (удалённый каталог). */
sealed interface SftpPaneState {
    /** Идёт загрузка листинга. */
    data object Loading : SftpPaneState

    /** Листинг загружен; [entries] уже отсортированы (каталоги первыми, затем по имени). */
    data class Loaded(val entries: List<SftpEntry>) : SftpPaneState

    /** Последняя операция/загрузка не удалась; [message] для показа пользователю. */
    data class Error(val message: String) : SftpPaneState
}

/**
 * Контроллер одной SFTP-панели поверх [SftpClient]: навигация по каталогам и операции над ними
 * (создать/удалить/переименовать). Операции [SftpClient] — `suspend`, поэтому, в отличие от
 * синхронных vault/host-контроллеров, панель держит [scope] и гоняет работу через [launch].
 *
 * Действия сериализуются флагом [busy]: пока идёт операция, новые игнорируются — иначе параллельные
 * list/мутации гонялись бы за один [state]/[path]. Ошибки операций переводят панель в
 * [SftpPaneState.Error], не роняя контроллер. Владение каналом — снаружи (экран открывает его через
 * `SshConnection.openSftp()` и закрывает в dispose); контроллер канал не закрывает.
 */
@Stable
class SftpController(
    private val sftp: SftpClient,
    private val scope: CoroutineScope,
) {
    var path: String by mutableStateOf("/")
        private set

    var state: SftpPaneState by mutableStateOf(SftpPaneState.Loading)
        private set

    private var busy = false

    /** Загрузить стартовый каталог сессии (`realpath(".")`). Вызывать один раз при открытии панели. */
    fun start() = op {
        path = sftp.realpath(".")
        reload()
    }

    /** Войти в каталог [entry]; для файла — no-op (download появится со стримингом). */
    fun open(entry: SftpEntry) {
        if (entry.type != SftpEntryType.Directory) return
        op {
            path = entry.path
            reload()
        }
    }

    /** Подняться в родительский каталог. В корне `/` остаётся на месте. */
    fun goUp() = op {
        path = sftp.realpath("$path/..")
        reload()
    }

    /** Перечитать текущий каталог. */
    fun refresh() = op { reload() }

    /** Создать подкаталог [name] в текущем каталоге. */
    fun mkdir(name: String) = op {
        sftp.mkdir(childPath(name))
        reload()
    }

    /** Удалить [entry] (файл — `remove`, каталог — `rmdir`). */
    fun delete(entry: SftpEntry) = op {
        if (entry.type == SftpEntryType.Directory) sftp.rmdir(entry.path) else sftp.remove(entry.path)
        reload()
    }

    /** Переименовать [entry] в [newName] (в пределах текущего каталога). */
    fun rename(entry: SftpEntry, newName: String) = op {
        sftp.rename(entry.path, childPath(newName))
        reload()
    }

    /** Перечитать [path] и положить отсортированный листинг в [state]; ошибку — в [SftpPaneState.Error]. */
    private suspend fun reload() {
        state = try {
            SftpPaneState.Loaded(sftp.list(path).sortedForPane())
        } catch (e: SftpException) {
            SftpPaneState.Error(e.message ?: "Ошибка SFTP")
        }
    }

    /**
     * Запустить операцию панели, сериализуя её флагом [busy]. Любая [SftpException] из самой
     * операции (mkdir/rename/…) переводит панель в [SftpPaneState.Error] — каждый путь к диску
     * под защитой, не только [reload].
     */
    private fun op(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: SftpException) {
                state = SftpPaneState.Error(e.message ?: "Ошибка SFTP")
            } finally {
                busy = false
            }
        }
    }

    /** Путь дочернего объекта [name] в текущем каталоге (без двойного `/` в корне). */
    private fun childPath(name: String): String = if (path == "/") "/$name" else "$path/$name"
}

/** Каталоги первыми, затем по имени без учёта регистра — привычный порядок файлового менеджера. */
private fun List<SftpEntry>.sortedForPane(): List<SftpEntry> =
    sortedWith(
        compareBy(
            { if (it.type == SftpEntryType.Directory) 0 else 1 },
            { it.name.lowercase() },
        ),
    )
