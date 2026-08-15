package app.skerry.ui.design

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import app.skerry.ui.desktop.runForm
import kotlin.test.Test

/**
 * A fact row is one fact: "Exported — never" must reach a screen reader as a single announcement,
 * not as two unrelated stops ("Exported", swipe, "never"). The label and the value merge into one
 * accessibility node.
 */
@OptIn(ExperimentalTestApi::class)
class KeyValueRowSemanticsTest {

    @Test
    fun `label and value are one accessibility node`() = runForm(
        content = { KeyValueRow("Exported", "never") },
    ) {
        onNodeWithText("Exported").assert(hasText("never"))
    }
}
