package app.skerry.ui.snippet

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Snippet create/edit form state: editable fields as Compose state. Shared by the desktop editor
 * (`SnippetEditor` in [SnippetsView]) and the mobile sheet (`MobileSnippetEditSheet`) — one source
 * of truth for seeding, validation, and draft assembly.
 *
 * [shortcut] is editable only on desktop (chord capture); the mobile sheet just carries it through
 * from the edited snippet so Save doesn't drop the assigned hotkey.
 */
@Stable
class SnippetFormState private constructor(private val editingId: String?) {
    var label: String by mutableStateOf("")
    var command: String by mutableStateOf("")

    /** Committed tags (pills); edited via [addTags]/[removeTag]/[pickTag]. */
    var tags: List<String> by mutableStateOf(emptyList())
        private set

    /** Uncommitted tag input (no pill yet); [toDraft] commits it so it isn't lost. */
    var tagDraft: String by mutableStateOf("")

    var shortcut: String? by mutableStateOf(null)

    val canSave: Boolean get() = label.isNotBlank() && command.isNotBlank()

    /** Commit tag(s) from [raw] ([parseSnippetTags], duplicates dropped) and clear the draft. */
    fun addTags(raw: String) {
        tags = (tags + parseSnippetTags(raw)).distinct()
        tagDraft = ""
    }

    /** Update the tag draft; a comma commits tag(s) immediately (a single tag on Enter, [addTags]). */
    fun updateTagDraft(value: String) {
        if (value.contains(',')) addTags(value) else tagDraft = value
    }

    /** Remove a tag (value as rendered on the pill). */
    fun removeTag(tag: String) {
        tags = tags - tag
    }

    /** Add a tag from a type-ahead suggestion and clear the draft. */
    fun pickTag(tag: String) {
        tags = (tags + tag).distinct()
        tagDraft = ""
    }

    /**
     * Draft for [SnippetManager.save]. Flushes an uncommitted [tagDraft] (typed but no Enter/comma
     * before Save), otherwise the tag would be lost.
     */
    fun toDraft(): SnippetDraft = SnippetDraft(
        id = editingId,
        label = label.trim(),
        command = command,
        tags = (tags + parseSnippetTags(tagDraft)).distinct(),
        shortcut = shortcut,
    )

    companion object {
        /** Form prefilled from [entry] (edit), or empty (create, `entry == null`). */
        fun fromEntry(entry: SnippetEntry?): SnippetFormState =
            SnippetFormState(entry?.id).apply {
                entry?.snippet?.let { s ->
                    label = s.label
                    command = s.command
                    tags = s.tags
                    shortcut = s.shortcut
                }
            }
    }
}
