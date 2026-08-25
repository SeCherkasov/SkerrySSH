package app.skerry.ui.teams

import app.skerry.shared.team.PeerKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * Which colleagues a peer-key lookup has refused to seal to, and what has already been said about
 * them in the current pass.
 *
 * [TeamsFailure] is an enum and carries no account id, so the error told the user to confirm a
 * fingerprint in the member list without naming whose row — and the pin cannot supply the name
 * either: a colleague who rotated their Teams identity leaves one that is still confirmed, identical
 * to every healthy member's (#326). This is what the member list marks a row from.
 *
 * A set because one rotation walks every recipient and may refuse several of them; its size is the
 * member list's, which is the server's to bound.
 *
 * A mark is evidence about one account, and only evidence about that same account takes it away —
 * the lookup that finds the key back on its pin, or the confirm that moves the pin to it. Tying the
 * marks to the error slot instead would clear every one of them on the next operation of any kind,
 * so the second colleague a rotation refused would go back to wearing the quiet confirmed mark
 * while nothing had been done about their key.
 */
internal class PeerRefusals {

    private val _accounts = MutableStateFlow<Set<String>>(emptySet())

    /** The accounts whose published key is not the one on record for them. */
    val accounts: StateFlow<Set<String>> = _accounts

    /**
     * account+fingerprint pairs already announced, so one refusal on the adopting side is said once
     * per pass rather than once per scope it appears in. A de-duplicator, not a record of what the
     * user has seen: it is emptied with the slot the warning lives in, and from there the warning has
     * to be earnable again — otherwise the next pass over the same unconfirmed key would say nothing.
     *
     * A flow rather than a plain set because [forgetAnnounced] runs on whichever thread emptied the
     * error slot while a sync-driven pass may be adding to it on another.
     */
    private val announced = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Files the verdict a lookup reached about [accountId], and hands it on unchanged. The lookup is
     * the authority on the account it looked up and on no other: [PeerKeys.Pinned] retires the mark,
     * [PeerKeys.Unconfirmed] raises it, and an account that has published nothing was never held to
     * a pin at all.
     */
    fun record(accountId: String, keys: PeerKeys): PeerKeys {
        when (keys) {
            is PeerKeys.Pinned -> settled(accountId)
            is PeerKeys.Unconfirmed -> mark(accountId)
            PeerKeys.Unpublished -> Unit
        }
        return keys
    }

    /** Whether this refusal is the first of its account+fingerprint in the current pass. */
    fun announcing(keys: PeerKeys.Unconfirmed): Boolean {
        val pair = "${keys.accountId}:${keys.fingerprint}"
        return pair !in announced.getAndUpdate { it + pair }
    }

    fun mark(accountId: String) = _accounts.update { it + accountId }

    /** The account's key is on record again — a lookup found it pinned, or a confirm wrote it. */
    fun settled(accountId: String) = _accounts.update { it - accountId }

    /** Empties the de-duplicator with the error slot the warnings it guards are written to. */
    fun forgetAnnounced() {
        announced.value = emptySet()
    }

    /** Empties both: another account's vault is another account's colleagues. */
    fun clear() {
        _accounts.value = emptySet()
        announced.value = emptySet()
    }
}
