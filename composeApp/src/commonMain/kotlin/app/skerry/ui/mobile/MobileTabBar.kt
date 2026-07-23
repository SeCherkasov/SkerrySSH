package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileTab
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.nav_tab_hosts
import app.skerry.ui.generated.resources.nav_tab_more
import app.skerry.ui.generated.resources.nav_tab_snippets
import app.skerry.ui.generated.resources.nav_tab_vault
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

// Mobile bottom tab bar.

/**
 * Bottom tab bar: translucent dark background + top cyan line; active tab cyanBright, others faint.
 * Content height ~64dp, with padding below for the system navigation (home indicator).
 */
@Composable
internal fun MobileTabBar(state: MobileDesignState, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Skerry.colors.railBg.copy(alpha = 0.92f)),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Skerry.colors.cyan08))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 9.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            MobileTab.entries.forEach { tab ->
                MobileTabItem(tab, active = state.tab == tab && state.route == null) { state.select(tab) }
            }
        }
    }
}

@Composable
private fun MobileTabItem(tab: MobileTab, active: Boolean, onClick: () -> Unit) {
    val color = if (active) Skerry.colors.cyanBright else Skerry.colors.faint
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Sym(tab.icon, size = 24.sp, color = color)
        val label = when (tab) {
            MobileTab.Hosts -> stringResource(Res.string.nav_tab_hosts)
            MobileTab.Snippets -> stringResource(Res.string.nav_tab_snippets)
            MobileTab.Vault -> stringResource(Res.string.nav_tab_vault)
            MobileTab.More -> stringResource(Res.string.nav_tab_more)
        }
        Txt(label, color = color, size = 10.sp, weight = if (active) FontWeight.SemiBold else FontWeight.Normal)
    }
}
