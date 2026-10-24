package app.skerry.ui.securitykey

import app.skerry.shared.securitykey.SecurityKeyAlgorithm
import app.skerry.shared.securitykey.SecurityKeyGenerationOptions

/** Shared desktop/mobile form. Only the private OpenSSH stub is persisted, never a token PIN. */
internal data class SecurityKeyGenerationForm(
    val algorithm: SecurityKeyAlgorithm = SecurityKeyAlgorithm.ED25519,
    val application: String = "ssh:",
    val resident: Boolean = false,
    val userVerification: Boolean = false,
    val username: String = "",
) {
    val valid: Boolean get() = application.startsWith("ssh:") && application.length in 4..255 &&
        application.none { it.code < 32 || it.code == 127 } && username.length <= 256 && username.none { it.code < 32 }
    fun options(comment: String) = SecurityKeyGenerationOptions(
        algorithm = algorithm, application = application, resident = resident,
        userVerification = userVerification, comment = comment,
        username = username,
    )
}
