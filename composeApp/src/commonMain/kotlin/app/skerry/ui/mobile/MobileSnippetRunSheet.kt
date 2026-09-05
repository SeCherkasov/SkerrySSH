package app.skerry.ui.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.FolderCaption
import app.skerry.ui.design.Txt
import app.skerry.ui.design.hasFolders
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_no_matches
import app.skerry.ui.generated.resources.lib_snippets_run_empty
import app.skerry.ui.generated.resources.lib_snippets_run_title
import app.skerry.ui.generated.resources.lib_snippets_search
import app.skerry.ui.snippet.SnippetEntry
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.snippet.matches
import app.skerry.ui.snippet.snippetFolderSections
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Snippet-run picker opened from the terminal header (`bolt` icon): list of saved commands, tap
 * runs the selected snippet in the active session via [onRun].
 *
 * Folder sections and their captions are the desktop palette's ([app.skerry.ui.terminal] `SnippetPalette`)
 * — the sheet is the same picker under a finger, and a command has to sit where its owner filed it
 * on either screen.
 */
@Composable
internal fun MobileSnippetRunSheet(manager: SnippetManager, onRun: (SnippetEntry) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = manager.snippets
    val filtered = remember(all, query) { if (query.isBlank()) all else all.filter { it.matches(query) } }
    // Inline sheet (like the Vault/New connection sheets), rendered at the screen's top-level Box,
    // not via Popup: a focusable Popup shifted window insets and slightly moved the terminal header.
    MobileBottomSheet(onDismiss = onDismiss, maxHeightFraction = 0.7f) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(stringResource(Res.string.lib_snippets_run_title), color = Skerry.colors.text, size = 18.sp, weight = FontWeight.Bold)
            MobileFormInput(query, { query = it }, stringResource(Res.string.lib_snippets_search))
            if (filtered.isEmpty()) {
                Txt(if (all.isEmpty()) stringResource(Res.string.lib_snippets_run_empty) else stringResource(Res.string.lib_snippets_no_matches), color = Skerry.colors.faint, size = 13.sp)
            } else if (hasFolders(filtered) { it.snippet.group }) {
                snippetFolderSections(filtered).forEach { folder ->
                    key(folder.name) {
                        FolderCaption(folder.name)
                        folder.items.forEach { entry ->
                            key(entry.id) {
                                val onClick = remember(entry.id) { { onRun(entry) } }
                                MobileSnippetCard(entry.snippet, onClick)
                            }
                        }
                    }
                }
            } else {
                filtered.forEach { entry ->
                    key(entry.id) {
                        val onClick = remember(entry.id) { { onRun(entry) } }
                        MobileSnippetCard(entry.snippet, onClick)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
