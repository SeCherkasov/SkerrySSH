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
import app.skerry.ui.design.Badge
import app.skerry.ui.design.ChipButton
import app.skerry.ui.design.D
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
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
import app.skerry.ui.generated.resources.settings_ai_provider_byok
import app.skerry.ui.generated.resources.settings_ai_provider_byok_desc
import app.skerry.ui.generated.resources.settings_ai_provider_device
import app.skerry.ui.generated.resources.settings_ai_provider_off
import app.skerry.ui.generated.resources.settings_ai_provider_off_desc
import app.skerry.ui.sftp.humanSize
import org.jetbrains.compose.resources.stringResource

/**
 * Живой выбор провайдера AI по умолчанию (хром — 1:1 с карточками прототипа): «На этом
 * устройстве» с каталогом локальных моделей (скачивание/прогресс/удаление), BYOK и «выключен».
 * Начинка раскрывается только у ВЫБРАННОЙ карточки (каталог моделей / [byokContent] — форма
 * ключ-модель-эндпоинт, платформенный layout подаёт вызывающий). Выбор сохраняется сразу
 * ([AiAssistantController.selectProvider]); Strict-хосты всё равно идут локально независимо
 * от выбранного здесь дефолта (см. AiRouter).
 */
@Composable
internal fun AiProviderCards(ai: AiAssistantController, byokContent: (@Composable () -> Unit)? = null) {
    Txt(stringResource(Res.string.settings_ai_default_provider), color = D.text, size = 13.sp, weight = FontWeight.Medium)
    Txt(stringResource(Res.string.settings_ai_default_provider_desc), color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))

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
    // Глобальный kill-switch: AI нигде не показывается и ничего не шлёт (сильнее per-host политик).
    ProviderCard(
        icon = "block",
        title = stringResource(Res.string.settings_ai_provider_off),
        desc = stringResource(Res.string.settings_ai_provider_off_desc),
        selected = provider == AiProviderKind.OFF,
        onClick = { ai.selectProvider(AiProviderKind.OFF) },
    )
}

/** Список моделей каталога внутри карточки «На этом устройстве»: радио-выбор + статус/действия. */
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
            .background(if (selected) D.cyan10 else Color(0x08FFFFFF))
            .border(1.dp, if (selected) D.cyan14 else D.line, RoundedCornerShape(7.dp))
            .clickable { ai.selectLocalModel(model.id) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(13.dp).clip(CircleShape).border(1.5.dp, if (selected) D.cyan else D.faint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Box(Modifier.size(6.dp).clip(CircleShape).background(D.cyan))
            }
            Column(Modifier.weight(1f)) {
                Txt(model.displayName, color = D.text, size = 12.5.sp, weight = FontWeight.Medium)
                Txt("${humanSize(model.sizeBytes)} · ${model.license}", color = D.dim, size = 10.5.sp, modifier = Modifier.padding(top = 1.dp))
            }
            ModelActions(models, model, status)
        }
        if (status is LocalModelStatus.Downloading) {
            val fraction = (status.downloadedBytes.toFloat() / status.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f)
            // Полоска — отдельной строкой во всю ширину, счётчик — под ней: в одном Row с weight
            // меняющаяся ширина текста («9.9 MB…» → «10.0 MB…») дёргала бы ширину полоски.
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(D.line)) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(D.cyan))
            }
            Txt(
                "${humanSize(status.downloadedBytes)} / ${humanSize(status.totalBytes)}",
                color = D.dim, size = 10.sp,
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
            )
        }
        if (status is LocalModelStatus.Failed) {
            Txt(status.message, color = D.storm, size = 10.5.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Действие/статус модели справа в строке: скачать, отменить, удалить, повторить. */
@Composable
private fun ModelActions(models: LocalModelController, model: LocalModel, status: LocalModelStatus) {
    when (status) {
        LocalModelStatus.NotInstalled ->
            ChipButton(stringResource(Res.string.settings_ai_local_download), color = D.cyan, onClick = { models.download(model) })
        is LocalModelStatus.Downloading ->
            ChipButton(stringResource(Res.string.settings_ai_local_cancel), color = D.dim, onClick = { models.cancel(model) })
        LocalModelStatus.Verifying ->
            Txt(stringResource(Res.string.settings_ai_local_verifying), color = D.dim, size = 11.sp)
        LocalModelStatus.Installed ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(stringResource(Res.string.settings_ai_local_ready), color = D.moss, size = 11.sp)
                ChipButton(stringResource(Res.string.settings_ai_local_delete), color = D.dim, onClick = { models.delete(model) })
            }
        is LocalModelStatus.Failed ->
            ChipButton(stringResource(Res.string.settings_ai_local_retry), color = D.cyan, onClick = { models.download(model) })
    }
}

/**
 * Карточка провайдера (стиль прототипа): иконка, заголовок с бейджем, описание, радио-отметка
 * справа. [onClick] `null` — карточка инертна (мок-превью/платформа без подсистемы);
 * [content] — раскрытая начинка (каталог моделей у «на этом устройстве»).
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
            .background(if (selected) D.cyan10 else Color.Transparent)
            .border(1.dp, if (selected) D.cyan else D.cyan08, RoundedCornerShape(8.dp))
            .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m }
            .padding(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(7.dp)).background(if (selected) D.cyan.copy(alpha = 0.2f) else Color(0x0DFFFFFF)), contentAlignment = Alignment.Center) {
                Sym(icon, size = 18.sp, color = if (selected) D.cyan else D.dim)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(title, color = D.text, size = 13.sp, weight = FontWeight.Medium)
                    if (badge != null) Badge(badge, bg = D.moss.copy(alpha = 0.16f), fg = D.moss, radius = 3, size = 9.5.sp)
                }
                Txt(desc, color = D.dim, size = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Box(
                Modifier.padding(top = 2.dp).size(18.dp).clip(CircleShape).background(if (selected) D.cyan else Color.Transparent).border(1.5.dp, if (selected) D.cyan else D.faint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Sym("check", size = 12.sp, color = D.ink)
            }
        }
        content?.invoke()
    }
}
