package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What [isSudoPasswordPrompt] may and may not answer `true` to. The detector decides whether the
 * client offers back the password this session authenticated with, so a false positive is the
 * expensive direction: it puts the offer under a line that is not sudo asking this user.
 */
class SudoPromptTest {

    @Test
    fun `the default English prompt is recognised`() {
        assertTrue(isSudoPasswordPrompt("[sudo] password for deploy:", "deploy"))
        assertTrue(isSudoPasswordPrompt("[sudo] password for deploy: ", "deploy"))
    }

    /**
     * Every translation keeps the literal `[sudo]` — only the words around it change — which is
     * what makes the marker usable as the anchor instead of the word "password".
     */
    @Test
    fun `localized prompts are recognised`() {
        assertTrue(isSudoPasswordPrompt("[sudo] пароль для deploy:", "deploy"))
        assertTrue(isSudoPasswordPrompt("[sudo] Passwort für deploy:", "deploy"))
        // French puts a space before the colon.
        assertTrue(isSudoPasswordPrompt("[sudo] Mot de passe de deploy :", "deploy"))
        assertTrue(isSudoPasswordPrompt("[sudo] hasło użytkownika deploy:", "deploy"))
        // Chinese ends on a full-width colon.
        assertTrue(isSudoPasswordPrompt("[sudo] deploy 的密码：", "deploy"))
        assertTrue(isSudoPasswordPrompt("[sudo] deploy のパスワード:", "deploy"))
    }

    /**
     * `rootpw`/`targetpw` make sudo ask for someone else's password. The prompt names whose it
     * wants, and this session's credential is not it.
     */
    @Test
    fun `a prompt naming another user is refused`() {
        assertFalse(isSudoPasswordPrompt("[sudo] password for root:", "deploy"))
        assertFalse(isSudoPasswordPrompt("[sudo] password for deploy-admin:", "deploy"))
        assertFalse(isSudoPasswordPrompt("[sudo] password for xdeploy:", "deploy"))
    }

    /** Other password prompts belong to other credentials — ssh's, su's, a program's own. */
    @Test
    fun `prompts that are not sudo are refused`() {
        assertFalse(isSudoPasswordPrompt("deploy@10.0.0.5's password:", "deploy"))
        assertFalse(isSudoPasswordPrompt("Password:", "deploy"))
        assertFalse(isSudoPasswordPrompt("Enter passphrase for key '/home/deploy/.ssh/id_ed25519':", "deploy"))
        assertFalse(isSudoPasswordPrompt("[sudo] password for deploy", "deploy"))
        assertFalse(isSudoPasswordPrompt("$ sudo apt update", "deploy"))
        assertFalse(isSudoPasswordPrompt("", "deploy"))
    }

    /** A session with no username to match against can never have a prompt addressed to it. */
    @Test
    fun `a blank username never matches`() {
        assertFalse(isSudoPasswordPrompt("[sudo] password for deploy:", ""))
        assertFalse(isSudoPasswordPrompt("[sudo] password for :", "  "))
    }

    /**
     * A prompt sudo has already answered scrolls up and stays on screen. The marker is only read
     * where the cursor is, but the line itself must still end at the colon — text typed after it
     * means the prompt was answered and the shell moved on.
     */
    @Test
    fun `text after the colon ends the prompt`() {
        assertFalse(isSudoPasswordPrompt("[sudo] password for deploy: uptime", "deploy"))
    }

    /**
     * The account-name boundary has to hold for characters that only look like separators, or the
     * `deploy-admin` case above is reopened by a hyphen the row draws identically (U+2011) and by a
     * zero-width space. Anything not a known separator counts as part of the name: the offer is then
     * refused, which is the direction this is allowed to fail in.
     */
    @Test
    fun `look-alike separators do not split the account name`() {
        assertFalse(isSudoPasswordPrompt("[sudo] password for deploy\u2011admin:", "deploy"))
        assertFalse(isSudoPasswordPrompt("[sudo] password for deploy\u200Badmin:", "deploy"))
    }

    /** A row that reorders what it draws is not the row that was matched, so it is not matched. */
    @Test
    fun `a row carrying bidi controls is refused`() {
        assertFalse(isSudoPasswordPrompt("[sudo] password for \u202Eyolped:", "deploy"))
    }

    /**
     * At the ends of the row as much as in the middle. The trim that keeps a trailing zero-width
     * character from hiding a prompt from history removes the overrides too — they are the same
     * Unicode category — so the offer has to judge the row it was handed, not the trimmed one.
     * History still tracks the row as a prompt: over-matching there costs a command its entry,
     * under-matching writes a password to disk.
     */
    @Test
    fun `a bidi override at the edge of the row is refused`() {
        assertFalse(isSudoPasswordPrompt("\u202E[sudo] password for deploy:", "deploy"))
        assertFalse(isSudoPasswordPrompt("[sudo] password for deploy:\u202E", "deploy"))
        assertTrue(isPasswordPrompt("[sudo] password for deploy:\u202E"))
    }

    /** The marker is sudo's, whatever case a build prints it in; the account name is Unix's. */
    @Test
    fun `the marker folds case and the account name does not`() {
        assertTrue(isSudoPasswordPrompt("[SUDO] password for deploy:", "deploy"))
        assertFalse(isSudoPasswordPrompt("[sudo] password for Deploy:", "deploy"))
    }

    /** A row longer than a prompt is output that happens to contain one, not a prompt. */
    @Test
    fun `an overlong row is refused`() {
        assertFalse(isSudoPasswordPrompt("deploy@web ".repeat(60) + "[sudo] password for deploy:", "deploy"))
    }

