package app.skerry.ui.known

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.HostKeyMismatch
import app.skerry.shared.ssh.HostKeyMismatchStore
import app.skerry.shared.ssh.KnownHost
import app.skerry.shared.ssh.KnownHostsStore

/** Статус доверенного ключа в таблице known-hosts. */
enum class KnownHostStatus { Verified, Changed }

/** Строка таблицы known-hosts: доверенный ключ + статус (Changed, если по нему есть незакрытая смена ключа). */
@Immutable
data class KnownHostEntry(
    val host: KnownHost,
    val status: KnownHostStatus,
)

/**
 * Состояние менеджера known-hosts поверх [KnownHostsStore] (доверенные ключи) и
 * [HostKeyMismatchStore] (незакрытые события смены ключа). Держит обе проекции как Compose-state и
 * сводит мутации к сторам, перечитывая [entries]/[mismatches] после каждой — тем же приёмом, что
 * [app.skerry.ui.host.HostManagerController].
 *
 * Источник истины — сторы: их же пишет [app.skerry.shared.ssh.TofuHostKeyVerifier] из потока sshj при
 * подключении. Контроллер перечитывает их на refresh, поэтому смена ключа, замеченная во время сессии,
 * проявится при следующем построении view.
 *
 * Мутации синхронны (пишут в файловые сторы прямо из UI-обработчиков) — как [HostManagerController]:
 * они редки (принять/отклонить/забыть ключ), поэтому контроллер не держит корутинную scope.
 *
 * [now] штампует [KnownHost.firstSeen] при принятии нового ключа (ISO-8601); по умолчанию пусто.
 */
@Stable
class KnownHostsController(
    private val store: KnownHostsStore,
    private val mismatchStore: HostKeyMismatchStore,
    private val now: () -> String = { "" },
) {
    var entries by mutableStateOf(emptyList<KnownHostEntry>())
        private set
    var mismatches by mutableStateOf(emptyList<HostKeyMismatch>())
        private set

    init {
        refresh()
    }

    /**
     * Принять новый ключ: заменить доверенный отпечаток на предъявленный (с новой отметкой времени)
     * и снять событие. После этого хост снова Verified — с актуальным ключом.
     */
    fun acceptNewKey(mismatch: HostKeyMismatch) {
        // Атомарная замена (не remove+add): иначе между ними поток sshj мог бы увидеть отсутствие
        // записи и пере-TOFU'ить произвольный предъявленный ключ как новый доверенный.
        store.replace(KnownHost(mismatch.host, mismatch.port, mismatch.keyType, mismatch.offeredFingerprint, now()))
        mismatchStore.clear(mismatch.host, mismatch.port, mismatch.keyType)
        refresh()
    }

    /**
     * Отклонить новый ключ (Reject & block / Dismiss): снять событие, оставив доверенным прежний ключ.
     * Предъявленный ключ так и не доверен — будущие подключения с ним продолжат отклоняться.
     */
    fun reject(mismatch: HostKeyMismatch) {
        mismatchStore.clear(mismatch.host, mismatch.port, mismatch.keyType)
        refresh()
    }

    /** Забыть доверенный ключ целиком (и связанное событие смены, если есть). */
    fun forget(entry: KnownHostEntry) {
        val host = entry.host
        store.remove(host.host, host.port, host.keyType)
        mismatchStore.clear(host.host, host.port, host.keyType)
        refresh()
    }

    private fun refresh() {
        val pending = mismatchStore.all()
        mismatches = pending
        entries = store.all().map { host ->
            val changed = pending.any {
                it.host == host.host && it.port == host.port && it.keyType == host.keyType
            }
            KnownHostEntry(host, if (changed) KnownHostStatus.Changed else KnownHostStatus.Verified)
        }
    }
}

/**
 * Короткий вид отпечатка для таблицы: без префикса `SHA256:`, первые 10 символов … последние 4
 * (как в макете `8c3F1a2bQz…pK9R`). Короткие значения отдаются как есть.
 */
fun shortFingerprint(fingerprint: String): String {
    val body = fingerprint.removePrefix("SHA256:")
    return if (body.length <= 16) body else body.take(10) + "…" + body.takeLast(4)
}
