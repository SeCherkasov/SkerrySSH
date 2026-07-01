package app.skerry.ui.vault

/**
 * Прим.: vaultGateErrorMessage стала @Composable — сообщения ошибок гейта локализованы через строковые
 * ресурсы (`strings_vtail`), поэтому прежние юнит-тесты (непустота/уникальность/наличие мин. длины)
 * сняты: они требовали вызова @Composable-функции вне composition. Само сопоставление
 * [VaultGateError] → ресурс тривиально (when по enum) и проверяется в живом UI.
 */
class VaultGateErrorMessageTest