    /**
     * What the terminal actually hands in is a grid row: exactly as many characters as the window
     * has columns, blanks included. Measuring that instead of the text would make every window wider
     * than the cap one where the offer never appears.
     */
    @Test
    fun `a prompt padded out to the window width is still recognised`() {
        val row = "[sudo] password for deploy:".padEnd(700, ' ')
        assertTrue(isSudoPasswordPrompt(row, "deploy"), "a wide window switched the offer off")
        assertTrue(isPasswordPrompt(row))
    }

    /**
     * [isPasswordPrompt] is the blunt one: it decides what stays out of history, out of the
     * production guard and out of a synchronized pane. It has to know every prompt the sudo detector
     * knows — a user who declines the offer at a translated prompt types the secret by hand, and a
     * row this did not recognise would put it in the vault-backed history.
     */
    @Test
    fun `a password prompt is recognised in the languages sudo is translated into`() {
        assertTrue(isPasswordPrompt("[sudo] password for deploy:"))
        assertTrue(isPasswordPrompt("[sudo] пароль для deploy:"))
        assertTrue(isPasswordPrompt("[sudo] Passwort für deploy:"))
        assertTrue(isPasswordPrompt("[sudo] deploy 的密码："))
        assertTrue(isPasswordPrompt("[sudo] deploy のパスワード:"))
        assertTrue(isPasswordPrompt("deploy@10.0.0.5's password:"))
        assertTrue(isPasswordPrompt("Enter passphrase for key '/home/deploy/.ssh/id_ed25519':"))
    }

    /**
     * The length cap belongs to the offer, not to this predicate. Refusing a long row here would
     * mean "not a secret, track it as a command", so a terminal wider than the cap — or a host
     * padding the row with invisible characters — would put a hand-typed password into the
     * vault-backed history and mirror it into every synchronized pane.
     */
    @Test
    fun `a row too long to offer on is still a password prompt`() {
        val wide = "deploy@web ".repeat(60) + "[sudo] password for deploy:"
        assertTrue(isPasswordPrompt(wide), "a wide terminal switched off the secret-input detection")
        assertFalse(isSudoPasswordPrompt(wide, "deploy"), "the offer must still refuse an overlong row")
    }

    /**
     * The blunt detector has to be a superset of the precise one, or the row where a user declines
     * the offer and types the password by hand is the row that is not treated as a secret. sudo is
     * translated into more locales than any keyword list holds, so the marker itself counts.
     */
    @Test
    fun `every row the offer recognises is a password prompt`() {
        val locales = listOf(
            "[sudo] парола за deploy:",   // bg
            "[sudo] geslo za deploy:",    // sl
            "[sudo] lozinka za deploy:",  // hr
            "[sudo] mật khẩu cho deploy:",
            // U+017F uppercases to S but lowercases to itself, so a marker folded one way and
            // matched the other way disagreed on exactly this row.
            "[\u017Fudo] deploy:",
        )
        for (row in locales) {
            assertTrue(isSudoPasswordPrompt(row, "deploy"), row)
            assertTrue(isPasswordPrompt(row), "the offer armed on a row history does not treat as secret: $row")
        }
    }

    /**
     * A character that occupies a cell without drawing anything must not decide what a secret is.
     * `trim()` removes `Char.isWhitespace()` only, and every format character (category Cf) survives
     * it — one appended after the colon leaves a row pixel-identical to a real prompt whose last
     * character is no longer ':'. Read as ordinary output, the password typed there is committed to
     * the vault-backed command history and mirrored into panes at an ordinary shell.
     */
    @Test
    fun `a trailing invisible character does not hide a password prompt`() {
        val invisible = listOf('\u200B', '\u200E', '\u2060', '\uFEFF', '\u00AD')
        for (c in invisible) {
            val row = "[sudo] password for deploy:" + c
            val at = c.code.toString(16)
            assertTrue(isPasswordPrompt(row), "history stopped seeing a prompt ending in U+$at")
            assertTrue(isSudoPasswordPrompt(row, "deploy"), "the offer stopped seeing U+$at")
        }
    }

    /**
     * Category Cf is not the whole of what draws nothing. A variation selector is a non-spacing
     * mark and the emulator folds it into the colon's own cell; a tag character is an astral
     * codepoint, so the row ends on a surrogate and its category is neither; U+2800 and the Hangul
     * fillers are an ordinary symbol and ordinary letters that every font draws as empty. Each one
     * appended after the colon leaves a row the user cannot tell from the prompt, and the row still
     * has to read as a prompt to both predicates.
     */
    @Test
    fun `a trailing inkless character does not hide a password prompt`() {
        val inkless = listOf(
            "\uFE0E",        // variation selector-15, folded into the base cell
            "\uDB40\uDC20",  // U+E0020 tag space, an astral format character
            "\u2800",        // braille pattern blank
            "\u3164",        // Hangul filler
        )
        for (c in inkless) {
            val row = "[sudo] password for deploy:" + c
            val at = c.map { it.code.toString(16) }.joinToString("+")
            assertTrue(isPasswordPrompt(row), "history stopped seeing a prompt ending in U+$at")
            assertTrue(isSudoPasswordPrompt(row, "deploy"), "the offer stopped seeing U+$at")
        }
    }

    /** Ordinary output must still reach history: over-matching costs every command on the row. */
    @Test
    fun `an ordinary line is not a password prompt`() {
        assertFalse(isPasswordPrompt("deploy@web:~$ cat passwords.txt"))
        assertFalse(isPasswordPrompt("total 48"))
        assertFalse(isPasswordPrompt(""))
    }
}
