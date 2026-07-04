package app.skerry.ui.teams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.D
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Txt
import app.skerry.ui.design.consumeClicks
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_create_subtitle
import app.skerry.ui.generated.resources.lib_teams_create_title
import app.skerry.ui.generated.resources.lib_teams_invite_account_placeholder
import app.skerry.ui.generated.resources.lib_teams_invite_fingerprint
import app.skerry.ui.generated.resources.lib_teams_invite_next
import app.skerry.ui.generated.resources.lib_teams_invite_send
import app.skerry.ui.generated.resources.lib_teams_invite_subtitle
import app.skerry.ui.generated.resources.lib_teams_invite_title
import app.skerry.ui.generated.resources.lib_teams_invite_verify
import app.skerry.ui.generated.resources.lib_teams_name_placeholder
import app.skerry.ui.generated.resources.lib_teams_your_fingerprint
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.generated.resources.shell_create
import org.jetbrains.compose.resources.stringResource

/** Элемент пикера шеринга (хост или сниппет своего vault): [detail] — вторая строка (адрес/команда). */
data class ShareItem(val id: String, val label: String, val detail: String)

/** Карточка диалога Teams — тот же визуальный язык, что [app.skerry.ui.design.ConfirmActionDialog]. */
@Composable
private fun TeamsDialogCard(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalScrim(onDismiss = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                // Поднять карточку над экранной клавиатурой (поля имени/аккаунта иначе перекрыты IME);
                // на desktop — no-op. Идёт ДО clip/background, чтобы двигать карточку, а не раздувать рамку.
                .imePadding()
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(D.surfaceDeep)
                .border(1.dp, D.cyan14, RoundedCornerShape(12.dp))
                .consumeClicks()
                .padding(26.dp),
        ) { content() }
    }
}

/** Рукописное поле диалога: рамка в decorationBox + fillMaxWidth (клик по всей площади ставит каретку). */
@Composable
private fun TeamsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onDone: () -> Unit,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = D.text, fontSize = 13.sp, fontFamily = LocalFonts.current.ui),
        cursorBrush = SolidColor(D.cyan),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth().focusRequester(focus),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(7.dp))
                    .background(D.card)
                    .border(1.dp, D.line, RoundedCornerShape(7.dp))
                    .padding(horizontal = 11.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.fillMaxWidth()) {
                    if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 13.sp)
                    inner()
                }
            }
        },
    )
}

@Composable
private fun CancelButton(onDismiss: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 9.dp)) {
        Txt(stringResource(Res.string.shell_cancel), color = D.dim, size = 12.5.sp)
    }
}

/** Создание команды: одно поле имени; ключ команды генерируется локально (подпись напоминает об этом). */
@Composable
fun CreateTeamDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun save() {
        if (name.trim().isNotEmpty()) onCreate(name.trim())
    }
    TeamsDialogCard(onDismiss) {
        Txt(stringResource(Res.string.lib_teams_create_title), color = D.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
        Txt(stringResource(Res.string.lib_teams_create_subtitle), color = D.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        TeamsTextField(name, { name = it }, stringResource(Res.string.lib_teams_name_placeholder), ::save, focus)
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CancelButton(onDismiss)
            PrimaryButton(stringResource(Res.string.shell_create), onClick = ::save, enabled = name.trim().isNotEmpty())
        }
    }
}

/**
 * Приглашение в два шага: ввод accountId → [onLookup] тянет опубликованный ключ; когда [preview]
 * пришёл — показываем фингерпринт для сверки по доверенному каналу и только тогда даём отправить.
 * Смена введённого id обнуляет превью через [onEdited] (нельзя отправить на несверенный ключ).
 */
@Composable
fun InviteMemberDialog(
    preview: InvitePreview?,
    ownFingerprint: String?,
    busy: Boolean,
    onLookup: (String) -> Unit,
    onEdited: () -> Unit,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var accountId by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val mono = LocalFonts.current.mono
    val ready = preview != null && preview.accountId == accountId.trim()
    fun submit() {
        val id = accountId.trim()
        if (id.isEmpty() || busy) return
        if (ready) onSend(id) else onLookup(id)
    }
    TeamsDialogCard(onDismiss) {
        Txt(stringResource(Res.string.lib_teams_invite_title), color = D.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
        Txt(stringResource(Res.string.lib_teams_invite_subtitle), color = D.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        TeamsTextField(accountId, { accountId = it; onEdited() }, stringResource(Res.string.lib_teams_invite_account_placeholder), ::submit, focus)
        if (ready && preview != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(D.cyan.copy(alpha = 0.06f))
                    .border(1.dp, D.cyan14, RoundedCornerShape(7.dp))
                    .padding(12.dp),
            ) {
                Txt(stringResource(Res.string.lib_teams_invite_fingerprint).uppercase(), color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                Txt(preview.fingerprint, color = D.cyanBright, size = 14.sp, font = mono, modifier = Modifier.padding(top = 4.dp))
                Txt(stringResource(Res.string.lib_teams_invite_verify), color = D.dim, size = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))
                if (ownFingerprint != null) {
                    Txt(stringResource(Res.string.lib_teams_your_fingerprint, ownFingerprint), color = D.faint, size = 11.sp, font = mono, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CancelButton(onDismiss)
            PrimaryButton(
                if (ready) stringResource(Res.string.lib_teams_invite_send) else stringResource(Res.string.lib_teams_invite_next),
                onClick = ::submit,
                enabled = accountId.trim().isNotEmpty() && !busy,
            )
        }
    }
}

/** Пикер записи своего vault для шеринга с командой: уже общие записи вызывающий отфильтровал. */
@Composable
fun SharePickerDialog(
    title: String,
    items: List<ShareItem>,
    emptyText: String,
    onPick: (ShareItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val mono = LocalFonts.current.mono
    TeamsDialogCard(onDismiss) {
        Txt(title, color = D.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, modifier = Modifier.padding(bottom = 14.dp))
        if (items.isEmpty()) {
            Txt(emptyText, color = D.dim, size = 12.5.sp)
        } else {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .border(1.dp, D.cyan08, RoundedCornerShape(7.dp))
                            .clickable { onPick(item) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Txt(item.label, color = D.textBright, size = 12.5.sp, modifier = Modifier.weight(1f))
                        Txt(item.detail, color = D.faint, size = 11.sp, font = mono)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            CancelButton(onDismiss)
        }
    }
}
