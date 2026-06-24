package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import app.skerry.ui.host.AuthMode
import app.skerry.ui.host.NewConnectionFormState

/** Панель листа (`#0E1B26`); фон полей ввода — `#07141E` = [D.bg]. */
private val SheetPanel = Color(0xFF0E1B26)

/**
 * Нижний лист «New connection» мобильного макета `Skerry Mobile.html`: затемнение + панель снизу с
 * формой профиля хоста. С живым [LocalHosts] (за гейтом vault) Save создаёт профиль через
 * [app.skerry.ui.host.HostManagerController] и закрывает лист; без него (превью) Save просто
 * закрывает. Переиспользует общий [NewConnectionFormState] (как desktop-модалка).
 *
 * Authentication — живой [MobileAuthPicker] (паритет desktop `AuthPicker`): Ask / новый пароль /
 * новый ключ / уже сохранённый keychain-секрет из [LocalCredentials]. Новый секрет запечатывается
 * в открытый vault и привязывается к хосту через [NewConnectionFormState.resolveCredentialId];
 * AI-политика спрятана за фича-флагом [FeatureFlags.ai] (Phase 2).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MobileNewConnectionSheet(state: MobileDesignState) {
    val hosts = LocalHosts.current
    val credentials = LocalCredentials.current
    val form = remember { NewConnectionFormState() }
    val canSave = hosts == null || form.canSave
    // Гард повторного Save (двойной тап) до закрытия листа — иначе дубль секрета+хоста в vault (как desktop).
    var submitting by remember { mutableStateOf(false) }
    // Незакоммиченный ввод тега (пилюля ещё не создана); Save дофиксирует его, чтобы не потерялся.
    var tagDraft by remember { mutableStateOf("") }
    val onSave = {
        if (submitting) {
            // повторное нажатие до закрытия — игнорируем
        } else if (hosts == null) {
            state.closeSheet() // мок/превью: сохранять некуда
        } else if (form.canSave) {
            submitting = true
            if (tagDraft.isNotBlank()) { form.addTag(tagDraft); tagDraft = "" }
            // Новый секрет создаём только при живом keychain (иначе он осел бы в vault без ссылки на хост);
            // ASK/мок-путь → credentialId = null. EXISTING-привязка возвращается как есть (не пересоздаём).
            val credentialId = form.resolveCredentialId(saveCredential = { draft -> credentials?.save(draft) })
            hosts.save(form.toDraft(credentialId = credentialId))
            // Секрет уже запечатан в vault — снимаем ссылки на него из state формы, сокращая окно
            // жизни ключа/пароля в куче (String на JVM не занулить на месте, но ссылку убираем).
            form.password = ""; form.privateKeyPem = ""; form.passphrase = ""
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
            SheetField("Authentication") { MobileAuthPicker(form) }
            Spacer(Modifier.height(14.dp))
            SheetField("Tags") {
                SheetTags(
                    tags = form.tags,
                    onRemove = { form.removeTag(it) },
                    draft = tagDraft,
                    // Запятая фиксирует тег(и) сразу; одиночный тег — по Enter (onCommit).
                    onDraftChange = { v -> if (v.contains(',')) { form.addTag(v); tagDraft = "" } else tagDraft = v },
                    onCommit = { form.addTag(tagDraft); tagDraft = "" },
                )
            }

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

/**
 * Текстовое поле листа в стиле макета (тёмный фон + cyan-рамка, радиус 11).
 * [masked] — скрывать ввод (пароль/passphrase); [singleLine] = false + [mono] + [minHeightDp] —
 * многострочная моноширинная область для вставки приватного ключа (PEM), как desktop `ModalTextField`.
 */
@Composable
private fun SheetInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    masked: Boolean = false,
    singleLine: Boolean = true,
    mono: Boolean = false,
    minHeightDp: Int? = null,
) {
    val fonts = LocalFonts.current
    val family = if (mono) fonts.mono else fonts.ui
    val fontSize = if (mono) 12.5.sp else 15.sp
    val textStyle = remember(family, fontSize) {
        TextStyle(color = D.text, fontSize = fontSize, fontFamily = family, lineHeight = if (mono) 17.sp else 20.sp)
    }
    // Рамка/паддинг — в decorationBox, чтобы клик по всей площади поля ставил каретку.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = textStyle,
        cursorBrush = SolidColor(D.cyan),
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (masked) KeyboardType.Password else keyboardType),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (minHeightDp != null) Modifier.heightIn(min = minHeightDp.dp) else Modifier)
                    .clip(RoundedCornerShape(11.dp))
                    .background(D.bg)
                    .border(1.dp, D.cyan14, RoundedCornerShape(11.dp))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (value.isEmpty()) Txt(placeholder, color = D.faint, size = fontSize, font = if (mono) fonts.mono else null)
                inner()
            }
        },
    )
}

