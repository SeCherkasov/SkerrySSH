package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.host.NewConnectionFormState

/** Панель листа (`#0E1B26`); фон полей ввода — `#07141E` = [D.bg]. */
private val SheetPanel = Color(0xFF0E1B26)

/**
 * Нижний лист «New connection» мобильного макета `Skerry Mobile.html`: затемнение + панель снизу с
 * формой профиля хоста. С живым [LocalHosts] (за гейтом vault) Save создаёт профиль через
 * [app.skerry.ui.host.HostManagerController] и закрывает лист; без него (превью) Save просто
 * закрывает. Переиспользует общий [NewConnectionFormState] (как desktop-модалка).
 *
 * Authentication — визуальная заглушка (привязка identity — отдельный слайс, как на desktop);
 * AI-политика спрятана за фича-флагом [FeatureFlags.ai] (Phase 2). Сохраняется базовый профиль.
 */
@Composable
fun MobileNewConnectionSheet(state: MobileDesignState) {
    val hosts = LocalHosts.current
    val form = remember { NewConnectionFormState() }
    val canSave = hosts == null || form.canSave
    val onSave = {
        if (hosts != null && form.canSave) {
            hosts.save(form.toDraft())
            state.closeSheet()
        } else if (hosts == null) {
            state.closeSheet()
        }
    }

    // Затемнение на весь экран — тап мимо панели закрывает лист.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x8C04080C))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = state::closeSheet),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Панель: перехватывает тап (не закрывается), скроллится, прижата к низу.
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(SheetPanel)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 30.dp),
        ) {
            Box(
                Modifier
                    .padding(bottom = 16.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 38.dp, height = 5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0x2EFFFFFF)),
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt("New connection", color = D.text, size = 20.sp, weight = FontWeight.Bold)
                Sym(
                    "close",
                    size = 24.sp,
                    color = D.dim,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = state::closeSheet,
                    ),
                )
            }
            Txt(
                "Credentials are encrypted with your master password and stay on this device.",
                color = D.dim,
                size = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
            )

            SheetField("Name") { SheetInput(form.name, { form.name = it }, "prod-web-01") }
            Spacer(Modifier.height(14.dp))
            SheetField("Host address") { SheetInput(form.address, { form.address = it }, "192.168.1.45") }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetField("Username", Modifier.weight(1f)) {
                    SheetInput(form.username, { form.username = it }, "root")
                }
                SheetField("Port", Modifier.width(84.dp)) {
                    SheetInput(form.port, { form.port = it }, "22", keyboardType = KeyboardType.Number)
                }
            }
            Spacer(Modifier.height(14.dp))
            // Authentication — заглушка-селект (привязка identity отдельным слайсом, как на desktop).
            SheetField("Authentication") { SheetSelect("SSH key · id_ed25519") }

            if (LocalFeatures.current.ai) {
                Spacer(Modifier.height(14.dp))
                SheetField("AI policy") { AiPolicyPills() }
            }

            Spacer(Modifier.height(22.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (canSave) D.cyan else D.cyan.copy(alpha = 0.4f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSave)
                    .padding(15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt("Save connection", color = Color(0xFF0A1A26), size = 16.sp, weight = FontWeight.Bold)
            }
        }
    }
}

/** Подпись поля (капс) + содержимое. */
@Composable
private fun SheetField(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Txt(
            label.uppercase(),
            color = D.faint,
            size = 10.5.sp,
            weight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        content()
    }
}

/** Текстовое поле листа в стиле макета (тёмный фон + cyan-рамка, радиус 11). */
@Composable
private fun SheetInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val ui = LocalFonts.current.ui
    val textStyle = remember(ui) { TextStyle(color = D.text, fontSize = 15.sp, fontFamily = ui) }
    // Рамка/паддинг — в decorationBox, чтобы клик по всей площади поля ставил каретку.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(D.cyan),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(D.bg)
                    .border(1.dp, D.cyan14, RoundedCornerShape(11.dp))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 15.sp)
                inner()
            }
        },
    )
}

/** Заглушка-селект (значение + шеврон) в стиле полей листа. */
@Composable
private fun SheetSelect(value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(D.bg)
            .border(1.dp, D.cyan14, RoundedCornerShape(11.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(value, color = D.text, size = 15.sp)
        Sym("expand_more", size = 20.sp, color = D.faint)
    }
}

/** Пилюли AI-политики (Strict/Balanced/Off) — визуальный выбор за фича-флагом (Phase 2). */
@Composable
private fun AiPolicyPills() {
    var selected by remember { mutableStateOf("Strict") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Strict", "Balanced", "Off").forEach { name ->
            val on = name == selected
            val onPick = remember(name) { { selected = name } }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) D.cyan.copy(alpha = 0.1f) else Color.Transparent)
                    .border(1.dp, if (on) D.cyan else D.cyan.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember(name) { MutableInteractionSource() },
                        indication = null,
                        onClick = onPick,
                    )
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(
                    name,
                    color = if (on) D.cyanBright else D.dim,
                    size = 12.5.sp,
                    weight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
