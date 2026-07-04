package app.skerry.ui.teams

import app.skerry.shared.sync.SyncEngine
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.sync.SyncSettings
import app.skerry.shared.sync.SyncSignal
import app.skerry.shared.sync.SyncStateStore
import app.skerry.shared.sync.SyncException
import app.skerry.shared.team.TeamClient
import app.skerry.shared.team.TeamInviteCodec
import app.skerry.shared.team.TeamKeyStore
import app.skerry.shared.team.TeamIdentityStore
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole
import app.skerry.shared.team.TeamScopedSyncClient
import app.skerry.shared.team.TeamVaults
import app.skerry.shared.team.sharingKeyFingerprint
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.skerry.shared.team.stripShareFields

/** Типизированная причина сбоя операции Teams (текст — в syncFailureText-стиле, слой UI). */
enum class TeamsFailure {
    NotConnected, VaultLocked, NoRecipientKey, AlreadyInvited, NoSuchAccount,
    KeyMissing, Network, Protocol, Forbidden,
}

/** Команда глазами UI: серверные метаданные + локальный ключ (имя живёт в своём vault / конверте). */
data class TeamUi(
    val id: String,
    val name: String,
    val ownerAccountId: String,
    val role: TeamRole,
    val status: TeamMemberStatus,
    val memberCount: Int,
    /** false у активной команды = ключ не доехал (или конверт не открылся) — записи недоступны. */
    val hasKey: Boolean,
)

/** Данные для подтверждения приглашения: фингерпринт ключа приглашаемого сверяют голосом/чатом. */
data class InvitePreview(val accountId: String, val fingerprint: String)

/**
 * Координатор Teams: связывает [TeamClient] (сеть), аккаунтный vault (ключи команд и identity),
 * per-team vault'ы и [SyncEngine] team-scope. Все операции — с [TeamsFailure] в [lastError] вместо
 * бросков (кроме CancellationException). Конвенции конкурентности — как в SyncCoordinator:
 * один [opMutex] на мутации, [syncMutex] на циклы синка.
 */
