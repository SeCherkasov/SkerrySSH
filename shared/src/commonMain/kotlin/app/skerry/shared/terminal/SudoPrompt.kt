package app.skerry.shared.terminal

/** Longest row a prompt is looked for in. A prompt lives at the head of the line, not past it. */
private const val MAX_PROMPT_LENGTH = 512

/** The marker every sudo build and every translation keeps verbatim. */
private const val SUDO_MARKER = "[sudo]"

/** The full-width colon the CJK translations end their prompt on, beside the ASCII one. */
private const val FULLWIDTH_COLON = '：'

/**
 * Whether the character puts no ink on the screen — it either takes a cell and draws nothing in it
 * or joins the cell before it.
 *
 * Judged by what is drawn, not by one Unicode category, because a row is hidden from the predicates
 * below by anything the eye cannot see:
 *
 * * `Cf` is the obvious set — zero-width space, word joiner, BOM, soft hyphen, the bidi marks;
 * * a variation selector is `Mn`, and [CharMetrics.isCombining] covers it, so the emulator folds it
 *   into the *base* cell: the colon's cell becomes two characters and the row no longer ends on `:`;
 * * the tag characters (U+E0020..U+E007F) and the variation selectors supplement are astral, so the
 *   row ends on a low surrogate whose category is `SURROGATE` and not `FORMAT`;
 * * U+2800 and the Hangul fillers are an ordinary symbol and ordinary letters that every font draws
 *   as empty — nothing about their category says so.
 */
private fun Char.isInkless(): Boolean = when (category) {
    CharCategory.FORMAT,
    CharCategory.NON_SPACING_MARK,
    CharCategory.ENCLOSING_MARK,
    CharCategory.SURROGATE,
    CharCategory.CONTROL,
    CharCategory.UNASSIGNED,
    -> true

    else -> this == '\u2800' ||                                    // braille pattern blank
        this == '\u115F' || this == '\u1160' ||                    // Hangul choseong/jungseong filler
        this == '\u3164' || this == '\uFFA0'                       // Hangul filler, and its halfwidth form
}

/**
 * The row as the eye reads it: trailing blanks gone, and with them the characters that take a cell
 * without drawing in it.
 *
 * `trim()` alone is not enough. Kotlin trims `Char.isWhitespace()`, which is the separators plus the
 * ASCII controls; everything [isInkless] names survives it. One such character appended after the
 * colon leaves a row pixel-identical to a real prompt whose last character is no longer `:`, and
 * both predicates below then read it as ordinary output — for [isPasswordPrompt] that means the
 * password typed there is committed to the vault-backed command history and mirrored into panes at
 * an ordinary shell.
 *
 * Stripped at the ends only. Inside the row an invisible character is a real difference:
 * `deploy` followed by a zero-width space and `admin` is not the account `deploy`, and
 * [isNameBoundary] refuses it on purpose.
 */
private fun String.trimInvisible(): String = trim { it.isWhitespace() || it.isInkless() }

/**
 * Where the sudo marker starts on [text], or -1 — folded one way for both predicates.
 *
 * They used to fold it separately, `indexOf(ignoreCase = true)` here and `lowercase()` there, and
 * those two do not agree on every character: U+017F ſ uppercases to `S` but lowercases to itself, so
 * `[ſudo] deploy:` armed the offer on a row the history filter called ordinary text — the one row
 * where a declined offer is followed by a hand-typed password.
 */
private fun sudoMarkerAt(text: String): Int = text.indexOf(SUDO_MARKER, ignoreCase = true)

/**
 * What separates an account name from the words around it in a prompt. Everything else counts as
 * part of the name, non-ASCII included: `-` is a legal name character, so `deploy` must not answer
 * the prompt naming `deploy-admin`, and a look-alike separator (U+2011 for the hyphen, a zero-width
 * space) must not turn that same row back into a match. Unknown characters therefore join the name
 * and the offer is refused — the direction a heuristic guarding a secret has to fail in.
 */
private fun Char.isNameBoundary(): Boolean =
    isWhitespace() || this == FULLWIDTH_COLON || this in ",.:;!?'\"`()[]{}<>|/\\"

/**
 * Whether [line] — one terminal row, as drawn — is sudo asking [username] for their password.
 *
 * Three facts have to hold together, and each one carries a share of the risk:
 *
 * * the literal `[sudo]` is on the row. It is the one part of the prompt no locale translates
 *   (`[sudo] пароль для %p:`, `[sudo] %p 的密码：`), which is why the marker is the anchor and the
 *   word "password" is not — matching on that would fire on ssh's prompt, su's, and any program
 *   that asks for one;
 * * the row ends on a colon, ASCII or full-width. A prompt that has been answered has the shell's
 *   next line after it and no longer ends there;
 * * [username] appears on the row as a whole name. `rootpw` and `targetpw` make sudo ask for
 *   *another* account's password and say so in the prompt — this session's credential is not that
 *   account's, and offering it would send the wrong secret to a program that asks for a right one.
 *
 * A row carrying a bidi override or isolate is refused outright, at its ends as much as in its
 * middle: those reorder what the user reads without changing what matches here, and the whole
 * feature rests on the row on screen being the row that was tested.
 *
 * A custom `Defaults passprompt` that drops the marker is simply not recognised: the offer does not
 * appear and the password is typed by hand, which is the safe direction for a heuristic to fail in.
 *
 * The caller must still require an explicit keypress before sending anything — a remote process can
 * print whatever it likes, this row included.
 */
