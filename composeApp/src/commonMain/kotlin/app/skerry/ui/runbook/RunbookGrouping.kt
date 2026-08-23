package app.skerry.ui.runbook

import app.skerry.ui.design.folderNames

/**
 * Names the library's folders in the one collapsed set the app persists
 * ([app.skerry.ui.design.folderCollapseKey]) — a `Production` folder here and a `Production` folder
 * of snippets or hosts fold independently.
 */
const val RUNBOOK_FOLDER_SCOPE = "runbook"

/** Folders the library already uses — what the editor's "Group" select offers. */
fun runbookFolders(runbooks: List<RunbookEntry>): List<String> =
    folderNames(runbooks.map { it.runbook.group })
