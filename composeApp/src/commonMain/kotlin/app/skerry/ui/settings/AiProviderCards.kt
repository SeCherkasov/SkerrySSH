package app.skerry.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ai.AiProviderKind
import app.skerry.shared.ai.local.LocalModel
import app.skerry.shared.ai.local.LocalModelCatalog
import app.skerry.ui.ai.AiAssistantController
import app.skerry.ui.ai.LocalModelController
import app.skerry.ui.ai.LocalModelStatus
import app.skerry.ui.ai.localModelFailureMessage
import app.skerry.ui.design.Badge
import app.skerry.ui.design.ChipButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ftail_transfer_progress
import app.skerry.ui.generated.resources.settings_ai_badge_private
import app.skerry.ui.generated.resources.settings_ai_default_provider
import app.skerry.ui.generated.resources.settings_ai_default_provider_desc
import app.skerry.ui.generated.resources.settings_ai_local_cancel
import app.skerry.ui.generated.resources.settings_ai_local_delete
import app.skerry.ui.generated.resources.settings_ai_local_desc
import app.skerry.ui.generated.resources.settings_ai_local_download
import app.skerry.ui.generated.resources.settings_ai_local_ready
import app.skerry.ui.generated.resources.settings_ai_local_retry
import app.skerry.ui.generated.resources.settings_ai_local_verifying
import app.skerry.ui.generated.resources.settings_ai_model_meta
import app.skerry.ui.generated.resources.settings_ai_provider_byok
import app.skerry.ui.generated.resources.settings_ai_provider_byok_desc
import app.skerry.ui.generated.resources.settings_ai_provider_device
import app.skerry.ui.generated.resources.settings_ai_provider_off
import app.skerry.ui.generated.resources.settings_ai_provider_off_desc
import app.skerry.ui.sftp.humanSize
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

/**
 * Default AI provider picker: on-device with a local model catalog (download/progress/delete),
 * BYOK, or off. Only the selected card expands its content (model catalog / [byokContent] —
 * key-model-endpoint form, layout supplied by the caller). Selection saves immediately via
 * [AiAssistantController.selectProvider]; Strict-policy hosts still route to the device model
 * regardless of the default chosen here (see AiRouter).
 */
@Composable
internal fun AiProviderCards(ai: AiAssistantController, byokContent: (@Composable () -> Unit)? = null) {
    Txt(stringResource(Res.string.settings_ai_default_provider), color = Skerry.colors.text, size = 13.sp, weight = FontWeight.Medium)
    Txt(stringResource(Res.string.settings_ai_default_provider_desc), color = Skerry.colors.dim, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))

    val models = ai.models
    val provider = ai.settings.provider
    ProviderCard(
        icon = "lock",
        title = stringResource(Res.string.settings_ai_provider_device),
        desc = stringResource(Res.string.settings_ai_local_desc),
        selected = provider == AiProviderKind.DEVICE,
        badge = stringResource(Res.string.settings_ai_badge_private),
        onClick = models?.let { { ai.selectProvider(AiProviderKind.DEVICE) } },
    ) {
        if (models != null && provider == AiProviderKind.DEVICE) LocalModelList(ai, models)
    }
    Box(Modifier.height(8.dp))
    ProviderCard(
        icon = "key",
        title = stringResource(Res.string.settings_ai_provider_byok),
        desc = stringResource(Res.string.settings_ai_provider_byok_desc),
        selected = provider == AiProviderKind.CLOUD,
        onClick = { ai.selectProvider(AiProviderKind.CLOUD) },
    ) {
        if (provider == AiProviderKind.CLOUD) byokContent?.invoke()
    }
    Box(Modifier.height(8.dp))
    // Global kill switch: AI is hidden everywhere and sends nothing, overriding per-host policies.
    ProviderCard(
        icon = "block",
        title = stringResource(Res.string.settings_ai_provider_off),
        desc = stringResource(Res.string.settings_ai_provider_off_desc),
        selected = provider == AiProviderKind.OFF,
        onClick = { ai.selectProvider(AiProviderKind.OFF) },
    )
}

/** Model catalog list inside the "On this device" card: radio selection + status/actions. */
@Composable
private fun LocalModelList(ai: AiAssistantController, models: LocalModelController) {
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LocalModelCatalog.models.forEach { model -> LocalModelRow(ai, models, model) }
    }
}

