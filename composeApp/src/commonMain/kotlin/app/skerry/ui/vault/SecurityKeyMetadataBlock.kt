package app.skerry.ui.vault

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.vault.SshPublicKeyInfo
import app.skerry.ui.app.LocalSecurityKeysEnabled
import app.skerry.ui.design.Txt
import app.skerry.ui.design.sanitizeServerText
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.security_key_disabled
import app.skerry.ui.generated.resources.security_key_metadata
import app.skerry.ui.generated.resources.security_key_no
import app.skerry.ui.generated.resources.security_key_yes
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SecurityKeyMetadataBlock(info: SshPublicKeyInfo) {
    val metadata = info.securityKey ?: return
    Txt(
        stringResource(Res.string.security_key_metadata, sanitizeServerText(metadata.application, maxChars = 512, allowNewlines = false), stringResource(if (metadata.resident) Res.string.security_key_yes else Res.string.security_key_no)),
        color = Skerry.colors.cyanBright, size = 11.sp, modifier = Modifier.padding(bottom = 12.dp),
    )
    if (!LocalSecurityKeysEnabled.current) {
        Txt(stringResource(Res.string.security_key_disabled), color = Skerry.colors.sunset, size = 11.sp, modifier = Modifier.padding(bottom = 12.dp))
    }
}
