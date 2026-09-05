package app.skerry.ui.snippet

import app.skerry.shared.snippet.Snippet
import app.skerry.ui.design.FolderItems
import app.skerry.ui.design.storedFolderName

/**
 * How the snippet library answers a folder reordering ([app.skerry.ui.design.FolderItems]).
 *
 * Folders are keyed the way the sections draw them ([app.skerry.ui.design.storedFolderName]), and the
 * unfiled bucket is pinned last because that is where the library draws it — the stored order has to
 * agree with the screen, or a folder dragged to the bottom lands above a bucket the user sees below.
 */
object SnippetFolderItems : FolderItems<Snippet> {
    override fun idOf(item: Snippet): String = item.id
    override fun folderOf(item: Snippet): String? = item.group
    override fun withFolder(item: Snippet, folder: String?): Snippet = item.copy(group = folder)
    override fun canonicalName(folder: String?): String? = storedFolderName(folder)
    override val ungroupedLast: Boolean get() = true
}
