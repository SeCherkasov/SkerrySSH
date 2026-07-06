package app.skerry.ui.vault

/**
 * vaultGateErrorMessage is now `@Composable` (messages come from string resources), so the old
 * unit tests (non-empty/unique/min length) were dropped: they called a `@Composable` outside
 * composition. The [VaultGateError] -> resource mapping is a trivial `when` and covered by live UI.
 */
class VaultGateErrorMessageTest
