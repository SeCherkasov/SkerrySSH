package app.skerry.shared.team

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private val json = Json { ignoreUnknownKeys = true }

/**
 * Поля payload'а хоста, теряющие смысл в team-scope: `group` — личная структура папок,
 * `credentialId`/`identityId` — ссылки на записи ЛИЧНОГО vault'а владельца (секреты не шарятся,
 * у участников ссылки повисли бы; каждый подключается своим секретом).
 */
val HOST_SHARE_STRIP: Set<String> = setOf("group", "credentialId", "identityId")

/**
 * Убрать из JSON-payload'а поля, не имеющие смысла в team-scope (например, `group` хоста —
 * ссылку на личную папку). Не-JSON или битый payload возвращается как есть: шеринг не должен
 * падать из-за формата, приёмная сторона всё равно валидирует записи своим декодером.
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
