package app.skerry.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
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
import app.skerry.ui.generated.resources.settings_ai_no_matches
import org.jetbrains.compose.resources.stringResource

/**
 * AI model picker menu: a search box on top (fuzzy filter over the model catalog) with the
 * matching models listed below, scrollable when the catalog is large (e.g. hundreds of entries
 * after "Refresh models"). Starred models ([favorites]) sort first and show a filled star; tapping
 * the star toggles the favorite without picking the model. Shared by the desktop
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
    val filtered = remember(models, query, favorites) {
        val q = query.trim()
        val base = if (q.isEmpty()) models else models.filter { it.contains(q, ignoreCase = true) }
        // Starred first, stable order within each group.
        base.sortedBy { it !in favorites }
    }
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
            Sym("search", size = 14.sp, color = Skerry.colors.faint)
            val ui = LocalFonts.current.ui
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Skerry.colors.text, fontSize = 12.5.sp, fontFamily = ui),
                cursorBrush = SolidColor(Skerry.colors.cyan),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
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
                Sym("close", size = 13.sp, color = Skerry.colors.faint,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { query = "" }.padding(2.dp))
            }
        }
        // Catalog size, so a refresh's result is visible at a glance (e.g. "321 models").
        Txt(
            stringResource(Res.string.settings_ai_models_count, models.size),
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
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        } else {
            // Lazy: a refreshed catalog can hold hundreds of entries; composing every row up front
            // would be wasted work the moment the menu is opened. weight(1f) inside the heightIn-
            // capped column lets layout compute the list height from the header, no magic constant.
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
            ) {
                items(filtered, key = { it }) { m ->
                    val starred = m in favorites
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(m) }.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Star toggles the favorite without selecting the model.
                        Sym(
                            if (starred) "star" else "star_border",
                            size = 13.sp,
                            color = if (starred) Skerry.colors.sunset else Skerry.colors.faint,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onToggleFavorite(m) }
                                .padding(3.dp),
                        )
                        Txt(
                            m,
                            color = if (m == selected) Skerry.colors.cyanBright else Skerry.colors.text,
                            size = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
