package app.skerry.ui.snippet

import app.skerry.shared.tag.normalizeTags

/**
 * Parses a snippet tag string into a canonical list: separators are comma/space/newline/tab, every
 * part goes through [app.skerry.shared.tag.normalizeTag] (lowercase, no `#`, length-capped), empties
 * and duplicates are dropped, first-seen order is preserved. Shared by the desktop ([SnippetsView])
 * and mobile (`MobileSnippetsView`) editors. Casing is canonicalized exactly like host tags — tags
 * double as categories, and "БД"/"бд" would otherwise split into two sections.
 */
fun parseSnippetTags(text: String): List<String> =
    normalizeTags(text.split(',', ' ', '\n', '\t'))
