package app.skerry.ui.ai

import app.skerry.shared.ai.AiPolicy

/** Вариант per-host AI-политики для пикеров (форма соединения, настройки). */
data class PolicyOption(val policy: AiPolicy, val icon: String, val title: String, val desc: String)

val POLICY_OPTIONS = listOf(
    PolicyOption(AiPolicy.Strict, "shield_lock", "Strict — production safety", "Local AI only. Every suggestion needs confirmation. Secrets sanitized before any prompt."),
    PolicyOption(AiPolicy.Balanced, "tune", "Balanced — cloud allowed", "Local AI by default. Cloud AI with explicit opt-in per request."),
    PolicyOption(AiPolicy.Permissive, "science", "Permissive — dev / homelab", "Any provider. Auto-suggestions without confirmation."),
    PolicyOption(AiPolicy.Off, "block", "Off — no AI", "Disable AI features for this connection."),
)