/**
 * Редактор тегов в стиле листа (паритет desktop-блока Tags): пилюли `#tag` с крестиком + инлайн-ввод
 * нового тега (Enter/запятая фиксирует пилюлю). [tags] — каноническая форма, на экране с префиксом `#`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetTags(
    tags: List<String>,
    onRemove: (String) -> Unit,
    draft: String,
    onDraftChange: (String) -> Unit,
    onCommit: () -> Unit,
) {
    val fonts = LocalFonts.current
    val textStyle = remember(fonts.ui) { TextStyle(color = D.text, fontSize = 14.sp, fontFamily = fonts.ui) }
    FlowRow(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(D.bg).border(1.dp, D.cyan14, RoundedCornerShape(11.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        tags.forEach { tag ->
            key(tag) {
                Row(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(D.cyan.copy(alpha = 0.12f)).padding(start = 10.dp, end = 5.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Txt("#$tag", color = D.cyanBright, size = 12.5.sp)
                    Box(
                        Modifier.clip(CircleShape).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onRemove(tag) }.padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Sym("close", size = 14.sp, color = D.cyanBright)
                    }
                }
            }
        }
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(D.cyan),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit() }),
            modifier = Modifier.widthIn(min = 90.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (draft.isEmpty()) Txt("add tag…", color = D.faint, size = 14.sp)
                    inner()
                }
            },
        )
    }
}

/**
 * Выбор аутентификации хоста в стиле мобильного листа (паритет desktop `AuthPicker`): селект-триггер
 * раскрывает варианты — Ask every time / новый пароль / новый ключ / уже сохранённые keychain-секреты
 * из живого [LocalCredentials] — плюс инлайн-поля под новый секрет. В мок-пути (без vault) остаются
 * только варианты без сохранения.
 */
@Composable
private fun MobileAuthPicker(form: NewConnectionFormState) {
    val credentials = LocalCredentials.current
    val saved = credentials?.credentials ?: emptyList()
    var menuOpen by remember { mutableStateOf(false) }
    val selectedLabel = when (form.authMode) {
        AuthMode.ASK -> "Ask every time"
        AuthMode.NEW_PASSWORD -> "Password"
        AuthMode.NEW_KEY -> "Private key"
        AuthMode.EXISTING -> saved.firstOrNull { it.id == form.existingCredentialId }?.let { "${it.label} (saved)" } ?: "Select credential…"
    }
    Column {
        AnchoredDropdown(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            trigger = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(D.bg)
                        .border(1.dp, D.cyan14, RoundedCornerShape(11.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menuOpen = !menuOpen }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Txt(selectedLabel, color = D.text, size = 15.sp)
                    Sym(if (menuOpen) "expand_less" else "expand_more", size = 20.sp, color = D.faint)
                }
            },
            menu = { width ->
                // Настоящий dropdown поверх листа (Popup), а не раздвигание формы; ширина = триггеру, скролл при переполнении.
                Column(
                    Modifier
                        .width(width)
                        .clip(RoundedCornerShape(11.dp))
                        .background(SheetPanel)
                        .border(1.dp, D.cyan14, RoundedCornerShape(11.dp))
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                ) {
                    MobileAuthOption("vpn_key_off", "Ask every time", "password requested on connect", form.authMode == AuthMode.ASK) {
                        form.authMode = AuthMode.ASK; menuOpen = false
                    }
                    MobileAuthOption("password", "Password…", "store a password in your vault", form.authMode == AuthMode.NEW_PASSWORD) {
                        form.authMode = AuthMode.NEW_PASSWORD; menuOpen = false
                    }
                    MobileAuthOption("key", "Paste private key…", "store an SSH key in your vault", form.authMode == AuthMode.NEW_KEY) {
                        form.authMode = AuthMode.NEW_KEY; menuOpen = false
                    }
                    // Разделитель перед сохранёнными секретами (паритет desktop AuthPicker).
                    if (saved.isNotEmpty()) {
                        HLine(modifier = Modifier.padding(vertical = 4.dp))
                        saved.forEach { cred ->
                            MobileAuthOption(cred.secret.pickerIcon(), cred.label, cred.secret.pickerTypeLabel(), form.authMode == AuthMode.EXISTING && form.existingCredentialId == cred.id) {
                                form.authMode = AuthMode.EXISTING; form.existingCredentialId = cred.id; menuOpen = false
                            }
                        }
                    }
                }
            },
        )
        when (form.authMode) {
            AuthMode.NEW_PASSWORD -> {
                Spacer(Modifier.height(12.dp))
                SheetInput(form.password, { form.password = it }, "password to store", masked = true)
            }
            AuthMode.NEW_KEY -> {
                Spacer(Modifier.height(12.dp))
                // keyboardType=Password гасит автокоррект/подсказки IME (Android), чтобы ключ не оседал в словаре.
                // PEM показываем в открытую (не masked): маскирование многострочного поля ломает вставку
                // ключа и его визуальную проверку — осознанный trade-off, как на desktop ModalTextField.
                SheetInput(form.privateKeyPem, { form.privateKeyPem = it }, "-----BEGIN OPENSSH PRIVATE KEY-----", keyboardType = KeyboardType.Password, singleLine = false, mono = true, minHeightDp = 104)
                Spacer(Modifier.height(12.dp))
                SheetInput(form.passphrase, { form.passphrase = it }, "key passphrase (optional)", masked = true)
            }
            else -> {}
        }
    }
}

/** Строка-вариант в дропдауне аутентификации: иконка + название + подпись + галочка выбранного. */
@Composable
private fun MobileAuthOption(icon: String, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) D.cyan10 else Color.Transparent)
            // Без явного interactionSource (паритет desktop AuthOption): remember в forEach позиционен —
            // при смене порядка saved слот сдвинулся бы на чужую строку.
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Sym(icon, size = 18.sp, color = if (selected) D.cyanBright else D.dim)
        Column(Modifier.weight(1f)) {
            Txt(title, color = if (selected) D.cyanBright else D.text, size = 14.sp, weight = FontWeight.Medium)
            Txt(subtitle, color = D.faint, size = 11.sp)
        }
        if (selected) Sym("check", size = 17.sp, color = D.cyanBright)
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
