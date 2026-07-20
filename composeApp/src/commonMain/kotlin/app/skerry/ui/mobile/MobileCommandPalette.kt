package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.terminal.TerminalHistoryRecord
import app.skerry.shared.terminal.TerminalHistoryStore
import app.skerry.shared.terminal.commandSuggestions
import app.skerry.ui.design.D
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_palette_empty
import app.skerry.ui.generated.resources.term_palette_hint
import app.skerry.ui.generated.resources.term_palette_placeholder
import app.skerry.ui.generated.resources.term_palette_this_host
import app.skerry.ui.generated.resources.term_palette_title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * Command palette on mobile: the desktop overlay ([app.skerry.ui.terminal.CommandPalette]) as a
 * bottom sheet, over the same [commandSuggestions]. Tapping a command inserts it into the terminal
 * without running it — the last keystroke stays the user's.
 */
@Composable
internal fun MobileCommandPaletteSheet(
    history: TerminalHistoryStore?,
    currentKey: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val mono = LocalFonts.current.mono
    var query by remember { mutableStateOf("") }
    var records by remember { mutableStateOf<List<TerminalHistoryRecord>?>(null) }
    // Reading history decrypts vault records — disk work, kept off the UI thread.
    LaunchedEffect(history) {
        records = withContext(Dispatchers.Default) { history?.all().orEmpty() }
    }
    val suggestions = remember(records, query, currentKey) {
        records?.let { commandSuggestions(it, currentKey, query) }.orEmpty()
    }

    MobileBottomSheet(onDismiss = onDismiss, maxHeightFraction = 0.75f) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(stringResource(Res.string.term_palette_title), color = D.text, size = 18.sp, weight = FontWeight.Bold)
            MobileFormInput(query, { query = it }, stringResource(Res.string.term_palette_placeholder))
            if (records != null && suggestions.isEmpty()) {
                Txt(stringResource(Res.string.term_palette_empty), color = D.faint, size = 13.sp)
            }
            suggestions.forEach { suggestion ->
                key(suggestion.command) {
                    val onClick = remember(suggestion.command) { { onPick(suggestion.command) } }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(D.card)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Txt(suggestion.command, color = D.text, size = 12.5.sp, font = mono, modifier = Modifier.weight(1f))
                        val origin = if (suggestion.fromCurrentHost) stringResource(Res.string.term_palette_this_host) else suggestion.hostLabel
                        if (origin != null) Txt(origin, color = if (suggestion.fromCurrentHost) D.cyanBright else D.faint, size = 10.sp)
                    }
                }
            }
            Txt(stringResource(Res.string.term_palette_hint), color = D.faint, size = 11.sp)
            Spacer(Modifier.height(4.dp))
        }
    }
}
