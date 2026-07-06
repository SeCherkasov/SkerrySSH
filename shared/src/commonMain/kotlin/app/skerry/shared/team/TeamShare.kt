package app.skerry.shared.team

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private val json = Json { ignoreUnknownKeys = true }

/**
 * Host payload fields that lose meaning in team scope: `group` is a personal folder structure;
 * `credentialId`/`identityId` reference records in the owner's PERSONAL vault (secrets aren't
 * shared — members' references would dangle; each member connects with their own secret).
 */
val HOST_SHARE_STRIP: Set<String> = setOf("group", "credentialId", "identityId")

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
