package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ai.AiRole
import app.skerry.ui.ai.AiAssistantController
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.more_ai_ask
import app.skerry.ui.generated.resources.more_ai_byok_desc
import app.skerry.ui.generated.resources.more_ai_clear
import app.skerry.ui.generated.resources.more_ai_input_placeholder_ready
import app.skerry.ui.generated.resources.more_ai_input_placeholder_setup
import app.skerry.ui.generated.resources.more_ai_key_saved
import app.skerry.ui.generated.resources.more_ai_label_api_key
import app.skerry.ui.generated.resources.more_ai_label_endpoint
import app.skerry.ui.generated.resources.more_ai_label_model
import app.skerry.ui.generated.resources.more_ai_not_configured
import app.skerry.ui.generated.resources.more_ai_placeholder_api_key
import app.skerry.ui.generated.resources.more_ai_placeholder_endpoint
import app.skerry.ui.generated.resources.more_ai_placeholder_model
import app.skerry.ui.generated.resources.more_ai_privacy
import app.skerry.ui.generated.resources.more_ai_quick_chat
import app.skerry.ui.generated.resources.more_ai_quick_chat_desc
import app.skerry.ui.generated.resources.more_ai_save
import app.skerry.ui.generated.resources.more_ai_sending
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.D
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt

/**
 * Мобильный экран настроек AI (More → «AI & privacy») — паритет с desktop `LiveAiSection`:
 * BYOK-поля (ключ/модель/endpoint) + Save в vault + Quick chat для проверки связи. Без живого
 * контроллера ([LocalAi] == null) не открывается (строка More инертна), поэтому здесь `ai` не-null.
 */
@Composable
fun MobileAiScreen(state: MobileDesignState) {
    val ai = LocalAi.current
    Column(Modifier.fillMaxSize().background(D.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Sym("chevron_left", size = 27.sp, color = D.cyanBright, modifier = Modifier.clickable(onClick = state::pop))
            Txt(stringResource(Res.string.more_ai_privacy), color = D.text, size = 18.sp, weight = FontWeight.Bold)
        }
        if (ai == null) return@Column
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Txt(
                stringResource(Res.string.more_ai_byok_desc),
                color = D.dim, size = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 12.dp),
            )

            var key by remember(ai.settings) { mutableStateOf(ai.settings.apiKey) }
            var model by remember(ai.settings) { mutableStateOf(ai.settings.model) }
            var baseUrl by remember(ai.settings) { mutableStateOf(ai.settings.baseUrl) }

            AiFieldLabel(stringResource(Res.string.more_ai_label_api_key))
            AiTextField(key, stringResource(Res.string.more_ai_placeholder_api_key), KeyboardType.Password, ImeAction.Next, secret = true) { key = it }
            AiFieldLabel(stringResource(Res.string.more_ai_label_model))
            AiTextField(model, stringResource(Res.string.more_ai_placeholder_model), KeyboardType.Text, ImeAction.Next) { model = it }
            AiFieldLabel(stringResource(Res.string.more_ai_label_endpoint))
            AiTextField(baseUrl, stringResource(Res.string.more_ai_placeholder_endpoint), KeyboardType.Uri, ImeAction.Done) { baseUrl = it }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AiChip(stringResource(Res.string.more_ai_save), D.cyan) { ai.save(key, model, baseUrl) }
                if (ai.isConfigured) Txt(stringResource(Res.string.more_ai_key_saved), color = D.moss, size = 11.5.sp)
                else Txt(stringResource(Res.string.more_ai_not_configured), color = D.faint, size = 11.5.sp)
            }

            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(D.line))
            Spacer(Modifier.height(14.dp))
            Txt(stringResource(Res.string.more_ai_quick_chat), color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt(stringResource(Res.string.more_ai_quick_chat_desc), color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))

            ai.turns.forEach { turn -> AiChatBubble(turn.role, turn.text) }
            ai.streaming?.let { AiChatBubble(AiRole.ASSISTANT, if (it.isEmpty()) "…" else it) }
            ai.error?.let { Txt(it, color = D.sunset, size = 12.sp, modifier = Modifier.padding(vertical = 6.dp)) }

            Spacer(Modifier.height(8.dp))
            var prompt by remember { mutableStateOf("") }
            val send = { if (prompt.isNotBlank() && !ai.busy) { ai.ask(prompt); prompt = "" } }
            AiTextField(
                value = prompt,
                placeholder = if (ai.isConfigured) stringResource(Res.string.more_ai_input_placeholder_ready) else stringResource(Res.string.more_ai_input_placeholder_setup),
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Send,
                onSubmit = send,
            ) { prompt = it }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AiChip(if (ai.busy) stringResource(Res.string.more_ai_sending) else stringResource(Res.string.more_ai_ask), if (ai.isConfigured && !ai.busy) D.cyan else D.faint) { send() }
                if (ai.turns.isNotEmpty()) AiChip(stringResource(Res.string.more_ai_clear), D.dim) { ai.clearConversation() }
            }
            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
private fun AiFieldLabel(text: String) {
    Txt(text, color = D.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
}

@Composable
private fun AiTextField(
    value: String,
    placeholder: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    secret: Boolean = false,
    onSubmit: (() -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x0AFFFFFF))
            .border(1.dp, D.line, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 13.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = D.text, fontSize = 13.sp),
            cursorBrush = SolidColor(D.cyan),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onDone = { onSubmit?.invoke() },
                onSend = { onSubmit?.invoke() },
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AiChip(label: String, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(fg.copy(alpha = 0.12f))
            .border(1.dp, fg.copy(alpha = 0.4f), RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Txt(label, color = fg, size = 12.5.sp, weight = FontWeight.Medium)
    }
}

@Composable
private fun AiChatBubble(role: AiRole, text: String) {
    val mine = role == AiRole.USER
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier.clip(RoundedCornerShape(8.dp))
                .background(if (mine) D.cyan.copy(alpha = 0.10f) else Color(0x0DFFFFFF))
                .border(1.dp, if (mine) D.cyan.copy(alpha = 0.14f) else D.line, RoundedCornerShape(8.dp))
                .padding(horizontal = 11.dp, vertical = 8.dp),
        ) {
            Txt(text, color = if (mine) D.text else D.dim, size = 12.5.sp, lineHeight = 18.sp)
        }
    }
}
