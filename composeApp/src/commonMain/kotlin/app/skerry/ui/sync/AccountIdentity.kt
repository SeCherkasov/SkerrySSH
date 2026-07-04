package app.skerry.ui.sync

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.LocalTeams
import app.skerry.ui.design.D
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sync_account_id_label
import app.skerry.ui.generated.resources.sync_copied
import app.skerry.ui.generated.resources.sync_identity_hint
import app.skerry.ui.generated.resources.sync_sharing_fingerprint_label
import app.skerry.ui.vault.copyTextToClipboard
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Идентификаторы аккаунта для Teams-приглашений: accountId и отпечаток СВОЕГО ключа шеринга (X25519).
 * Показываются в настройках аккаунта (desktop Settings → Account, mobile More → Sync), чтобы их можно
 * было скопировать и сообщить владельцу команды, не разыскивая ID в админке sync-сервера. Оба значения
 * публичные — копируются обычным буфером без пометки sensitive.
 */
@Composable
fun AccountIdentityBlock(accountId: String, modifier: Modifier = Modifier) {
    val teams = LocalTeams.current
    // Отпечаток считается из identity-пары в личном vault'е: настройки открываются только с открытым
    // vault'ом, так что null здесь — превью/офскрин без Teams-бэкенда (или ключ ещё не создан).
    val fingerprint = remember(teams, accountId) { teams?.ownFingerprint() }
    val mono = LocalFonts.current.mono
    var copied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(1500)
            copied = null
        }
    }

    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).border(1.dp, D.cyan08, RoundedCornerShape(9.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IdentityRow(stringResource(Res.string.sync_account_id_label), accountId, mono, copied == accountId) { copied = accountId }
        if (fingerprint != null) {
            IdentityRow(stringResource(Res.string.sync_sharing_fingerprint_label), fingerprint, mono, copied == fingerprint) { copied = fingerprint }
        }
        Txt(stringResource(Res.string.sync_identity_hint), color = D.faint, size = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun IdentityRow(label: String, value: String, mono: FontFamily, copied: Boolean, onCopied: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Txt(label.uppercase(), color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            Txt(value, color = D.cyanBright, size = 13.sp, font = mono, modifier = Modifier.padding(top = 3.dp))
        }
        if (copied) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Sym("check", size = 16.sp, color = D.moss)
                Txt(stringResource(Res.string.sync_copied), color = D.moss, size = 11.sp)
            }
        } else {
            Box(
                Modifier.clip(RoundedCornerShape(7.dp))
                    .clickable {
                        copyTextToClipboard(value)
                        onCopied()
                    }
                    .padding(6.dp),
            ) {
                Sym("content_copy", size = 16.sp, color = D.dim)
            }
        }
    }
}
