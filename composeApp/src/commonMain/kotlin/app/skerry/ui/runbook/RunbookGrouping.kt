package app.skerry.ui.runbook

import app.skerry.ui.design.Folder
import app.skerry.ui.design.folderNames
import app.skerry.ui.design.foldersOf

/**
 * Names the library's folders in the one collapsed set the app persists
 * ([app.skerry.ui.design.folderCollapseKey]) — a `Production` folder here and a `Production` folder
 * of snippets or hosts fold independently.
 */
const val RUNBOOK_FOLDER_SCOPE = "runbook"

/** Folders the library already uses — what the editor's "Group" select offers. */
fun runbookFolders(runbooks: List<RunbookEntry>): List<String> =
    folderNames(runbooks.map { it.runbook.group })

/**
 * The library's folder sections, in the order the user dragged them into — see
 * [app.skerry.ui.snippet.snippetFolderSections], the runbook half of the same split.
 */
fun runbookFolderSections(runbooks: List<RunbookEntry>): List<Folder<RunbookEntry>> =
    foldersOf(runbooks, ordered = true) { it.runbook.group }
