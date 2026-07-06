package app.skerry.ui.ai

import app.skerry.shared.ai.AiPolicy

/** A per-host AI policy option for pickers (connection form, settings). */
data class PolicyOption(val policy: AiPolicy, val icon: String, val title: String, val desc: String)

/**
 * Whether an AI provider endpoint uses plain http:// (excluding localhost/127.0.0.1) — the key and
 * prompt (with secrets, under Permissive) would travel in plaintext.
 */
fun isInsecureAiEndpoint(url: String): Boolean {
    val u = url.trim()
    if (!u.startsWith("http://")) return false
    val host = u.removePrefix("http://")
    return !host.startsWith("localhost") && !host.startsWith("127.0.0.1")
}

val POLICY_OPTIONS = listOf(
    PolicyOption(AiPolicy.Strict, "shield_lock", "Strict — production safety", "Cloud AI is off for this host; nothing is sent to a provider. (Local AI is planned.)"),
    PolicyOption(AiPolicy.Balanced, "tune", "Balanced — cloud allowed", "Cloud AI enabled. Secrets are stripped from every prompt before it is sent."),
    PolicyOption(AiPolicy.Permissive, "science", "Permissive — dev / homelab", "Cloud AI enabled. Prompts are sent as-is, without stripping secrets."),
    PolicyOption(AiPolicy.Off, "block", "Off — no AI", "Disable AI features for this connection."),
)
