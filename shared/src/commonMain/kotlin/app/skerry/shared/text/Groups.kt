package app.skerry.shared.text

/**
 * Max length of a stored folder name. Not a security boundary (a name only flows into Compose text
 * and JSON), but a folder header is one line of chrome above a list: a pasted paragraph would push
 * the list off the screen, and the name is also the key the fold state is filed under.
 */
const val MAX_GROUP_LENGTH = 60

/**
 * Canonicalize a folder name — the optional `group` a snippet, a runbook or a keychain secret is
 * filed under. Everything that draws as nothing is dropped ([stripInvisible], which also removes the
 * line breaks a paste brings in — a folder name is a label, not free-form text), the edges are
 * trimmed, the length is capped ([MAX_GROUP_LENGTH]), and blank becomes `null`: "no folder" is the
 * absence of a value, so the synthetic *Ungrouped* bucket has one thing to test for.
 *
 * The name is compared as well as drawn — it is the grouping key, and the key the collapsed state is
 * filed under — so two names that render alike have to *be* one name. Lives in `shared` because
 * groups are *stored* in this form: every write path goes through here.
 */
fun normalizeGroup(raw: String?): String? {
    val text = raw ?: return null
    return capText(stripInvisible(text).trim(), MAX_GROUP_LENGTH).ifBlank { null }
}
