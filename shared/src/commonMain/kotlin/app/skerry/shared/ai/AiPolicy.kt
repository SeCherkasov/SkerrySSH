package app.skerry.shared.ai

import kotlinx.serialization.Serializable

/**
 * Per-host AI policy ("AI under policy" principle, `docs/skerry-product-brief.md`). Model output is
 * an untrusted source; the policy decides whether context can go to the cloud and whether secrets
 * must be scrubbed. Confirmation before command execution is always required regardless of policy
 * (separate UI invariant).
 *
 * - [Strict] — local AI only. Sanitizes secrets. Default for new hosts.
 * - [Balanced] — cloud allowed, secrets scrubbed before sending.
 * - [Permissive] — cloud allowed without sanitization (non-sensitive systems only).
 * - [Off] — AI disabled entirely for this host.
 *
 * Value order matches the UI prototype; serialization is by name, so order doesn't affect backward
 * compatibility of saved hosts.
 */
@Serializable
enum class AiPolicy { Strict, Balanced, Permissive, Off }

/**
 * Resolves [AiPolicy] into concrete runtime permissions. Single source of truth for UI/controllers,
 * so `when(policy)` isn't scattered across layers.
 *
 * @property aiEnabled whether to show AI features for the host at all (false only for [AiPolicy.Off]).
 * @property cloudAllowed whether a request may go to an external (cloud) provider.
 * @property sanitizeSecrets whether to scrub secrets from the prompt before sending (see [SecretRedactor]).
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
