package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.LocalKeepAliveBridge
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.keepalive_header_desc
import app.skerry.ui.generated.resources.keepalive_notice_safety
import app.skerry.ui.generated.resources.keepalive_step1_btn
import app.skerry.ui.generated.resources.keepalive_step1_desc
import app.skerry.ui.generated.resources.keepalive_step1_title
import app.skerry.ui.generated.resources.keepalive_step2_btn
import app.skerry.ui.generated.resources.keepalive_step2_desc
import app.skerry.ui.generated.resources.keepalive_step2_title
import app.skerry.ui.generated.resources.keepalive_step3_btn
import app.skerry.ui.generated.resources.keepalive_step3_desc_generic
import app.skerry.ui.generated.resources.keepalive_step3_desc_xiaomi
import app.skerry.ui.generated.resources.keepalive_step3_title
import app.skerry.ui.generated.resources.keepalive_subtitle_optimal
import app.skerry.ui.generated.resources.keepalive_subtitle_warning
import app.skerry.ui.generated.resources.keepalive_title
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

@Composable
fun MobileKeepAliveScreen(state: MobileDesignState) {
    val bridge = LocalKeepAliveBridge.current
    val manufacturer = bridge?.getManufacturer() ?: "Android"
    val isXiaomi = manufacturer.lowercase().let { it.contains("xiaomi") || it.contains("redmi") }
    val isHuawei = manufacturer.lowercase().let { it.contains("huawei") || it.contains("honor") }
    val isOppo = manufacturer.lowercase().let { it.contains("oppo") || it.contains("realme") || it.contains("oneplus") }
    val isVivo = manufacturer.lowercase().let { it.contains("vivo") || it.contains("iqoo") }

    val friendlyBrand = when {
        isXiaomi -> "小米 / 红米 (Xiaomi / Redmi)"
        isHuawei -> "华为 / 荣耀 (Huawei / Honor)"
        isOppo -> "OPPO / 一加 / 真我 (ColorOS)"
        isVivo -> "vivo / iQOO (OriginOS)"
        else -> manufacturer.ifBlank { "Android" }
    }

    val isOptimized = bridge?.isOptimizedForKeepAlive() ?: true

    Box(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            MobilePushHeader(stringResource(Res.string.keepalive_title), onBack = state::pop)
            Column(
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Txt(
                    stringResource(Res.string.keepalive_header_desc),
                    color = Skerry.colors.dim,
                    size = 12.5.sp,
                    lineHeight = 18.sp,
                )

                // Device status card
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Skerry.colors.card)
                        .border(1.dp, Skerry.colors.line, RoundedCornerShape(10.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Sym("smartphone", size = 28.sp, color = Skerry.colors.cyanBright)
                    Column(Modifier.weight(1f)) {
                        Txt(friendlyBrand, color = Skerry.colors.text, size = 14.sp, weight = FontWeight.SemiBold)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Box(
                                Modifier.clip(RoundedCornerShape(3.dp))
                                    .background(if (isOptimized) Skerry.colors.moss.copy(alpha = 0.2f) else Skerry.colors.amber.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Txt(
                                    if (isOptimized) stringResource(Res.string.keepalive_subtitle_optimal) else stringResource(Res.string.keepalive_subtitle_warning),
                                    color = if (isOptimized) Skerry.colors.moss else Skerry.colors.amber,
                                    size = 11.sp,
                                    weight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }

                // Step 1: Battery optimization
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Skerry.colors.card)
                        .border(1.dp, Skerry.colors.line, RoundedCornerShape(10.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Sym("battery_charging_full", size = 20.sp, color = Skerry.colors.cyanBright)
                        Txt(stringResource(Res.string.keepalive_step1_title), color = Skerry.colors.text, size = 14.sp, weight = FontWeight.SemiBold)
                    }
                    Txt(stringResource(Res.string.keepalive_step1_desc), color = Skerry.colors.dim, size = 12.sp, lineHeight = 17.sp)
                    PrimaryButton(
                        label = stringResource(Res.string.keepalive_step1_btn),
                        onClick = { bridge?.requestKeepAliveOptimization() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Step 2: Autostart & background
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Skerry.colors.card)
                        .border(1.dp, Skerry.colors.line, RoundedCornerShape(10.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Sym("play_arrow", size = 20.sp, color = Skerry.colors.cyanBright)
                        Txt(stringResource(Res.string.keepalive_step2_title), color = Skerry.colors.text, size = 14.sp, weight = FontWeight.SemiBold)
                    }
                    Txt(stringResource(Res.string.keepalive_step2_desc), color = Skerry.colors.dim, size = 12.sp, lineHeight = 17.sp)
                    GhostButton(
                        label = stringResource(Res.string.keepalive_step2_btn),
                        onClick = { bridge?.openAutostartSettings() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Step 3: Recents lock & lock-screen memory
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Skerry.colors.card)
                        .border(1.dp, Skerry.colors.line, RoundedCornerShape(10.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Sym("lock", size = 20.sp, color = Skerry.colors.cyanBright)
                        Txt(stringResource(Res.string.keepalive_step3_title), color = Skerry.colors.text, size = 14.sp, weight = FontWeight.SemiBold)
                    }
                    Txt(
                        if (isXiaomi) stringResource(Res.string.keepalive_step3_desc_xiaomi) else stringResource(Res.string.keepalive_step3_desc_generic),
                        color = Skerry.colors.dim,
                        size = 12.sp,
                        lineHeight = 17.sp,
                    )
                    GhostButton(
                        label = stringResource(Res.string.keepalive_step3_btn),
                        onClick = { bridge?.openAppDetailsSettings() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Safety notice
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Skerry.colors.cyanBright.copy(alpha = 0.08f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Sym("verified_user", size = 18.sp, color = Skerry.colors.cyanBright)
                    Txt(
                        stringResource(Res.string.keepalive_notice_safety),
                        color = Skerry.colors.dim,
                        size = 11.5.sp,
                        lineHeight = 16.sp,
                    )
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
