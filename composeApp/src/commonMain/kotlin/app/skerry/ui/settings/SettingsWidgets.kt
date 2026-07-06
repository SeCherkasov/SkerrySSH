package app.skerry.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.D
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt

// Shared settings-section widgets (used by several *Section.kt files in this package).

/** Settings section header: title plus a dimmed subtitle, spaced above the content. */
@Composable
internal fun SectionTitle(title: String, subtitle: String) {
    Txt(title, color = D.text, size = 16.sp, weight = FontWeight.SemiBold)
    Txt(subtitle, color = D.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
}

/** A toggle-setting row: title and description on the left, [Toggle] on the right. */
@Composable
internal fun SettingToggleRow(title: String, desc: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, color = D.text, size = 13.sp, weight = FontWeight.Medium)
            if (desc.isNotEmpty()) Txt(desc, color = D.dim, size = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Toggle(on, onToggle, Modifier.padding(top = 2.dp))
    }
}