fun isSudoPasswordPrompt(line: String, username: String): Boolean {
    val name = username.trim()
    if (name.isEmpty()) return false
    // Trimmed before the length is judged: a grid row is always exactly `cols` characters, blanks
    // included, so measuring the raw string would turn every window wider than the cap into one
    // where the offer never appears. What is capped is how much text the prompt is allowed to be.
    val text = line.trimInvisible()
    if (text.length > MAX_PROMPT_LENGTH) return false
    val last = text.lastOrNull() ?: return false
    // Cheapest discriminator first: almost no row of ordinary output ends on a colon, so the
    // full-row scan below runs on the few that could be prompts rather than on every line drawn.
    if (last != ':' && last != FULLWIDTH_COLON) return false
    // Scanned on the row as handed in, not on `text`: the trim removes exactly what this refuses —
    // the overrides and isolates are format characters too — so scanning the trimmed string would
    // let an override at either end through the guard this line is.
    if (line.any { !isSafeDisplayChar(it) }) return false
    // Case-insensitive on the marker only: an account name is case-sensitive on Unix, and folding
    // it would let a prompt for `Deploy` answer for `deploy`.
    val marker = sudoMarkerAt(text)
    if (marker < 0) return false
    return namesUser(text, marker + SUDO_MARKER.length, name)
}

/** Whether [name] occurs in [text] at or after [from] as a whole account name. */
private fun namesUser(text: String, from: Int, name: String): Boolean {
    var at = text.indexOf(name, from)
    while (at >= 0) {
        val before = text.getOrNull(at - 1)
        val after = text.getOrNull(at + name.length)
        if (before?.isNameBoundary() != false && after?.isNameBoundary() != false) return true
        at = text.indexOf(name, at + 1)
    }
    return false
}

/**
 * Whether [line] — the cursor row, as drawn — reads as any program's password prompt.
 *
 * Broader and blunter than [isSudoPasswordPrompt] on purpose: this one decides that what is typed
 * next must be kept out of command history, out of the production-guard dialog and out of a
 * synchronized pane sitting at an ordinary shell. Over-matching costs a command its history entry;
 * under-matching writes a secret to disk, so `cat passwords.txt` losing its history entry is the
 * trade this makes.
 *
 * The keyword list is multilingual for a concrete reason: sudo is translated, and a user who
 * declines the saved-password offer at `[sudo] пароль для deploy:` types the secret by hand. If
 * only the English words were known, that row would not read as a prompt and the password would be
 * tracked as a command.
 */
fun isPasswordPrompt(line: String): Boolean {
    // Deliberately NOT capped by [MAX_PROMPT_LENGTH]. There, refusing a long row means "do not offer
    // the password", which is safe; here it would mean "this is not a secret, track it as a command".
    // A terminal wider than the cap, or a host padding the row with invisible combining characters,
    // would then put a hand-typed password into the vault-backed history and mirror it into every
    // synchronized pane. The scan is bounded by the row's own width either way.
    val text = line.trimInvisible()
    val last = text.lastOrNull() ?: return false
    if (last != ':' && last != FULLWIDTH_COLON) return false
    // sudo's own marker counts as a hint of its own, so this can never fail to recognise a row the
    // offer recognised. sudo is translated into more locales than any keyword list will hold
    // (`[sudo] парола за deploy:`, `[sudo] geslo za deploy:`), and the row where the two could
    // disagree is exactly the one where a declined offer is followed by a hand-typed password.
    if (sudoMarkerAt(text) >= 0) return true
    val folded = text.lowercase()
    return PASSWORD_PROMPT_HINTS.any { it in folded }
}

/**
 * Words that make a row ending in a colon a password prompt. English first (the overwhelming
 * default for a server locale), then the translations sudo, ssh, su and polkit ship — the languages
 * whose prompt a user of this client is realistically sitting in front of.
 */
private val PASSWORD_PROMPT_HINTS = listOf(
    "password", "passphrase", "passcode", "verification code", "pin",
    "otp", "one-time", "token", "2fa", "mfa", "authenticator", "challenge",
    "пароль", "passwort", "mot de passe", "contraseña", "senha", "wachtwoord",
    "hasło", "lösenord", "salasana", "adgangskode", "passord", "heslo", "jelszó",
    "parolă", "şifre", "密码", "密碼", "パスワード", "암호", "비밀번호", "كلمة المرور",
    "парола", "geslo", "lozinka", "mật khẩu", "κωδικός", "lykilorð", "slaptažodis", "parole",
)
