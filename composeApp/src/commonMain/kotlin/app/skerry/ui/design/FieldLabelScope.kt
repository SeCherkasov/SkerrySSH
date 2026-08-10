package app.skerry.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Label of the form field currently being composed.
 *
 * A field's caption is drawn above it as a separate line of text, which leaves the input itself
 * anonymous: a screen reader lands on it and can say nothing but "text field", and the caption it
 * would need is a sibling with no relation to it. Publishing the caption here lets the input adopt
 * it as its own accessible name, with no call site repeating the string.
 *
 * Set by [FormField] and the shells' own label-plus-content wrappers; read by [Modifier.fieldName]
 * on the inputs inside them. `null` outside any of them: a bare input names itself or stays
 * unnamed, exactly as before.
 */
internal val LocalFieldLabel = compositionLocalOf<String?> { null }

/**
 * Caption above [field], with the caption published to it as its accessible name.
 *
 * The pairing is the point: a caption and an input written as two statements are two unrelated
 * nodes, and every form in the app was written that way. Nesting the input makes the relation
 * explicit at the one place that knows about it.
 */
@Composable
fun FormField(
    label: String,
    top: Dp = 12.dp,
    bottom: Dp = 5.dp,
    /** Form captions are small caps almost everywhere; the password dialogs are the exception. */
    uppercase: Boolean = true,
    field: @Composable () -> Unit,
) {
    // Case is a drawing decision: the plain label is what names the input, and a screen reader
    // should not be handed SHOUTED text.
    FieldLabel(if (uppercase) labelUppercase(label) else label, top = top, bottom = bottom)
    CompositionLocalProvider(LocalFieldLabel provides label) { field() }
}

/**
 * Names this input after the caption above it ([FormField]), or after [fallback] outside one — a
 * search field has no caption, and its placeholder is the only label it ever shows. Without either,
 * a no-op: an input that carries its own name, or has none, is left as it is.
 */
@Composable
internal fun Modifier.fieldName(fallback: String? = null): Modifier {
    val label = LocalFieldLabel.current ?: fallback ?: return this
    return semantics { contentDescription = label }
}

/**
 * Names a control whose value is nothing but the text it draws — a picker trigger, a combo row.
 *
 * [fieldName] is wrong on those. It stamps a description on a node that merges its children, and a
 * description replaces the text under it rather than joining it: the caption would be announced and
 * the chosen host or port type would not be announced at all. An editable field has no such problem
 * — its content is a separate property from its name — so it keeps [fieldName].
 */
@Composable
internal fun Modifier.fieldValueName(value: String): Modifier {
    val label = LocalFieldLabel.current ?: return semantics { contentDescription = value }
    // Comma-joined, which is how Compose itself joins the texts it merges.
    return semantics { contentDescription = "$label, $value" }
}
