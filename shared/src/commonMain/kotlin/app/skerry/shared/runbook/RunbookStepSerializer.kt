package app.skerry.shared.runbook

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Value of the stored `kind` field for each [RunbookStep] variant. */
private const val KIND_COMMAND = "command"
private const val KIND_TRANSFER = "transfer"

/** Name of the discriminator field itself. */
private const val KIND = "kind"

/**
 * Stored form of a [RunbookStep]: the variant's own fields plus a `kind` discriminator.
 *
 * Hand-written rather than `@JsonClassDiscriminator`, for one reason: runbooks written before
 * transfer steps existed have no discriminator at all, and they live in vaults and arrive over
 * sync. A payload that fails to decode is dropped by
 * [app.skerry.shared.vault.VaultRecordCodec] — the runbook would disappear from the library — so a
 * step with no `kind` reads as a [RunbookStep.Command], which is what every step was back then.
 *
 * The discriminator is written but never handed to the variant serializers, so the variants stay
 * plain data classes without a redundant field, and decoding doesn't depend on the surrounding
 * `Json` ignoring unknown keys.
 *
 * JSON only: the vault and the sync wire are JSON, and the fallback above is a JSON-shaped
 * decision. Another format is a programming error, not a runtime case.
 */
internal object RunbookStepSerializer : KSerializer<RunbookStep> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("app.skerry.shared.runbook.RunbookStep")

    override fun serialize(encoder: Encoder, value: RunbookStep) {
        val output = encoder as? JsonEncoder ?: throw SerializationException(JSON_ONLY)
        val fields = when (value) {
            is RunbookStep.Command -> output.json.encodeToJsonElement(RunbookStep.Command.serializer(), value)
            is RunbookStep.Transfer -> output.json.encodeToJsonElement(RunbookStep.Transfer.serializer(), value)
        }
        val kind = when (value) {
            is RunbookStep.Command -> KIND_COMMAND
            is RunbookStep.Transfer -> KIND_TRANSFER
        }
        output.encodeJsonElement(JsonObject(fields.jsonObject + (KIND to JsonPrimitive(kind))))
    }

    override fun deserialize(decoder: Decoder): RunbookStep {
        val input = decoder as? JsonDecoder ?: throw SerializationException(JSON_ONLY)
        val stored = input.decodeJsonElement().jsonObject
        val fields = JsonObject(stored - KIND)
        return when (stored[KIND]?.jsonPrimitive?.contentOrNull) {
            KIND_TRANSFER -> input.json.decodeFromJsonElement(RunbookStep.Transfer.serializer(), fields)
            // Anything else, including a step written before the discriminator existed.
            else -> input.json.decodeFromJsonElement(RunbookStep.Command.serializer(), fields)
        }
    }
}

private const val JSON_ONLY = "RunbookStep is stored as JSON only"
