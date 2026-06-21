package app.skerry.ui.design

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.host.NewConnectionFormState

/**
 * Модалка «New connection»: форма профиля хоста + выбор AI-политики. С живым [LocalHosts]
 * (за гейтом vault) Save создаёт профиль через [app.skerry.ui.host.HostManagerController] и
 * выделяет его в сайдбаре; без него (мок/превью) Save просто закрывает модалку. Поля
 * authentication/jump/keep-alive/tags/AI-политика — пока визуальные заглушки (отдельные слайсы);
 * сохраняется базовый профиль ([NewConnectionFormState]).
 */
@Composable
fun NewConnectionModal(state: DesktopDesignState) {
    val noop = remember { MutableInteractionSource() }
    val hosts = LocalHosts.current
    val form = remember { NewConnectionFormState() }
    Box(
        Modifier.fillMaxSize().background(Color(0xB3060E16)).clickable(interactionSource = noop, indication = null, onClick = state::closeModal),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .heightIn(max = 720.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(D.surfaceDeep)
                .border(1.dp, D.cyan14, RoundedCornerShape(12.dp))
                .clickable(interactionSource = noop, indication = null, onClick = {}),
        ) {
            Box(Modifier.fillMaxWidth().padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 14.dp)) {
                Column {
                    Txt("New connection", color = D.text, size = 18.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
                    Txt("Configure a new skerry in your archipelago. Credentials are encrypted with your master password.", color = D.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
                }
                IconBtn("close", onClick = state::closeModal, modifier = Modifier.align(Alignment.TopEnd))
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 26.dp, end = 26.dp, top = 6.dp, bottom = 22.dp)) {
                Field("Name") { ModalTextField(form.name, { form.name = it }, "e.g. prod-web-01") }
                Spacer14()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field("Host address", Modifier.weight(1f)) {
                        ModalTextField(form.address, { form.address = it }, "192.168.1.45 or example.com", icon = "dns")
                    }
                    Field("Port", Modifier.width(110.dp)) {
                        ModalTextField(form.port, { form.port = it }, "22", keyboardType = KeyboardType.Number)
                    }
                }
                Spacer14()
                Field("Username") { ModalTextField(form.username, { form.username = it }, "root or username", icon = "person") }
                Spacer14()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field("Authentication", Modifier.weight(1f)) { ModalSelect("SSH key (Ed25519)") }
                    Field("Group", Modifier.weight(1f)) { ModalTextField(form.group, { form.group = it }, "Production (optional)") }
                }
                Spacer14()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field("Jump host (optional)", Modifier.weight(1f)) { ModalSelect("None — direct") }
                    Field("Keep-alive", Modifier.weight(1f)) { ModalSelect("Every 30s") }
                }
                Spacer14()
                Field("Tags") {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TagPill("#prod")
                        TagPill("#docker")
                        Txt("add tag…", color = D.faint, size = 12.5.sp)
                    }
                }
                // Выбор AI-политики — фича MVP2 за фича-флагом; в MVP1 (дефолт) его в форме нет.
                if (LocalFeatures.current.ai) {
                    Spacer14()
                    Field("AI policy for this connection") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            POLICY_OPTIONS.forEach { opt ->
                                PolicyRow(opt, selected = state.modalPolicy == opt.policy, onClick = { state.choosePolicy(opt.policy) })
                            }
                        }
                    }
                }
            }
            HLine()
            Row(
                Modifier.fillMaxWidth().background(Color(0x26000000)).padding(horizontal = 26.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sym("shield_lock", size = 14.sp, color = D.moss)
                    Txt("Encrypted · stays on this device", color = D.faint, size = 11.sp)
                }
                Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = state::closeModal).padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Txt("Cancel", color = D.dim, size = 12.5.sp)
                }
                GhostButton("Test", onClick = {})
                PrimaryButton(
                    "Save",
                    onClick = {
                        if (hosts == null) {
                            state.closeModal() // мок/превью: сохранять некуда
                        } else if (form.canSave) {
                            state.selectHost(hosts.save(form.toDraft()))
                            state.closeModal()
                        }
                    },
                    bg = if (hosts == null || form.canSave) D.cyan else D.cyan.copy(alpha = 0.4f),
                )
            }
        }
    }
}

@Composable
private fun Spacer14() = Box(Modifier.size(14.dp))

@Composable
private fun Field(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Txt(label.uppercase(), color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(bottom = 5.dp))
        content()
    }
}

/** Редактируемое текстовое поле формы (опц. иконка слева): стиль макета + плейсхолдер. */
@Composable
private fun ModalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Sym(icon, size = 16.sp, color = D.faint)
        val ui = LocalFonts.current.ui
        val textStyle = remember(ui) { TextStyle(color = D.text, fontSize = 13.sp, fontFamily = ui) }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 13.sp)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(D.cyan),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            )
        }
    }
}

@Composable
private fun ModalSelect(value: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(value, color = D.text, size = 13.sp)
        Sym("expand_more", size = 16.sp, color = D.faint)
    }
}

@Composable
private fun TagPill(text: String) {
    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(D.cyan.copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 2.dp)) {
        Txt(text, color = D.cyanBright, size = 11.sp)
    }
}

@Composable
private fun PolicyRow(opt: PolicyOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) D.cyan10 else Color.Transparent)
            .border(1.dp, if (selected) D.cyan else D.cyan06, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(16.dp).clip(CircleShape).border(1.5.dp, if (selected) D.cyan else D.faint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(D.cyan))
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Sym(opt.icon, size = 14.sp, color = D.dim)
                Txt(opt.title, color = D.text, size = 13.sp, weight = FontWeight.Medium)
            }
            Txt(opt.desc, color = D.dim, size = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
