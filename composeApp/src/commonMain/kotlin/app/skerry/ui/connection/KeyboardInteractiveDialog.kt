package app.skerry.ui.connection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ssh.KeyboardInteractiveChallenge
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.generated.resources.shell_kbdint_continue
import app.skerry.ui.generated.resources.shell_kbdint_jump
import app.skerry.ui.generated.resources.shell_kbdint_title
import app.skerry.ui.nav.PlatformBackHandler
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Prompt for a server's keyboard-interactive challenge — a 2FA code, an SMS token, a push
 * confirmation. Shown while `connect` is blocked waiting for the answer, on desktop and mobile
 * alike; style follows [DesktopPasswordDialog].
 *
 * Every string in the challenge comes from the server and is therefore untrusted: it is sanitized
 * ([sanitizeServerText]) before being rendered, so a hostile host can't push the buttons off-screen
 * with a wall of text or smuggle control characters into the UI.
 *
 * Confirming is allowed with empty fields on purpose: some exchanges (a push notification you accept
 * on the phone) expect an empty answer, and disabling the button would make them impossible to
 * complete.
 */
@Composable
fun KeyboardInteractiveDialog(
    challenge: KeyboardInteractiveChallenge,
    onDismiss: () -> Unit,
    onSubmit: (List<String>) -> Unit,
) {
    val noop = remember { MutableInteractionSource() }
    val answers = remember(challenge) { mutableStateListOf(*Array(challenge.prompts.size) { "" }) }
    val focus = remember(challenge) { FocusRequester() }
    val submit = { onSubmit(answers.toList()) }

    LaunchedEffect(challenge) { runCatching { focus.requestFocus() } }
    PlatformBackHandler(onBack = onDismiss)

    Box(
        Modifier.fillMaxSize().background(Skerry.colors.modalScrim)
            .clickable(interactionSource = noop, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Skerry.colors.surfaceDeep)
                .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                .clickable(interactionSource = noop, indication = null, onClick = {})
                .verticalScroll(rememberScrollState())
                .padding(26.dp),
        ) {
            val name = sanitizeServerText(challenge.name, MAX_TITLE_CHARS)
            Txt(
                name.ifBlank { stringResource(Res.string.shell_kbdint_title) },
                color = Skerry.colors.text,
                size = 16.sp,
                weight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
            )
            if (challenge.hop) {
                Txt(
                    stringResource(Res.string.shell_kbdint_jump),
                    color = Skerry.colors.dim,
                    size = 12.5.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val instruction = sanitizeServerText(challenge.instruction, MAX_INSTRUCTION_CHARS)
            if (instruction.isNotBlank()) {
                Txt(
                    instruction,
                    color = Skerry.colors.dim,
                    size = 12.5.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            challenge.prompts.forEachIndexed { index, prompt ->
                val label = sanitizeServerText(prompt.text, MAX_PROMPT_CHARS)
                Txt(
                    label,
                    color = Skerry.colors.faint,
                    size = 11.sp,
                    font = LocalFonts.current.mono,
                    modifier = Modifier.padding(top = 16.dp, bottom = 5.dp),
                )
                PromptField(
                    value = answers[index],
                    onValueChange = { answers[index] = it },
                    masked = !prompt.echo,
                    last = index == challenge.prompts.lastIndex,
                    onSubmit = submit,
                    modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CancelButton(stringResource(Res.string.shell_cancel), onClick = onDismiss)
                PrimaryButton(stringResource(Res.string.shell_kbdint_continue), onClick = submit)
            }
        }
    }
}

/**
 * Renders the pending challenge, if any, wherever it is placed — one call at the root of each
 * platform's chrome. Kept separate from the dialog so both shells share the wiring instead of
 * repeating the collect/submit/dismiss dance.
 */
@Composable
fun KeyboardInteractiveHost(controller: KeyboardInteractivePromptController?) {
    val request by (controller?.pending ?: return).collectAsState()
    val pending = request ?: return
    KeyboardInteractiveDialog(
        challenge = pending.challenge,
        onDismiss = { controller.dismiss(pending.id) },
        onSubmit = { answers -> controller.submit(answers, pending.id) },
    )
}

@Composable
private fun PromptField(
    value: String,
    onValueChange: (String) -> Unit,
    masked: Boolean,
    last: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = LocalFonts.current.ui
    val textColor = Skerry.colors.text
    val style = remember(ui, textColor) { TextStyle(color = textColor, fontSize = 13.sp, fontFamily = ui) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(Skerry.colors.cyan),
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        // Password keyboard type even for an echoed prompt: it keeps the IME from autocorrecting or
        // remembering a one-time code in its dictionary.
        keyboardOptions = KeyboardOptions(
            imeAction = if (last) ImeAction.Done else ImeAction.Next,
            keyboardType = KeyboardType.Password,
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Skerry.colors.bg)
                    .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp))
                    .padding(horizontal = 11.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Sym(if (masked) "key" else "keyboard", size = 16.sp, color = Skerry.colors.faint)
                Box(Modifier.weight(1f)) { inner() }
            }
        },
    )
}

private const val MAX_TITLE_CHARS = 120
private const val MAX_INSTRUCTION_CHARS = 600
private const val MAX_PROMPT_CHARS = 200

/**
 * Makes server-supplied text safe to render: drops control characters (including the escape
 * sequences a terminal would act on and the bidi overrides that could reorder the sentence), keeps
 * newlines only inside longer blocks, collapses runs of blank lines and caps the length.
 *
 * Truncation is silent rather than marked with an ellipsis — the cap is generous enough that only a
 * server trying to flood the dialog reaches it.
 */
internal fun sanitizeServerText(text: String, maxChars: Int): String {
    val allowNewlines = maxChars > MAX_PROMPT_CHARS
    val cleaned = buildString(minOf(text.length, maxChars)) {
        for (ch in text) {
            if (length >= maxChars) break
            when {
                ch == '\n' && allowNewlines -> if (isNotEmpty() && last() != '\n') append(ch)
                ch == '\t' -> if (isNotEmpty() && last() != ' ') append(' ')
                ch.isISOControl() -> Unit
                // Bidi overrides/isolates: they can visually reverse the text around them.
                ch in '\u202A'..'\u202E' || ch in '\u2066'..'\u2069' -> Unit
                ch == '\uFEFF' -> Unit
                else -> append(ch)
            }
        }
    }
    return cleaned.trim()
}
