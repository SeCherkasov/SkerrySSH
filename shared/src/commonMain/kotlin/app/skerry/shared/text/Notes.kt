package app.skerry.shared.text

/**
 * Max length of a stored note. Not a security boundary (notes only flow into Compose text / JSON —
 * no injection), but a guard against pathological input: a pasted log file would bloat the stored
 * record (and the synced blob) and slow the hover tooltip's layout.
 */
const val MAX_NOTES_LENGTH = 500

/**
 * Canonicalize a note — a free-form remark on a host profile ([app.skerry.shared.host.Host.notes])
 * or a keychain secret ([app.skerry.shared.vault.Credential.note]): trim the edges and cap the
 * length ([MAX_NOTES_LENGTH]); blank becomes `null` (no note stored, so the field tells "has a note"
 * apart from "has an empty note" — the hover tooltip and the detail rows both key off `null`). Inner
 * line breaks are kept: a note is free-form text, not a label. Lives in `shared` because notes are
 * *stored* in this form — every write path must go through the same normalization.
 */
fun normalizeNotes(raw: String): String? = capNotes(raw.trim()).ifBlank { null }

/**
 * Cut [raw] down to [MAX_NOTES_LENGTH] without splitting a surrogate pair: a plain `take` counts
 * UTF-16 code units, so an emoji straddling the limit would leave a lone high surrogate behind,
 * which the UTF-8 encoder on the way into the vault blob turns into a replacement character —
 * silent corruption rather than an honest truncation. The dangling half is dropped instead.
 * Used by the input fields (per keystroke) and by [normalizeNotes] (on the way to the store).
 */
fun capNotes(raw: String): String {
    if (raw.length <= MAX_NOTES_LENGTH) return raw
    val cut = if (raw[MAX_NOTES_LENGTH - 1].isHighSurrogate()) MAX_NOTES_LENGTH - 1 else MAX_NOTES_LENGTH
    return raw.substring(0, cut)
}
