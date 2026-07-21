package app.skerry.ui.snippet

/**
 * Starter snippets offered on an empty library — enough to show what categories are for without the
 * user typing five commands first. Labels and commands are user data (they land in the vault and
 * sync), so they stay in English rather than going through UI resources; only the button offering
 * them is localized.
 *
 * Installed on an explicit tap, never automatically: a fresh install syncing into an existing
 * account starts with an empty library too, and auto-seeding there would push a second copy of the
 * pack to every other device.
 */
val STARTER_SNIPPETS: List<SnippetDraft> = listOf(
    SnippetDraft(label = "Disk usage", command = "df -h", tags = listOf("disk")),
    SnippetDraft(label = "Largest directories here", command = "du -sh * | sort -rh | head -20", tags = listOf("disk")),
    SnippetDraft(label = "Listening ports", command = "ss -ltnp", tags = listOf("net")),
    SnippetDraft(label = "Top processes by memory", command = "ps aux --sort=-%mem | head -15", tags = listOf("monitoring")),
    SnippetDraft(label = "Running containers", command = "docker ps", tags = listOf("docker")),
    SnippetDraft(label = "Container logs (last 100)", command = "docker logs --tail 100 -f ", tags = listOf("docker")),
    SnippetDraft(label = "Failed services", command = "systemctl --failed", tags = listOf("monitoring")),
    SnippetDraft(label = "Service status", command = "systemctl status ", tags = listOf("monitoring")),
    SnippetDraft(label = "PostgreSQL shell", command = "psql -U postgres", tags = listOf("db")),
    SnippetDraft(label = "MySQL shell", command = "mysql -u root -p", tags = listOf("db")),
)

/**
 * Fill an empty library with [STARTER_SNIPPETS] and return how many were added. A non-empty library
 * is left alone (0) — the offer is only shown on an empty one, but the guard keeps a double tap from
 * duplicating the pack.
 */
fun SnippetManager.installStarterPack(): Int {
    if (snippets.isNotEmpty()) return 0
    STARTER_SNIPPETS.forEach { save(it) }
    return STARTER_SNIPPETS.size
}