class TeamsCoordinator(
    private val session: () -> SyncSession?,
    private val client: () -> TeamClient?,
    private val vault: Vault,
    private val crypto: VaultCrypto,
    private val teamVaults: TeamVaults,
    private val teamState: SyncStateStore,
    private val newTeamId: () -> String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onTeamsChanged: () -> Unit = {},
) {

    private val keyStore = TeamKeyStore(vault)
    private val identityStore = TeamIdentityStore(vault, crypto)
    private val inviteCodec = TeamInviteCodec(crypto)

    private val opMutex = Mutex()
    private val syncMutex = Mutex()

    private val _teams = MutableStateFlow<List<TeamUi>>(emptyList())
    val teams: StateFlow<List<TeamUi>> = _teams

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _lastError = MutableStateFlow<TeamsFailure?>(null)
    val lastError: StateFlow<TeamsFailure?> = _lastError

    fun clearError() {
        _lastError.value = null
    }

    /**
     * Просьба к АККАУНТНОМУ синку сделать восстановительный полный re-pull
     * ([SyncCoordinator.recoverFullPull]): активная команда без ключа означает, что запись TEAM
     * потеряна для дельта-синка (старый клиент без Teams пропустил незнакомый тип, продвинув курсор,
     * — повторно она уже не приедет). Поздняя привязка, как teamsForSync: sync создаётся раньше teams.
     */
    var onKeyMissing: (() -> Unit)? = null

    // Ключ восстанавливаем ОДИН раз на команду за процесс: если его нет и на сервере, каждый refresh
    // иначе гонял бы полный re-pull впустую.
    private val recoveryRequested = mutableSetOf<String>()

    /** Подключить к WS-сигналам SyncCoordinator (`sync.onTeamSignal = teams::onSignal`). */
    fun onSignal(signal: SyncSignal) {
        when (signal) {
            is SyncSignal.Team -> scope.launch {
                // Курсор-guard, как в аккаунтном watch: своё же эхо не гоняет лишний цикл.
                if (signal.cursor > teamState.cursor(cursorKey(signal.teamId))) syncTeam(signal.teamId)
            }
            SyncSignal.Membership -> scope.launch { refresh() }
            is SyncSignal.Account -> Unit // аккаунтный канал обрабатывает SyncCoordinator
        }
    }

    /**
     * Дёрнуть после цикла АККАУНТНОГО синка ([SyncCoordinator.onSynced]): записи TEAM могли только
     * что доехать в личный vault (команда создана/принята на другом устройстве этого аккаунта).
     * Без этого «ключ команды ещё не доехал» висит до перезахода на экран, даже когда ключ уже
     * лежит в vault'е. No-op, пока UI не показывает команду без ключа, — не гоняем сеть на каждый
     * цикл live-синка.
     */
    fun onAccountSynced() {
        if (!vault.isUnlocked) return
        val keyless = _teams.value.filter { !it.hasKey }
        if (keyless.isEmpty()) return
        val keys = keyStore.list()
        if (keyless.none { keys.containsKey(it.id) }) return
        scope.launch {
            refresh()
            syncAll() // у свежеоткрытых команд надо сразу подтянуть общие записи
        }
    }

    /** Фингерпринт СВОЕЙ публичной половины — показывается в UI для сверки при приглашении. */
    fun ownFingerprint(): String? {
        if (!vault.isUnlocked) return null
        return runCatching { sharingKeyFingerprint(identityStore.ensure().publicKey) }.getOrNull()
    }

    /** Перечитать команды с сервера, открыть vault'ы активных, опубликовать identity при первом входе. */
    suspend fun refresh() {
        val s = session() ?: return markError(TeamsFailure.NotConnected)
        val c = client() ?: return markError(TeamsFailure.NotConnected)
        if (!vault.isUnlocked) return markError(TeamsFailure.VaultLocked)
        op {
            // Identity публикуем идемпотентно: без неё нас нельзя пригласить в команду.
            c.publishKey(s, identityStore.ensure().publicKey)
            val remote = c.listTeams(s)
            val keys = keyStore.list()
            _teams.value = remote.map { t ->
                val entry = keys[t.id]
                val name = entry?.name ?: t.envelope?.let { env ->
                    identityStore.load()?.let { id -> inviteCodec.open(id, env)?.teamName }
                } ?: t.id
                TeamUi(t.id, name, t.ownerAccountId, t.role, t.status, t.memberCount, entry != null)
            }
            // Ключи команд, из которых нас удалили (или команда удалена), больше не нужны.
            val liveIds = remote.map { it.id }.toSet()
            keys.keys.filter { it !in liveIds }.forEach { gone ->
                keyStore.remove(gone)
                teamVaults.reset(gone)
            }
            onTeamsChanged()
            maybeRecoverKeys()
        }
    }

    suspend fun members(teamId: String): List<TeamMember> {
        val s = session() ?: return emptyList()
        val c = client() ?: return emptyList()
        return try {
            c.members(s, teamId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markError(e.toFailure())
            emptyList()
        }
    }

    /** Создать команду: id клиентский, teamKey локальный; сервер узнаёт только id. */
    suspend fun createTeam(name: String) {
        val s = session() ?: return markError(TeamsFailure.NotConnected)
        val c = client() ?: return markError(TeamsFailure.NotConnected)
        if (!vault.isUnlocked) return markError(TeamsFailure.VaultLocked)
        op {
            c.publishKey(s, identityStore.ensure().publicKey)
            val teamId = newTeamId()
            c.createTeam(s, teamId)
            keyStore.put(teamId, name.ifBlank { teamId }, TeamRole.OWNER, crypto.newDataKey())
            refreshUnlocked(s, c)
        }
    }

    /** Шаг 1 приглашения: ключ приглашаемого + фингерпринт для сверки по доверенному каналу. */
    suspend fun previewInvite(accountId: String): InvitePreview? {
        val s = session() ?: run { markError(TeamsFailure.NotConnected); return null }
        val c = client() ?: run { markError(TeamsFailure.NotConnected); return null }
        return try {
            val key = c.fetchPublicKey(s, accountId)
            if (key == null) {
                markError(TeamsFailure.NoRecipientKey)
                null
            } else {
                InvitePreview(accountId, sharingKeyFingerprint(key))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markError(e.toFailure())
            null
        }
    }

    /** Шаг 2: запечатать teamKey+имя на ключ приглашаемого и создать членство-приглашение. */
    suspend fun invite(teamId: String, accountId: String) {
        val s = session() ?: return markError(TeamsFailure.NotConnected)
        val c = client() ?: return markError(TeamsFailure.NotConnected)
        val entry = keyStore.get(teamId) ?: return markError(TeamsFailure.KeyMissing)
        val teamKey = entry.dataKey() ?: return markError(TeamsFailure.KeyMissing)
        op {
            val recipientKey = c.fetchPublicKey(s, accountId)
                ?: return@op markError(TeamsFailure.NoRecipientKey)
            c.invite(s, teamId, accountId, inviteCodec.seal(recipientKey, teamKey, entry.name))
            refreshUnlocked(s, c)
        }
    }

    /** Принять приглашение: открыть конверт своей identity, сохранить ключ, подтянуть записи. */
    suspend fun accept(teamId: String) {
        val s = session() ?: return markError(TeamsFailure.NotConnected)
        val c = client() ?: return markError(TeamsFailure.NotConnected)
        if (!vault.isUnlocked) return markError(TeamsFailure.VaultLocked)
        op {
            val summary = c.listTeams(s).firstOrNull { it.id == teamId }
                ?: return@op markError(TeamsFailure.Protocol)
            val envelope = summary.envelope ?: return@op markError(TeamsFailure.KeyMissing)
            val identity = identityStore.load() ?: return@op markError(TeamsFailure.KeyMissing)
            val invite = inviteCodec.open(identity, envelope)
                ?: return@op markError(TeamsFailure.KeyMissing)
            keyStore.put(teamId, invite.teamName, TeamRole.MEMBER, invite.teamKey)
            c.accept(s, teamId)
            refreshUnlocked(s, c)
        }
        syncTeam(teamId)
    }

    /** Отклонить приглашение = удалить своё членство (конверт на сервере пропадает вместе с ним). */
    suspend fun decline(teamId: String) = leave(teamId)

    suspend fun leave(teamId: String) {
        val self = session()?.accountId ?: return markError(TeamsFailure.NotConnected)
        removeMember(teamId, self)
    }

    suspend fun removeMember(teamId: String, accountId: String) {
        val sess = session() ?: return markError(TeamsFailure.NotConnected)
        val c = client() ?: return markError(TeamsFailure.NotConnected)
        op {
            c.removeMember(sess, teamId, accountId)
            if (accountId == sess.accountId) forgetTeamLocally(teamId)
            refreshUnlocked(sess, c)
        }
    }

    suspend fun deleteTeam(teamId: String) {
        val sess = session() ?: return markError(TeamsFailure.NotConnected)
        val c = client() ?: return markError(TeamsFailure.NotConnected)
        op {
            c.deleteTeam(sess, teamId)
            forgetTeamLocally(teamId)
            refreshUnlocked(sess, c)
        }
    }

    /** Vault команды (для сторов общих записей в UI); null — нет ключа/не активны/vault залочен. */
    fun teamVault(teamId: String): Vault? {
        if (!vault.isUnlocked) return null
        val key = keyStore.get(teamId)?.dataKey() ?: return null
        return teamVaults.open(teamId, key)
    }

    /**
     * Поделиться записью аккаунтного vault с командой: копия расшифрованного payload'а кладётся в
     * team-vault под тем же id. [stripFields] — поля, теряющие смысл вне личного workspace
     * (например `groupId` хоста). Возвращает false при недоступном vault/записи.
     */
    suspend fun shareRecord(
        teamId: String,
        recordId: String,
        type: RecordType,
        stripFields: Set<String> = emptySet(),
    ): Boolean {
        val target = teamVault(teamId) ?: run { markError(TeamsFailure.KeyMissing); return false }
        val payload = runCatching { vault.openPayload(recordId) }.getOrNull() ?: return false
        val cleaned = stripShareFields(payload, stripFields)
        target.put(recordId, type, cleaned)
        syncTeam(teamId)
        return true
    }

    /** Убрать запись из команды (tombstone доедет до всех участников). */
    suspend fun unshareRecord(teamId: String, recordId: String) {
        teamVault(teamId)?.remove(recordId) ?: return
        syncTeam(teamId)
    }

    /** Синкнуть одну команду (pull+push team-scope через общий SyncEngine). */
    suspend fun syncTeam(teamId: String) {
        val s = session() ?: return
        val c = client() ?: return
        val teamVault = teamVault(teamId) ?: return
        syncMutex.withLock {
            try {
                val engine = SyncEngine(
                    TeamScopedSyncClient(c, teamId),
                    teamVault,
                    KeyedStateStore(teamState, cursorKey(teamId)),
                    settings = { SyncSettings() },
                )
                engine.sync(s)
                onTeamsChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                markError(e.toFailure())
            }
        }
    }

    suspend fun syncAll() {
        _teams.value.filter { it.status == TeamMemberStatus.ACTIVE && it.hasKey }
            .forEach { syncTeam(it.id) }
    }

    /** Залочить team-vault'ы (зовётся при локе аккаунтного vault — ключи команд больше недоступны). */
    fun lock() {
        teamVaults.lockAll()
        _teams.value = emptyList()
    }

    // --- внутренности ---

    private fun forgetTeamLocally(teamId: String) {
        keyStore.remove(teamId)
        teamVaults.reset(teamId)
        teamState.setCursor(cursorKey(teamId), 0)
    }

    /** refresh() без повторного захвата [opMutex] — для вызова из op{}-блоков. */
    private suspend fun refreshUnlocked(s: SyncSession, c: TeamClient) {
        val remote = c.listTeams(s)
        val keys = keyStore.list()
        _teams.value = remote.map { t ->
            val entry = keys[t.id]
            val name = entry?.name ?: t.envelope?.let { env ->
                identityStore.load()?.let { id -> inviteCodec.open(id, env)?.teamName }
            } ?: t.id
            TeamUi(t.id, name, t.ownerAccountId, t.role, t.status, t.memberCount, entry != null)
        }
        onTeamsChanged()
        maybeRecoverKeys()
    }

    /**
     * Активная команда без ключа → одноразово (на команду за процесс) попросить аккаунтный синк о
     * полном re-pull: ключ мог быть потерян дельта-синком навсегда (см. [onKeyMissing]). После pull
     * [onAccountSynced] сам заметит доехавший ключ и перечитает команды.
     */
    private fun maybeRecoverKeys() {
        val lost = _teams.value.filter {
            it.status == TeamMemberStatus.ACTIVE && !it.hasKey && recoveryRequested.add(it.id)
        }
        if (lost.isNotEmpty()) onKeyMissing?.invoke()
    }

    private suspend fun op(block: suspend () -> Unit) {
        opMutex.withLock {
            _busy.value = true
            try {
                _lastError.value = null
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                markError(e.toFailure())
            } finally {
                _busy.value = false
            }
        }
    }

    private fun markError(reason: TeamsFailure) {
        _lastError.value = reason
    }

    private fun Exception.toFailure(): TeamsFailure = when ((this as? SyncException)?.kind) {
        SyncException.Kind.NETWORK -> TeamsFailure.Network
        SyncException.Kind.UNAUTHORIZED -> TeamsFailure.Forbidden
        SyncException.Kind.NOT_FOUND -> TeamsFailure.NoSuchAccount
        SyncException.Kind.CONFLICT -> TeamsFailure.AlreadyInvited
        else -> TeamsFailure.Protocol
    }

    private companion object {
        fun cursorKey(teamId: String) = "team:$teamId"
    }
}

/** [SyncStateStore] с фиксированным ключом — чтобы SyncEngine хранил per-team курсор. */
private class KeyedStateStore(
    private val backing: SyncStateStore,
    private val key: String,
) : SyncStateStore {
    override fun cursor(accountId: String): Long = backing.cursor(key)
    override fun setCursor(accountId: String, cursor: Long) = backing.setCursor(key, cursor)
}

