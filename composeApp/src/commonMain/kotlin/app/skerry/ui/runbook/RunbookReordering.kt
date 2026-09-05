package app.skerry.ui.runbook

import app.skerry.shared.runbook.Runbook
import app.skerry.ui.design.FolderItems
import app.skerry.ui.design.storedFolderName

/** The runbook library's half of [app.skerry.ui.snippet.SnippetFolderItems] — same rules, other type. */
object RunbookFolderItems : FolderItems<Runbook> {
    override fun idOf(item: Runbook): String = item.id
    override fun folderOf(item: Runbook): String? = item.group
    override fun withFolder(item: Runbook, folder: String?): Runbook = item.copy(group = folder)
    override fun canonicalName(folder: String?): String? = storedFolderName(folder)
    override val ungroupedLast: Boolean get() = true
}
