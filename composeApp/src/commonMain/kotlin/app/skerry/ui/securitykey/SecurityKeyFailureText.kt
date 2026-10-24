package app.skerry.ui.securitykey

import app.skerry.shared.securitykey.SecurityKeyFailure
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.security_key_algorithm
import app.skerry.ui.generated.resources.security_key_cancelled
import app.skerry.ui.generated.resources.security_key_detached
import app.skerry.ui.generated.resources.security_key_disabled
import app.skerry.ui.generated.resources.security_key_error
import app.skerry.ui.generated.resources.security_key_invalid
import app.skerry.ui.generated.resources.security_key_no_device
import app.skerry.ui.generated.resources.security_key_permission
import app.skerry.ui.generated.resources.security_key_pin_blocked
import app.skerry.ui.generated.resources.security_key_pin_invalid
import app.skerry.ui.generated.resources.security_key_pin_required
import app.skerry.ui.generated.resources.security_key_timeout
import app.skerry.ui.generated.resources.security_key_unavailable
import app.skerry.ui.generated.resources.security_key_verification
import org.jetbrains.compose.resources.StringResource

private val securityKeyFailureResources = mapOf(
    SecurityKeyFailure.Disabled to Res.string.security_key_disabled,
    SecurityKeyFailure.Unavailable to Res.string.security_key_unavailable,
    SecurityKeyFailure.NoDevice to Res.string.security_key_no_device,
    SecurityKeyFailure.PermissionDenied to Res.string.security_key_permission,
    SecurityKeyFailure.Detached to Res.string.security_key_detached,
    SecurityKeyFailure.Cancelled to Res.string.security_key_cancelled,
    SecurityKeyFailure.Timeout to Res.string.security_key_timeout,
    SecurityKeyFailure.PinRequired to Res.string.security_key_pin_required,
    SecurityKeyFailure.PinInvalid to Res.string.security_key_pin_invalid,
    SecurityKeyFailure.PinBlocked to Res.string.security_key_pin_blocked,
    SecurityKeyFailure.UnsupportedAlgorithm to Res.string.security_key_algorithm,
    SecurityKeyFailure.InvalidKey to Res.string.security_key_invalid,
    SecurityKeyFailure.VerificationFailed to Res.string.security_key_verification,
    SecurityKeyFailure.DeviceError to Res.string.security_key_error,
)

internal fun securityKeyFailureText(reason: SecurityKeyFailure): StringResource = securityKeyFailureResources.getValue(reason)
