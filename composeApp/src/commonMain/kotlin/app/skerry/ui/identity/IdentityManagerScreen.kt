package app.skerry.ui.identity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.skerry.shared.vault.Identity
import app.skerry.shared.vault.IdentityAuth
import app.skerry.ui.theme.SkerryColors

/**
 * Панель управления переиспользуемыми секретами (identity) поверх [IdentityManagerController].
 * Список со списком секретов и редактор (создание/правка) в локальном [editing]-черновике.
 * Открывается из менеджера хостов и закрывается через [onClose].
 */
@Composable
fun IdentityManagerPanel(
    controller: IdentityManagerController,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<IdentityDraft?>(null) }

    Column(
        modifier.fillMaxSize().widthIn(max = 460.dp).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Ключи и пароли", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = onClose) { Text("Закрыть") }
        }

        val draft = editing
        if (draft != null) {
            IdentityEditor(
                draft = draft,
                onSave = { controller.save(it); editing = null },
                onCancel = { editing = null },
                onDelete = draft.id?.let { id -> { controller.delete(id); editing = null } },
            )
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (controller.identities.isEmpty()) {
                    Text("Пока нет сохранённых секретов", color = SkerryColors.textFaint, style = MaterialTheme.typography.bodySmall)
                }
                controller.identities.forEach { identity ->
                    IdentityRow(identity) { editing = identity.toDraft() }
                }
            }
            Button(
                onClick = { editing = IdentityDraft(label = "", kind = IdentityKind.PASSWORD) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("+ Новый секрет")
            }
        }
    }
}

private fun Identity.toDraft(): IdentityDraft = when (val a = auth) {
    is IdentityAuth.Password -> IdentityDraft(id = id, label = label, kind = IdentityKind.PASSWORD, password = a.password)
    is IdentityAuth.PrivateKey -> IdentityDraft(
        id = id,
        label = label,
        kind = IdentityKind.PRIVATE_KEY,
        privateKeyPem = a.privateKeyPem,
        passphrase = a.passphrase ?: "",
    )
}

/** Короткая подпись вида секрета для списков/панелей. */
internal fun IdentityAuth.kindLabel(): String = when (this) {
    is IdentityAuth.Password -> "пароль"
    is IdentityAuth.PrivateKey -> "ключ"
}

@Composable
private fun IdentityRow(identity: Identity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(identity.label.ifBlank { "(без имени)" }, color = SkerryColors.text, style = MaterialTheme.typography.bodyMedium)
        Text("· ${identity.auth.kindLabel()}", color = SkerryColors.textFaint, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun IdentityEditor(
    draft: IdentityDraft,
    onSave: (IdentityDraft) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var label by remember(draft) { mutableStateOf(draft.label) }
    var kind by remember(draft) { mutableStateOf(draft.kind) }
    var password by remember(draft) { mutableStateOf(draft.password) }
    var pem by remember(draft) { mutableStateOf(draft.privateKeyPem) }
    var passphrase by remember(draft) { mutableStateOf(draft.passphrase) }

    val canSave = label.isNotBlank() && when (kind) {
        IdentityKind.PASSWORD -> password.isNotEmpty()
        IdentityKind.PRIVATE_KEY -> pem.isNotBlank()
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(label, { label = it }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            KindToggle("Пароль", kind == IdentityKind.PASSWORD, Modifier.weight(1f)) { kind = IdentityKind.PASSWORD }
            KindToggle("Ключ", kind == IdentityKind.PRIVATE_KEY, Modifier.weight(1f)) { kind = IdentityKind.PRIVATE_KEY }
        }

        when (kind) {
            IdentityKind.PASSWORD -> OutlinedTextField(
                password, { password = it }, label = { Text("Пароль") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )

            IdentityKind.PRIVATE_KEY -> {
                OutlinedTextField(
                    pem, { pem = it }, label = { Text("Приватный ключ (PEM)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    passphrase, { passphrase = it }, label = { Text("Passphrase (если есть)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (canSave) {
                        onSave(
                            draft.copy(
                                label = label.trim(),
                                kind = kind,
                                password = password,
                                privateKeyPem = pem,
                                passphrase = passphrase,
                            ),
                        )
                    }
                },
                enabled = canSave,
                modifier = Modifier.weight(1f),
            ) {
                Text("Сохранить")
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Отмена") }
        }
        if (onDelete != null) {
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Text("Удалить", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun KindToggle(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = if (selected) SkerryColors.cyanSoft else Color.Transparent
    OutlinedButton(onClick = onClick, modifier = modifier.background(bg, RoundedCornerShape(8.dp))) {
        Text(text, color = if (selected) MaterialTheme.colorScheme.primary else SkerryColors.textDim)
    }
}
