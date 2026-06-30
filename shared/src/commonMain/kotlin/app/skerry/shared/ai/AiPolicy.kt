package app.skerry.shared.ai

import kotlinx.serialization.Serializable

/**
 * Per-host политика AI (принцип «AI under policy», `docs/skerry-product-brief.md`). Вывод модели —
 * недоверенный источник; политика решает, можно ли слать контекст в облако и надо ли вычищать секреты.
 * Подтверждение перед выполнением команды — ВСЕГДА, независимо от политики (это отдельный инвариант UI).
 *
 * - [Strict] — только локальный AI (Phase 3). Сейчас локального провайдера нет → облако запрещено,
 *   т.е. AI-бар для такого хоста фактически недоступен. Санитизация секретов. Дефолт для новых хостов.
 * - [Balanced] — облако разрешено, секреты вычищаются перед отправкой.
 * - [Permissive] — облако разрешено без санитизации (только несенситивные системы).
 * - [Off] — AI выключен для этого хоста полностью.
 *
 * Порядок значений соответствует UI-прототипу; сериализация идёт по имени, поэтому порядок
 * на обратную совместимость сохранённых хостов не влияет.
 */
@Serializable
enum class AiPolicy { Strict, Balanced, Permissive, Off }

/**
 * Разбор [AiPolicy] в конкретные runtime-разрешения. Единственный источник правды для UI/контроллеров:
 * не размазывать `when(policy)` по слоям.
 *
 * @property aiEnabled показывать ли AI-возможности для хоста вообще (false только для [AiPolicy.Off]).
 * @property cloudAllowed можно ли слать запрос внешнему (облачному) провайдеру. Для [AiPolicy.Strict]
 *   `false` — «локальный AI only», а локального провайдера пока нет, поэтому AI-бар будет заблокирован.
 * @property sanitizeSecrets вычищать ли секреты из промпта перед отправкой (см. [SecretRedactor]).
 */
data class AiPolicyDecision(
    val aiEnabled: Boolean,
    val cloudAllowed: Boolean,
    val sanitizeSecrets: Boolean,
) {
    companion object {
        fun of(policy: AiPolicy): AiPolicyDecision = when (policy) {
            AiPolicy.Off -> AiPolicyDecision(aiEnabled = false, cloudAllowed = false, sanitizeSecrets = true)
            AiPolicy.Strict -> AiPolicyDecision(aiEnabled = true, cloudAllowed = false, sanitizeSecrets = true)
            AiPolicy.Balanced -> AiPolicyDecision(aiEnabled = true, cloudAllowed = true, sanitizeSecrets = true)
            AiPolicy.Permissive -> AiPolicyDecision(aiEnabled = true, cloudAllowed = true, sanitizeSecrets = false)
        }
    }
}
