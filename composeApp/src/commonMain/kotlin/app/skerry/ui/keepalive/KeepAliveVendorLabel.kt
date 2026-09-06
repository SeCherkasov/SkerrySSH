package app.skerry.ui.keepalive

import androidx.compose.runtime.Composable
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.keepalive_vendor_huawei
import app.skerry.ui.generated.resources.keepalive_vendor_oppo
import app.skerry.ui.generated.resources.keepalive_vendor_samsung
import app.skerry.ui.generated.resources.keepalive_vendor_vivo
import app.skerry.ui.generated.resources.keepalive_vendor_xiaomi
import org.jetbrains.compose.resources.stringResource

/**
 * What the screen calls the detected ROM family. A recognised family names every brand it covers,
 * so a POCO owner can tell the Xiaomi steps are meant for them; an unrecognised one falls back to
 * what the firmware calls itself.
 *
 * That fallback goes through [untrustedLabel]: `Build.MANUFACTURER` is a string a custom ROM sets,
 * and this one is drawn next to instructions — a bidi override in it would rewrite the line it
 * sits on.
 */
@Composable
internal fun KeepAliveVendor.label(manufacturer: String): String = when (this) {
    KeepAliveVendor.Xiaomi -> stringResource(Res.string.keepalive_vendor_xiaomi)
    KeepAliveVendor.Huawei -> stringResource(Res.string.keepalive_vendor_huawei)
    KeepAliveVendor.Oppo -> stringResource(Res.string.keepalive_vendor_oppo)
    KeepAliveVendor.Vivo -> stringResource(Res.string.keepalive_vendor_vivo)
    KeepAliveVendor.Samsung -> stringResource(Res.string.keepalive_vendor_samsung)
    KeepAliveVendor.Other -> untrustedLabel(manufacturer)
}
