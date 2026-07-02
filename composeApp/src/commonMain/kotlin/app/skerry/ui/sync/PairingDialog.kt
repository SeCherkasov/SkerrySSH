package app.skerry.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.sync.PairingOffer
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.sync.nowMillis
import app.skerry.ui.sync.qr.QrImage
import kotlinx.coroutines.delay
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sync_link_device
import app.skerry.ui.generated.resources.sync_pairing_dialog_desc
import app.skerry.ui.generated.resources.sync_zero_knowledge
import app.skerry.ui.generated.resources.sync_done
import app.skerry.ui.generated.resources.sync_pairing_gen_failed
import app.skerry.ui.generated.resources.sync_generating_code
import app.skerry.ui.generated.resources.sync_code_expired
import app.skerry.ui.generated.resources.sync_expires_in
import app.skerry.ui.generated.resources.sync_copied
import app.skerry.ui.generated.resources.sync_copy
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.D
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt

/**
 * Desktop-диалог «Link a device» (вошедшее устройство): скрим + карточка, как [SyncSetupDialog].
 * Содержимое — общий [PairingOfferContent] (QR + текстовый код + обратный отсчёт). Быстрого паринга
 * нет в макете (макет показывает лишь тост «New device paired»), стиль выдержан под существующие диалоги.
 */
@Composable
fun PairingShowDialog(sync: SyncCoordinator, onDismiss: () -> Unit) {
    val noop = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(Color(0xB3060E16))
            .clickable(interactionSource = noop, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(D.surfaceDeep)
                .border(1.dp, D.cyan14, RoundedCornerShape(12.dp))
                .clickable(interactionSource = noop, indication = null, onClick = {})
                .padding(26.dp),
        ) {
            Txt(stringResource(Res.string.sync_link_device), color = D.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
            Txt(
                stringResource(Res.string.sync_pairing_dialog_desc),
                color = D.dim, size = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            PairingOfferContent(sync)
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sym("shield_lock", size = 14.sp, color = D.moss)
                    Txt(stringResource(Res.string.sync_zero_knowledge), color = D.faint, size = 11.sp)
                }
                PrimaryButton(stringResource(Res.string.sync_done), onClick = onDismiss)
            }
        }
    }
}

/**
 * Общий контент показа кода связывания (desktop-диалог и mobile-секция): запускает [SyncCoordinator.startPairing]
 * один раз, показывает QR-код, текстовый код (выделяемый + кнопка Copy) и обратный отсчёт до протухания.
 * transferKey уезжает в QR/код — на сервере без него только бесполезный конверт (zero-knowledge).
 */
@Composable
fun PairingOfferContent(sync: SyncCoordinator) {
    var offer by remember { mutableStateOf<PairingOffer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var remaining by remember { mutableStateOf<Long?>(null) } // секунд до протухания
    val clipboard = LocalClipboardManager.current
    val genFailedMessage = stringResource(Res.string.sync_pairing_gen_failed)

    LaunchedEffect(Unit) {
        val o = sync.startPairing()
        if (o == null) error = genFailedMessage else offer = o
    }
    LaunchedEffect(offer) {
        val o = offer ?: return@LaunchedEffect
        while (true) {
            val left = ((o.expiresAt - nowMillis()) / 1000).coerceAtLeast(0)
            remaining = left
            if (left <= 0L) break
            delay(1000)
        }
    }
    LaunchedEffect(copied) {
        if (copied) { delay(1600); copied = false }
    }

    val mono = LocalFonts.current.mono
    when {
        error != null -> Row(
            Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Sym("error", size = 14.sp, color = D.sunset)
            Txt(error!!, color = D.sunset, size = 11.5.sp)
        }

        offer == null -> Row(
            Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(stringResource(Res.string.sync_generating_code), color = D.dim, size = 12.sp)
        }

        else -> {
            Box(
                Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center,
            ) {
                QrImage(offer!!.payload, Modifier.size(200.dp).clip(RoundedCornerShape(8.dp)))
            }
            // Текстовый код для устройств без камеры / desktop→desktop: выделяемый, моноширинный.
            SelectionContainer {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(D.bg)
                        .border(1.dp, D.cyan14, RoundedCornerShape(7.dp))
                        .horizontalScroll(rememberScrollState()).padding(horizontal = 11.dp, vertical = 9.dp),
                ) {
                    Txt(offer!!.payload, color = D.dim, size = 11.sp, font = mono, maxLines = 1)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val left = remaining
                val expired = left != null && left <= 0L
                Txt(
                    when {
                        expired -> stringResource(Res.string.sync_code_expired)
                        left != null -> stringResource(Res.string.sync_expires_in, formatMmSs(left))
                        else -> ""
                    },
                    color = if (expired) D.sunset else D.faint, size = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                GhostButton(
                    if (copied) stringResource(Res.string.sync_copied) else stringResource(Res.string.sync_copy),
                    onClick = { clipboard.setText(AnnotatedString(offer!!.payload)); copied = true },
                    icon = if (copied) "check" else "content_copy",
                )
            }
        }
    }
}

private fun formatMmSs(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
