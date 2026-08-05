package app.skerry.shared.terminal.highlight

import app.skerry.shared.terminal.COMMON_COMMANDS
import app.skerry.shared.terminal.SUBCOMMANDS

/**
 * What the tokenizer is allowed to call a command. Deliberately a lookup and not a check against the
 * host: asking the shell (`command -v`) would cost a round-trip per keystroke and pollute the user's
 * own history, so the vocabulary is local and necessarily incomplete. An unknown word is therefore
 * left uncolored rather than marked as wrong — the failure mode is "less green", not a false alarm.
 */
interface CommandVocabulary {
    fun isCommand(word: String): Boolean
    fun isSubcommand(command: String, word: String): Boolean

    /** Knows nothing — every word stays uncolored. For tests and for the highlight-off path. */
    object Empty : CommandVocabulary {
        override fun isCommand(word: String): Boolean = false
        override fun isSubcommand(command: String, word: String): Boolean = false
    }
}

/** Shell keywords and builtins: no binary behind them, but they open a command position all the same. */
val SHELL_KEYWORDS: Set<String> = setOf(
    "if", "then", "elif", "else", "fi", "for", "while", "until", "do", "done",
    "case", "esac", "select", "function", "in",
    "cd", "echo", "export", "source", "alias", "unalias", "set", "unset", "read",
    "return", "exit", "shift", "test", "eval", "trap", "wait", "jobs", "kill",
    "pushd", "popd", "dirs", "local", "declare", "typeset", "readonly", "printf",
)

/**
 * Words that keep the command position open: what follows them is still a command, not an argument
 * (`sudo systemctl restart nginx` — both `sudo` and `systemctl` are commands).
 */
val COMMAND_PREFIXES: Set<String> = setOf(
    "sudo", "doas", "env", "time", "nohup", "command", "exec", "watch", "nice", "ionice", "stdbuf",
)

/**
 * Base userland present on any Unix host. [COMMON_COMMANDS] is a list of ready-made *lines* for
 * autocomplete, not a command inventory, so the everyday binaries are enumerated here.
 */
val BASE_COMMANDS: Set<String> = setOf(
    "ls", "cat", "less", "more", "head", "tail", "grep", "egrep", "fgrep", "rg", "ag",
    "find", "locate", "which", "whereis", "file", "stat", "du", "df", "mount", "umount",
    "cp", "mv", "rm", "mkdir", "rmdir", "ln", "touch", "chmod", "chown", "chgrp", "install",
    "tar", "gzip", "gunzip", "zip", "unzip", "xz", "zstd",
    "ps", "top", "htop", "btop", "kill", "pkill", "pgrep", "nice", "renice", "lsof", "strace",
    "free", "uptime", "uname", "hostname", "whoami", "id", "who", "w", "last", "groups",
    "man", "info", "history", "date", "cal", "sleep", "seq", "yes", "tee", "xargs",
    "sed", "awk", "sort", "uniq", "wc", "cut", "paste", "tr", "diff", "patch", "jq", "yq",
    "curl", "wget", "ssh", "scp", "sftp", "rsync", "ping", "traceroute", "dig", "nslookup",
    "netstat", "ss", "ip", "ifconfig", "iptables", "nft", "nc", "tcpdump", "openssl",
    "vim", "vi", "nvim", "nano", "emacs", "tmux", "screen", "make", "cmake", "gcc", "g++",
    "python", "python3", "pip", "pip3", "node", "npx", "yarn", "pnpm", "java", "javac",
    "go", "rustc", "ruby", "perl", "php", "psql", "mysql", "redis-cli", "sqlite3",
    "adb", "gradle", "gradlew", "mvn", "podman", "helm", "terraform", "ansible", "zfs", "btrfs",
    "useradd", "usermod", "userdel", "groupadd", "passwd", "su", "crontab", "dmesg", "lsblk",
    "fdisk", "mkfs", "swapon", "sync", "shutdown", "reboot", "systemd-analyze", "journalctl",
    "apt-get", "dpkg", "yum", "dnf", "pacman", "zypper", "snap", "flatpak", "rpm",
)

/**
 * Vocabulary for one session: the built-in catalogue ([BASE_COMMANDS], [COMMON_COMMANDS],
 * [SUBCOMMANDS]), shell keywords, and the first word of everything this session ran ([history],
 * newest first) — so a host's own tooling turns green after its first use.
 */
class SessionVocabulary(private val history: List<String> = emptyList()) : CommandVocabulary {

    // Built lazily: this is constructed while a terminal session is being set up, and folding a few
    // hundred names into a set there would only add latency to the moment the screen first appears.
    private val commands: Set<String> by lazy { buildCommands() }

    private fun buildCommands(): Set<String> = buildSet {
        addAll(SHELL_KEYWORDS)
        addAll(COMMAND_PREFIXES)
        addAll(BASE_COMMANDS)
        addAll(SUBCOMMANDS.keys)
        // COMMON_COMMANDS holds ready-made lines ("ls -la", "git status"), not bare command names.
        COMMON_COMMANDS.forEach { line -> firstWord(line)?.let { add(it) } }
        history.forEach { line -> firstWord(line)?.let { add(it) } }
    }

    override fun isCommand(word: String): Boolean = word in commands

    override fun isSubcommand(command: String, word: String): Boolean =
        SUBCOMMANDS[command]?.contains(word) == true
}

/**
 * The command name a history line starts with, or `null` when it doesn't start with one — a path
 * (`./deploy.sh`), an assignment (`FOO=1`) or an option is not a name worth learning.
 */
private fun firstWord(line: String): String? {
    val word = line.trimStart().substringBefore(' ').trim()
    if (word.isEmpty()) return null
    if (word.any { it == '/' || it == '=' || it == '$' }) return null
    if (!word[0].isLetter() && word[0] != '_') return null
    return word
}
