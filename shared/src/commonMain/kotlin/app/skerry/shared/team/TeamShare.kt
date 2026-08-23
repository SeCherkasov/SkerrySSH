package app.skerry.shared.team

import app.skerry.shared.vault.RecordType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private val json = Json { ignoreUnknownKeys = true }

/**
 * Host payload fields that lose meaning in team scope: `group` is a personal folder structure;
 * `credentialId` references a record in the owner's PERSONAL vault (secrets aren't shared —
 * members' references would dangle; each member connects with their own secret).
 *
 * `notes` is deliberately NOT stripped: a host note describes the box (maintenance window, owner),
 * which is exactly what a team sharing the profile needs.
 */
val HOST_SHARE_STRIP: Set<String> = setOf("group", "credentialId")

/**
 * The same for a snippet or a runbook: `group` is the folder the owner filed it under in their own
 * library, and a folder name is a private filing decision (a customer, a client engagement) that the
 * team has no use for. What is shared is the record; where the owner keeps it is not part of it.
 *
 * `notes` stays, for the same reason it stays on a host: it describes the thing being shared.
 */
val LIBRARY_SHARE_STRIP: Set<String> = setOf("group")

/**
 * The fields [kind] sheds on its way into a team space. A record kind is the whole input, so the
 * decision lives here rather than at the share button: what a share leaks is not something to work
 * out again at a call site, and a wrong answer there is invisible until someone reads the payload.
 *
 * Exhaustive on purpose, and it throws rather than guessing for the kinds the share picker does not
 * offer: an `else` that strips only the folder would answer for `CREDENTIAL` and `TEAM` too, and the
 * day one of them is wired into the picker it would ship key material to everyone holding the space
 * key. A share that fails loudly is a bug found in the first run; this one is found by reading a
 * payload.
 */
fun shareStripFields(kind: RecordType): Set<String> = when (kind) {
    RecordType.HOST -> HOST_SHARE_STRIP
    RecordType.SNIPPET, RecordType.RUNBOOK -> LIBRARY_SHARE_STRIP
    else -> error("sharing $kind into a team space is not defined")
}

/**
 * Strips fields with no meaning in team scope (e.g. a host's `group`, a personal-folder
 * reference) from a JSON payload. A non-JSON or malformed payload is returned as-is: sharing
 * must not fail on format — the receiving side validates records with its own decoder anyway.
 */
fun stripShareFields(payload: ByteArray, fields: Set<String>): ByteArray {
    if (fields.isEmpty()) return payload
    return try {
        val obj = json.parseToJsonElement(payload.decodeToString()).jsonObject
        JsonObject(obj.filterKeys { it !in fields }).toString().encodeToByteArray()
    } catch (e: SerializationException) {
        payload
    } catch (e: IllegalArgumentException) {
        payload
    }
}
