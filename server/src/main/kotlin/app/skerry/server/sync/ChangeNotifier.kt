package app.skerry.server.sync

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * In-memory шина «в аккаунте появились изменения» для WS-push (`docs/skerry-sync-design.md` §3:
 * push без содержимого). Несёт лишь accountId и новый курсор — никаких данных. Модель одиночного
 * self-hosted инстанса; горизонтальное масштабирование потребовало бы внешнего брокера.
 */
class ChangeNotifier {
    /**
     * [channel]: `acc:{accountId}` — курсор аккаунтного vault; `team:{teamId}` — курсор записей
     * команды; `member:{accountId}` — изменился состав/приглашения (курсор не несёт смысла, 0).
     */
    data class Change(val channel: String, val cursor: Long)

    data class TeamChange(val teamId: String, val cursor: Long)

    // replay=0 + DROP_OLDEST: уведомление о курсоре идемпотентно, поэтому при медленном
    // подписчике сбрасываем старые события вместо блокировки издателя (publish зовётся прямо
    // в обработчике PUT /vault/records — он не должен ждать WS-клиента; kotlin-ревью HIGH-2).
    private val flow = MutableSharedFlow<Change>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Не-suspend: tryEmit никогда не подвешивает издателя (буфер с DROP_OLDEST). */
    fun publish(accountId: String, cursor: Long) {
        flow.tryEmit(Change("acc:$accountId", cursor))
    }

    /** Сигнал «в команде появились записи до cursor» — для WS-сессий активных участников. */
    fun publishTeam(teamId: String, cursor: Long) {
        flow.tryEmit(Change("team:$teamId", cursor))
    }

    /** Сигнал «состав команд/приглашения аккаунта изменились» — клиент перечитывает список команд. */
    fun publishMembership(accountId: String) {
        flow.tryEmit(Change("member:$accountId", 0))
    }

    /** Поток курсоров для конкретного аккаунта (для одной WS-сессии). */
    fun forAccount(accountId: String): Flow<Long> =
        flow.filter { it.channel == "acc:$accountId" }.map { it.cursor }

    /** Все командные сигналы; фильтрация по членству — на стороне WS-сессии (состав меняется). */
    fun teamChanges(): Flow<TeamChange> =
        flow.filter { it.channel.startsWith("team:") }
            .map { TeamChange(it.channel.removePrefix("team:"), it.cursor) }

    /** Сигналы об изменении членства для конкретного аккаунта. */
    fun forMembership(accountId: String): Flow<Unit> =
        flow.filter { it.channel == "member:$accountId" }.map { }

    /**
     * Число активных подписчиков шины (все аккаунты). Наблюдаемость для тестов WS-сессий:
     * закрытие сокета клиентом должно освобождать подписку, а не держать collect до следующего publish.
     */
    val subscriptions: StateFlow<Int> get() = flow.subscriptionCount
}
