package app.skerry.shared.host

/**
 * Max length of a single tag and max number of tags per host. Not a security boundary (tags only
 * flow into Compose text / JSON / string comparison — no injection), but a guard against
 * pathological input: oversized or accumulated tags would bloat `hosts.json` and slow rendering/
 * filtering. Normalization is the only entry point for tags coming from the form.
 */
const val MAX_TAG_LENGTH = 32
const val MAX_TAGS_PER_HOST = 20

/**
 * Canonicalize a host tag: trim, strip `#` from both ends, lowercase, and truncate to
 * [MAX_TAG_LENGTH]; an empty result becomes `null` (tag not added). The canonical form makes chip
 * filtering a plain string comparison and prevents "Prod"/"#prod" duplicates. Lives in `shared`
 * (not the UI layer) because [Host.tags] is stored in this form — every write path (form, future
 * sync import, migration) must go through the same canonicalization. Pure function, covered by
 * [app.skerry.shared.host.HostTagsTest].
 */
fun normalizeTag(raw: String): String? =
    raw.trim().trim('#').trim().lowercase().take(MAX_TAG_LENGTH).ifBlank { null }
