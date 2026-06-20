package app.skerry.ui.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Локализованные строки ошибок гейта переиспользуются и Material-формами, и дизайн-слоем
 * (`ui/design`), поэтому маппинг вынесен из `private` и зафиксирован тестом: каждое значение —
 * непустое и уникальное, а «слишком короткий пароль» называет минимальную длину.
 */
class VaultGateErrorMessageTest {

    @Test
    fun too_short_message_mentions_min_length() {
        val msg = vaultGateErrorMessage(VaultGateError.PasswordTooShort)
        assertTrue(msg.contains(MIN_MASTER_PASSWORD_LENGTH.toString()), "ожидался минимум в тексте: $msg")
    }

    @Test
    fun every_error_has_distinct_non_blank_message() {
        val all = VaultGateError.entries.map { vaultGateErrorMessage(it) }
        assertTrue(all.all { it.isNotBlank() }, "пустые сообщения: $all")
        assertEquals(all.size, all.toSet().size, "сообщения должны быть уникальны: $all")
    }
}
