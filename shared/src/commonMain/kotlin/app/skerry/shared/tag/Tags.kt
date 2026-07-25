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
 * The one tag with behavior attached: a host carrying it is production, which turns on the
 * production guard (connect confirmation, risky-command confirmation, red accent — see
 * [app.skerry.shared.guard.ProductionGuard]). Canonical form, so it compares against stored tags
 * directly. Not localized: the tag is data that syncs between clients and locales, a translated
 * value would silently disarm the guard for everyone on another language.
 */
const val PROD_TAG = "prod"

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
 * only in case, hoist [PROD_TAG] to the front, keep first-seen order for the rest and cap the count
 * at [MAX_TAGS_PER_RECORD]. Hoisting happens before the cap, so `prod` can't fall off the end of a
 * long list and take the production guard with it.
 */
fun normalizeTags(raw: Iterable<String>): List<String> =
    orderTagsProdFirst(raw.mapNotNull(::normalizeTag).distinct()).take(MAX_TAGS_PER_RECORD)

/**
 * [PROD_TAG] first, everything else in its original order. Applied on write ([normalizeTags]) and
 * again on display, since records stored before this rule keep their old order — `prod` is what the
 * user must see first on a host row, not the tag that happened to be typed first.
 */
fun orderTagsProdFirst(tags: List<String>): List<String> =
    if (tags.firstOrNull() == PROD_TAG || PROD_TAG !in tags) tags
    else listOf(PROD_TAG) + tags.filterNot { it == PROD_TAG }