@Composable
private fun LocalModelRow(ai: AiAssistantController, models: LocalModelController, model: LocalModel) {
    val selected = ai.localModel.id == model.id
    val status = models.status(model)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Skerry.colors.cyan10 else Skerry.colors.card)
            .border(1.dp, if (selected) Skerry.colors.cyan14 else Skerry.colors.line, RoundedCornerShape(7.dp))
            .clickable { ai.selectLocalModel(model.id) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(13.dp).clip(CircleShape).border(1.5.dp, if (selected) Skerry.colors.cyan else Skerry.colors.faint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Box(Modifier.size(6.dp).clip(CircleShape).background(Skerry.colors.cyan))
            }
            Column(Modifier.weight(1f)) {
                Txt(model.displayName, color = Skerry.colors.text, size = 12.5.sp, weight = FontWeight.Medium)
                Txt(stringResource(Res.string.settings_ai_model_meta, humanSize(model.sizeBytes), model.license), color = Skerry.colors.dim, size = 10.5.sp, modifier = Modifier.padding(top = 1.dp))
            }
            ModelActions(models, model, status)
        }
        if (status is LocalModelStatus.Downloading) {
            val fraction = (status.downloadedBytes.toFloat() / status.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f)
            // Progress bar is its own full-width row, counter below it: in a single weighted Row
            // the changing text width ("9.9 MB…" → "10.0 MB…") would jitter the bar width.
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Skerry.colors.line)) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Skerry.colors.cyan))
            }
            Txt(
                stringResource(Res.string.ftail_transfer_progress, humanSize(status.downloadedBytes), humanSize(status.totalBytes)),
                color = Skerry.colors.dim, size = 10.sp,
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
            )
        }
        if (status is LocalModelStatus.Failed) {
            Txt(localModelFailureMessage(status.failure), color = Skerry.colors.storm, size = 10.5.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Model action/status on the right of the row: download, cancel, delete, retry. */
@Composable
private fun ModelActions(models: LocalModelController, model: LocalModel, status: LocalModelStatus) {
    when (status) {
        LocalModelStatus.NotInstalled ->
            ChipButton(stringResource(Res.string.settings_ai_local_download), color = Skerry.colors.cyan, onClick = { models.download(model) })
        is LocalModelStatus.Downloading ->
            ChipButton(stringResource(Res.string.settings_ai_local_cancel), color = Skerry.colors.dim, onClick = { models.cancel(model) })
        LocalModelStatus.Verifying ->
            Txt(stringResource(Res.string.settings_ai_local_verifying), color = Skerry.colors.dim, size = 11.sp)
        LocalModelStatus.Installed ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(stringResource(Res.string.settings_ai_local_ready), color = Skerry.colors.moss, size = 11.sp)
                ChipButton(stringResource(Res.string.settings_ai_local_delete), color = Skerry.colors.dim, onClick = { models.delete(model) })
            }
        is LocalModelStatus.Failed ->
            ChipButton(stringResource(Res.string.settings_ai_local_retry), color = Skerry.colors.cyan, onClick = { models.download(model) })
    }
}

/**
 * Provider card: icon, title with badge, description, radio mark on the right. [onClick] `null`
 * makes the card inert (mock preview / platform without the subsystem); [content] is the
 * expanded body (model catalog for "on this device").
 */
@Composable
internal fun ProviderCard(
    icon: String,
    title: String,
    desc: String,
    selected: Boolean,
    badge: String? = null,
    onClick: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Skerry.colors.cyan10 else Color.Transparent)
            .border(1.dp, if (selected) Skerry.colors.cyan else Skerry.colors.cyan08, RoundedCornerShape(8.dp))
            .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m }
            .padding(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(7.dp)).background(if (selected) Skerry.colors.cyan20 else Skerry.colors.overlayMed), contentAlignment = Alignment.Center) {
                Sym(icon, size = 18.sp, color = if (selected) Skerry.colors.cyan else Skerry.colors.dim)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(title, color = Skerry.colors.text, size = 13.sp, weight = FontWeight.Medium)
                    if (badge != null) Badge(badge, bg = Skerry.colors.moss.copy(alpha = 0.16f), fg = Skerry.colors.moss, radius = 3, size = 9.5.sp)
                }
                Txt(desc, color = Skerry.colors.dim, size = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Box(
                Modifier.padding(top = 2.dp).size(18.dp).clip(CircleShape).background(if (selected) Skerry.colors.cyan else Color.Transparent).border(1.5.dp, if (selected) Skerry.colors.cyan else Skerry.colors.faint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Sym("check", size = 12.sp, color = Skerry.colors.ink)
            }
        }
        content?.invoke()
    }
}
