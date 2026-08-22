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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import app.skerry.ui.design.rememberPromptFocus
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.sanitizeServerText
import app.skerry.ui.design.Txt
import app.skerry.ui.design.fieldName
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.generated.resources.shell_kbdint_asks
import app.skerry.ui.generated.resources.shell_kbdint_continue
import app.skerry.ui.generated.resources.shell_kbdint_jump
import app.skerry.ui.generated.resources.shell_kbdint_title
import app.skerry.ui.nav.PlatformBackHandler
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.platform.testTag
import app.skerry.ui.app.UiTags

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
    requestId: Long,
    challenge: KeyboardInteractiveChallenge,
    onDismiss: () -> Unit,
    onSubmit: (List<String>) -> Unit,
) {
    val noop = remember { MutableInteractionSource() }
    // Keyed on the request, not the challenge: a server re-asking after a wrong code sends the very
    // same name/instruction/prompt, so a challenge-keyed remember would keep the rejected answer in
    // the field and skip re-focusing — the user would resubmit the code that just failed.
    val answers = remember(requestId) { mutableStateListOf(*Array(challenge.prompts.size) { "" }) }
    val focus = remember(requestId) { FocusRequester() }
    // Keyed too: an unkeyed scroll position outlives the dialog it was scrolled in, so the next
    // challenge opens part-way down and its first line is off screen.
    val scroll = remember(requestId) { ScrollState(0) }
    val submit = { onSubmit(answers.toList()) }

    // Registered as a modal, holding the caret and drawn where the caret is — see
    // [rememberPromptFocus]: a 2FA answer must not be typed into the session waiting underneath, nor
    // into a prompt for another host that opened over this one.
    val prompt = rememberPromptFocus(focus, requestId)
    PlatformBackHandler(onBack = onDismiss)

    Box(
        prompt.fillMaxSize().background(Skerry.colors.modalScrim)
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
                .verticalScroll(scroll)
                .padding(26.dp),
        ) {
            // Our own heading, never the server's: the wording below is written by whatever host was
            // dialed, and a prompt that looked like Skerry's own chrome could ask for the vault
            // password and be believed. The title says who is asking, the server's text sits under a
            // caption that marks it as theirs.
            Txt(
                stringResource(Res.string.shell_kbdint_title),
                color = Skerry.colors.text,
                size = 16.sp,
                weight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
            )
            if (challenge.endpoint.isNotBlank()) {
                Txt(
                    sanitizeServerText(challenge.endpoint, MAX_TITLE_CHARS, allowNewlines = false),
                    color = Skerry.colors.dim,
                    size = 12.5.sp,
                    font = LocalFonts.current.mono,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (challenge.hop) {
                Txt(
                    stringResource(Res.string.shell_kbdint_jump),
                    color = Skerry.colors.dim,
                    size = 12.5.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            val name = sanitizeServerText(challenge.name, MAX_TITLE_CHARS, allowNewlines = false)
            val instruction = sanitizeServerText(challenge.instruction, MAX_INSTRUCTION_CHARS, allowNewlines = true)
            // Drawn whatever the server sent, not only when it filled in a name or an instruction:
            // everything below this line is the host's own wording, prompt captions included. Left
            // conditional, a server that sends nothing but a prompt gets to caption the input box
            // itself — "Skerry vault master password:" over a field whose answer it receives.
            Txt(
                stringResource(Res.string.shell_kbdint_asks),
                color = Skerry.colors.faint,
                size = 10.5.sp,
                weight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
                modifier = Modifier.padding(top = 14.dp, bottom = 5.dp),
            )
            if (name.isNotBlank() || instruction.isNotBlank()) {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(7.dp))
                        .background(Skerry.colors.bg)
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                ) {
                    if (name.isNotBlank()) {
                        Txt(name, color = Skerry.colors.text, size = 12.5.sp, lineHeight = 18.sp)
                    }
                    if (instruction.isNotBlank()) {
                        Txt(
                            instruction,
                            color = Skerry.colors.dim,
                            size = 12.5.sp,
                            lineHeight = 18.sp,
                            modifier = if (name.isBlank()) Modifier else Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }

            challenge.prompts.forEachIndexed { index, prompt ->
                val label = sanitizeServerText(prompt.text, MAX_PROMPT_CHARS, allowNewlines = false)
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
                    label = label,
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
                CancelButton(stringResource(Res.string.shell_cancel), onClick = onDismiss, modifier = Modifier.testTag(UiTags.FORM_CANCEL))
                PrimaryButton(stringResource(Res.string.shell_kbdint_continue), onClick = submit, modifier = Modifier.testTag(UiTags.FORM_SAVE))
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
        requestId = pending.id,
        challenge = pending.challenge,
        onDismiss = { controller.dismiss(pending.id) },
        onSubmit = { answers -> controller.submit(answers, pending.id) },
    )
}

@Composable
private fun PromptField(
    value: String,
    onValueChange: (String) -> Unit,
    /** The server's own prompt text, already sanitized — this field's only caption, and its name. */
    label: String,
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
        // One tag per prompt row: a challenge can carry several, in server order. The tag tells them
        // apart by index only — the name is what tells a screen-reader user which prompt this is.
        // Prefixed with our own "the host is asking" caption: the words are the server's, and a
        // bare prompt as the field's name would be indistinguishable from a label Skerry wrote.
        modifier = modifier.fillMaxWidth()
            .fieldName(fallback = "${stringResource(Res.string.shell_kbdint_asks)} $label")
            .testTag(UiTags.FORM_FIELD),
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
