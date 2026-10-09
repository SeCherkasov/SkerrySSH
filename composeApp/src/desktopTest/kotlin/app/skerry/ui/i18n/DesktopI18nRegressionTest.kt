package app.skerry.ui.i18n

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import app.skerry.ui.desktop.runForm
import app.skerry.ui.vault.DeleteSecretDialog
import java.util.Locale
import kotlin.test.Test

/** Exercises the rendered grammar and Foundation controls, rather than resource-key parity. */
@OptIn(ExperimentalTestApi::class)
class DesktopI18nRegressionTest {
    @Test
    fun `bound credential warning inflects its host count in every language`() = preservingLocale {
        val nouns = mapOf(
            UiLanguage.English to listOf("host", "hosts", "hosts", "hosts"),
            UiLanguage.Russian to listOf("хост", "хоста", "хостов", "хост"),
            UiLanguage.Chinese to listOf("台主机", "台主机", "台主机", "台主机"),
            UiLanguage.Turkish to listOf("sunucu", "sunucu", "sunucu", "sunucu"),
            UiLanguage.German to listOf("Host", "Hosts", "Hosts", "Hosts"),
        )
        nouns.forEach { (language, words) ->
            Locale.setDefault(Locale.forLanguageTag(language.localeTag!!))
            listOf(1, 2, 5, 21).zip(words).forEach { (count, noun) ->
                runForm({ DeleteSecretDialog("database", count, onDismiss = {}, onConfirm = {}) }) {
                    val prefix = "$count $noun" + if (language == UiLanguage.Chinese) "" else " "
                    onAllNodes(hasText(prefix, substring = true)).assertCountEquals(1)
                }
            }
        }
    }

    @Test
    fun `text context menu follows language changes including simplified Chinese`() = preservingLocale {
        val language = mutableStateOf(UiLanguage.English)
        val labels = listOf(
            UiLanguage.English to "Select all",
            UiLanguage.Chinese to "全选",
            UiLanguage.Russian to "Выбрать все",
            UiLanguage.Turkish to "Tümünü seç",
            UiLanguage.German to "Alles auswählen",
            UiLanguage.English to "Select all",
        )
        runForm({
            AppLocaleProvider(language.value) {
                BasicTextField("sample", onValueChange = {}, modifier = Modifier.width(300.dp))
            }
        }) {
            labels.forEach { (chosen, label) ->
                runOnIdle { language.value = chosen }
                waitForIdle()
                onNodeWithText("sample").performMouseInput {
                    moveTo(center)
                    press(MouseButton.Secondary)
                    release(MouseButton.Secondary)
                }
                onNodeWithText(label).assertExists().performKeyInput { pressKey(Key.Escape) }
            }
        }
    }

    private fun preservingLocale(body: () -> Unit) {
        val original = Locale.getDefault()
        try {
            body()
        } finally {
            Locale.setDefault(original)
        }
    }
}
