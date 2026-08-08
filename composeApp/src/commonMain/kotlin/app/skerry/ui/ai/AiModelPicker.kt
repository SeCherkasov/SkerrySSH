package app.skerry.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.HLine
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.theme.Skerry
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_ai_models_count
import app.skerry.ui.generated.resources.settings_ai_favorite_model
import app.skerry.ui.generated.resources.settings_ai_no_matches
import app.skerry.ui.generated.resources.settings_ai_search_clear
import org.jetbrains.compose.resources.stringResource

/**
 * AI model picker menu: a search box on top (fuzzy filter over the model catalog) with the
 * matching models listed below, scrollable when the catalog is large (e.g. hundreds of entries
 * after "Refresh models"). Starred models ([favorites]) sort first and their star is
 * drawn in the accent colour; tapping the star toggles the favorite without picking the model. Shared by the desktop
 * [app.skerry.ui.settings.AiSection] and the mobile [app.skerry.ui.mobile.MobileAiScreen] dropdowns.
 */
@Composable
fun ModelPickerMenu(
    modifier: Modifier = Modifier,
    models: List<String>,
    selected: String,
    favorites: Set<String>,
    onToggleFavorite: (String) -> Unit,
    onSelect: (String) -> Unit,
    emptyText: String,
    searchPlaceholder: String,
    maxHeight: Dp = 320.dp,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(models, query, favorites) { filterAndSortModels(models, query, favorites) }
    Column(
        modifier
            .heightIn(max = maxHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(Skerry.colors.surface2)
            .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(8.dp)),
    ) {
        // Search row: fixed at the top, filters the list below as you type.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Decorative: the field next to it carries the meaning, and Sym is a BasicText whose
            // content would otherwise be read out as the literal ligature name.
            Sym("search", size = 14.sp, color = Skerry.colors.faint, modifier = Modifier.clearAndSetSemantics {})
            val ui = LocalFonts.current.ui
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Skerry.colors.text, fontSize = 12.5.sp, fontFamily = ui),
                cursorBrush = SolidColor(Skerry.colors.cyan),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // Enter picks the first match, as in the command palette — otherwise the only way
                // out of the search field is a pointer.
                keyboardActions = KeyboardActions(onSearch = { filtered.firstOrNull()?.let(onSelect) }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (query.isEmpty()) {
                            Txt(searchPlaceholder, color = Skerry.colors.faint, size = 12.5.sp)
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                val clearLabel = stringResource(Res.string.settings_ai_search_clear)
                Box(
                    Modifier
                        .size(ICON_TARGET) // an icon-only control still needs a finger-sized target
                        .clip(RoundedCornerShape(6.dp))
                        .semantics { contentDescription = clearLabel }
                        .clickable { query = "" },
                    contentAlignment = Alignment.Center,
                ) { Sym("close", size = 13.sp, color = Skerry.colors.faint) }
            }
        }
        // Catalog size, so a refresh's result is visible at a glance (e.g. "321 models"); with a
        // query typed it counts what is actually listed, or it would contradict the rows below.
        Txt(
            stringResource(Res.string.settings_ai_models_count, if (query.isBlank()) models.size else filtered.size),
            color = Skerry.colors.faint,
            size = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
        HLine()
        if (filtered.isEmpty()) {
            Txt(
                if (models.isEmpty()) emptyText else stringResource(Res.string.settings_ai_no_matches),
                color = Skerry.colors.faint,
                size = 12.5.sp,
                // A query that matches nothing is otherwise silent for a screen reader.
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else {
            // Lazy: a refreshed catalog can hold hundreds of entries; composing every row up front
            // would be wasted work the moment the menu is opened. weight with fill = false caps the
            // list at the space the header leaves without stretching a two-row catalog to 320dp.
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false),
            ) {
                items(filtered, key = { it }) { m ->
                    val starred = m in favorites
                    val starLabel = stringResource(Res.string.settings_ai_favorite_model, m)
                    val isSelected = m == selected
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // Two sibling targets, not a clickable inside a clickable: nesting them
                        // merges the row into one accessibility node and the star's action is lost.
                        // toggleable rather than clickable, so the star reports checked/unchecked
                        // instead of reading as a plain button.
                        Box(
                            Modifier
                                .size(ICON_TARGET)
                                .clip(RoundedCornerShape(6.dp))
                                .semantics { contentDescription = starLabel }
                                .toggleable(
                                    value = starred,
                                    onValueChange = { onToggleFavorite(m) },
                                    role = Role.Checkbox,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            // One glyph for both states: the bundled symbol font maps `star_border`
                            // onto the same outline as `star`, and the filled variant (`star.fill`)
                            // has no ligature name to reach it by.
                            Sym("star", size = 13.sp, color = if (starred) Skerry.colors.sunset else Skerry.colors.faint)
                        }
                        Txt(
                            m,
                            color = if (isSelected) Skerry.colors.cyanBright else Skerry.colors.text,
                            weight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                            size = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                // selectable, not clickable + semantics: it states the selection to
                                // a screen reader, which colour alone does not.
                                .selectable(selected = isSelected, onClick = { onSelect(m) })
                                .padding(horizontal = 4.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Rows of the picker for [query]: a case-insensitive substring match over [models], starred entries
 * first. Plain function rather than logic inside the composable, so the filter can be tested.
 */
fun filterAndSortModels(models: List<String>, query: String, favorites: Set<String>): List<String> {
    val q = query.trim()
    val base = if (q.isEmpty()) models else models.filter { it.contains(q, ignoreCase = true) }
    // Starred first, stable order within each group.
    return base.sortedBy { it !in favorites }
}

/** Minimum touch/click target for the icon-only controls in the picker. */
private val ICON_TARGET = 24.dp
