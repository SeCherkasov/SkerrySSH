package app.skerry.ui.vault

/** Оценка силы мастер-пароля для индикатора на экране создания vault. */
enum class PasswordStrength { Weak, Fair, Good, Strong }

/**
 * Грубая эвристика силы пароля по длине и числу классов символов (нижний/верхний регистр, цифры,
 * прочие). Не криптостойкая метрика энтропии — только UX-подсказка «не делай совсем слабый». Пустой
 * ввод → `null` (индикатор скрыт). Короче 8 символов → всегда [PasswordStrength.Weak]. Чистая функция,
 * зафиксирована [PasswordStrengthTest].
 */
fun passwordStrength(password: String): PasswordStrength? {
    if (password.isEmpty()) return null
    // Пробельный пароль не даёт реальной стойкости — не вводим в заблуждение оценкой выше Weak.
    if (password.isBlank()) return PasswordStrength.Weak
    val len = password.length
    if (len < 8) return PasswordStrength.Weak

    var classes = 0
    if (password.any { it.isLowerCase() }) classes++
    if (password.any { it.isUpperCase() }) classes++
    if (password.any { it.isDigit() }) classes++
    if (password.any { !it.isLetterOrDigit() }) classes++

    var score = 1 // длина уже >= 8
    if (len >= 12) score++
    if (len >= 16) score++
    if (classes >= 2) score++
    if (classes >= 3) score++

    return when {
        score <= 2 -> PasswordStrength.Fair
        score == 3 -> PasswordStrength.Good
        else -> PasswordStrength.Strong
    }
}
