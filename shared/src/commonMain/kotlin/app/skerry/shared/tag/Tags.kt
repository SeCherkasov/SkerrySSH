package app.skerry.shared.tag

/**
 * Max length of a single tag and max number of tags per record. Not a security boundary (tags only
 * flow into Compose text / JSON / string comparison — no injection), but a guard against
 * pathological input: oversized or accumulated tags would bloat the stored records and slow
 * rendering/filtering.
 */
const val MAX_TAG_LENGTH = 32
const val MAX_TAGS_PER_RECORD = 20

/**
 * Canonicalize a tag: trim, strip `#` from both ends, lowercase, and truncate to [MAX_TAG_LENGTH];
 * an empty result becomes `null` (tag not added). The canonical form makes chip filtering a plain
 * string comparison and prevents "Prod"/"#prod" duplicates. Lives in `shared` (not the UI layer)
 * because tags are *stored* in this form — every write path (form, sync import, migration) must go
 * through the same canonicalization. Shared by hosts ([app.skerry.shared.host.Host.tags]) and
 * snippets ([app.skerry.shared.snippet.Snippet.tags]).
 */
fun normalizeTag(raw: String): String? =
    raw.trim().trim('#').trim().lowercase().take(MAX_TAG_LENGTH).ifBlank { null }

/**
 * Canonicalize a whole tag list: normalize each entry, drop blanks, collapse duplicates that differ
 * only in case, keep first-seen order and cap the count at [MAX_TAGS_PER_RECORD].
 */
fun normalizeTags(raw: Iterable<String>): List<String> =
    raw.mapNotNull(::normalizeTag).distinct().take(MAX_TAGS_PER_RECORD)
