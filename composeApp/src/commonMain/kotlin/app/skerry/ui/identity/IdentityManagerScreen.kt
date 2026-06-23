// TODO(mobile parity): полноценный двухуровневый редактор учёток (keychain + identity) — отдельная сессия.
package app.skerry.ui.identity

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.skerry.shared.vault.Identity
import app.skerry.ui.theme.SkerryColors

/**
 * Панель управления учётками ([Identity]) поверх [IdentityManagerController]. Каждая учётка — пара
 * «label + username» со ссылкой на keychain-секрет ([app.skerry.shared.vault.Credential]); метка
 * привязанного секрета берётся из [CredentialManagerController] (если передан). Богатый двухуровневый
 * редактор (создание/смена секрета) пока упрощён — см. TODO(mobile parity) вверху файла; сейчас
 * доступен просмотр списка и удаление учётки. Открывается из менеджера хостов, закрывается [onClose].
 */
@Composable
fun IdentityManagerPanel(
    controller: IdentityManagerController,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    credentials: CredentialManagerController? = null,
) {
    Column(
        modifier.fillMaxSize().widthIn(max = 460.dp).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Учётки", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = onClose) { Text("Закрыть") }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (controller.identities.isEmpty()) {
                Text("Пока нет сохранённых учёток", color = SkerryColors.textFaint, style = MaterialTheme.typography.bodySmall)
            }
            controller.identities.forEach { identity ->
                IdentityRow(
                    identity = identity,
                    credentialLabel = credentials?.find(identity.credentialId)?.label,
                    onDelete = { controller.delete(identity.id) },
                )
            }
        }
    }
}

@Composable
private fun IdentityRow(identity: Identity, credentialLabel: String?, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(identity.label.ifBlank { "(без имени)" }, color = SkerryColors.text, style = MaterialTheme.typography.bodyMedium)
            val subtitle = buildString {
                append(identity.username)
                if (!credentialLabel.isNullOrBlank()) append(" · ").append(credentialLabel)
            }
            Text(subtitle, color = SkerryColors.textFaint, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onDelete) {
            Text("Удалить", color = MaterialTheme.colorScheme.error)
        }
    }
}
