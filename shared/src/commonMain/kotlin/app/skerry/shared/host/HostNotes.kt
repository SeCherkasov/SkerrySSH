package app.skerry.shared.host

/**
 * Max length of a host note. Not a security boundary (notes only flow into Compose text / JSON —
 * no injection), but a guard against pathological input: a pasted log file would bloat the stored
 * profile (and the synced record) and slow the hover tooltip's layout.
 */
const val MAX_NOTES_LENGTH = 500

/**
 * Canonicalize a host note: trim the edges and cap the length ([MAX_NOTES_LENGTH]); blank becomes
 * `null` (no note stored, so [Host.notes] tells "has a note" apart from "has an empty note" — the
 * hover tooltip and the mobile Details row both key off `null`). Inner line breaks are kept: a note
 * is free-form text, not a label. Lives in `shared` because notes are *stored* in this form —
 * every write path must go through the same normalization.
 */
fun normalizeNotes(raw: String): String? = raw.trim().take(MAX_NOTES_LENGTH).ifBlank { null }
