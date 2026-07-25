package app.skerry.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_trash_empty_hint
import app.skerry.ui.theme.Skerry
import app.skerry.ui.vault.TrashList
import app.skerry.ui.vault.rememberTrashController
import org.jetbrains.compose.resources.stringResource

/** Settings → Trash: deleted records still restorable within the retention window. */
@Composable
fun TrashSection() {
    val controller = rememberTrashController()
    Column(Modifier.fillMaxWidth()) {
        // The hint doubles as the empty state's explanation; here it states the window up front,
        // before the user restores or purges anything.
        if (controller?.items?.isNotEmpty() == true) {
            Txt(
                stringResource(Res.string.settings_trash_empty_hint),
                color = Skerry.colors.dim,
                size = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
        TrashList(controller)
    }
}
