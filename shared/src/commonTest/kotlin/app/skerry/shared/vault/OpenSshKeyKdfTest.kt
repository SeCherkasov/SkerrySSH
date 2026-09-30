package app.skerry.shared.vault

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenSshKeyKdfTest {

    @Test
    fun `reads the rounds an encrypted key asks for`() {
        assertEquals(OpenSshKdf.Rounds(16), readOpenSshKdf(ENCRYPTED_ED25519))
    }

    /** The count is the key author's to choose, up to uint32 max — it must not come back negative. */
    @Test
    fun `reads a rounds count past Int range as it is`() {
        assertEquals(OpenSshKdf.Rounds(0xFFFFFFFFL), readOpenSshKdf(withRounds(ENCRYPTED_ED25519, 0xFFFFFFFFL)))
    }

    @Test
    fun `an unencrypted key costs no rounds`() {
        assertEquals(OpenSshKdf.Rounds(0), readOpenSshKdf(UNENCRYPTED_ED25519))
    }

    @Test
    fun `text sshj would not read as openssh-key-v1 is not one`() {
        assertEquals(OpenSshKdf.NotOpenSsh, readOpenSshKdf("-----BEGIN RSA PRIVATE KEY-----\nMIIEow==\n-----END RSA PRIVATE KEY-----"))
        assertEquals(OpenSshKdf.NotOpenSsh, readOpenSshKdf(""))
    }

    @Test
    fun `an openssh key whose header can't be read is unreadable`() {
        assertEquals(OpenSshKdf.Unreadable, readOpenSshKdf("-----BEGIN OPENSSH PRIVATE KEY-----\n!!not base64!!\n-----END OPENSSH PRIVATE KEY-----"))
        assertEquals(OpenSshKdf.Unreadable, readOpenSshKdf("-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEA\n-----END OPENSSH PRIVATE KEY-----"))
        assertEquals(OpenSshKdf.Unreadable, readOpenSshKdf(ENCRYPTED_ED25519.substringBefore("-----END")))
    }

    /** sshj decodes with java.util.Base64, which does not insist on the `=` padding. */
    @Test
    fun `reads a body stripped of its padding`() {
        val hostile = withRounds(ENCRYPTED_ED25519, 1L shl 30)
        assertTrue("=" in hostile)
        assertEquals(OpenSshKdf.Rounds(1L shl 30), readOpenSshKdf(hostile.replace("=", "")))
    }

    /** sshj takes any first line that starts `-----BEGIN`, ends `PRIVATE KEY-----` and names OPENSSH. */
    @Test
    fun `reads the block sshj reads when the header carries extra text`() {
        val hostile = withRounds(ENCRYPTED_ED25519, 1L shl 30)
            .replace(OPENSSH_BEGIN, "$OPENSSH_BEGIN PRIVATE KEY-----")
        assertEquals(OpenSshKdf.Rounds(1L shl 30), readOpenSshKdf(hostile))
        assertEquals(OpenSshKdf.Rounds(1L shl 30), readOpenSshKdf(hostile + ENCRYPTED_ED25519))
    }

    @Test
    fun `the cost is acceptable only when it is known and within the cap`() {
        assertTrue(kdfCostAcceptable(ENCRYPTED_ED25519))
        assertTrue(kdfCostAcceptable(UNENCRYPTED_ED25519))
        assertTrue(kdfCostAcceptable("-----BEGIN RSA PRIVATE KEY-----\nMIIEow==\n-----END RSA PRIVATE KEY-----"))
        assertFalse(kdfCostAcceptable(withRounds(ENCRYPTED_ED25519, MAX_KDF_ROUNDS + 1)))
        assertFalse(kdfCostAcceptable("-----BEGIN OPENSSH PRIVATE KEY-----\n!!not base64!!\n-----END OPENSSH PRIVATE KEY-----"))
    }
}

private const val OPENSSH_BEGIN = "-----BEGIN OPENSSH PRIVATE KEY-----"

/** `ssh-keygen -t ed25519 -a 16 -N pw`: aes256-ctr under bcrypt, 16 rounds. */
internal val ENCRYPTED_ED25519 = """
    -----BEGIN OPENSSH PRIVATE KEY-----
    b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABCN1kB7pk
    DWcBFtFlWU5Y/bAAAAEAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIEIi3b4260hBxLWV
    bDKwbJsXRGLfT7OcJfotCBsr/eMPAAAAkDkDfY46+uk3LBYXfm/CjmQt3p8mfwB8/Ff4S8
    e8+jvleUFDhKKGUChSH0PRtBmtwe5yfyRnRv7kAowutgHRmCzTG9a3Kd9fCRNLa5mGTuLn
    SECvCHdfdq+a1pzympcPJcAWowb6JCm/FefA82OQHbt/0m+uLsIbmce5A07TtFt9RohLNw
    azm75EpbOHj16Dkw==
    -----END OPENSSH PRIVATE KEY-----
""".trimIndent() + "\n"

internal const val ENCRYPTED_ED25519_PASSPHRASE = "pw"

private val UNENCRYPTED_ED25519 = """
    -----BEGIN OPENSSH PRIVATE KEY-----
    b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
    QyNTUxOQAAACCHmK+eOLE/3SmTEHz2mQerUTWuK10g2yXsCeRmqBhDJwAAAJCTquJek6ri
    XgAAAAtzc2gtZWQyNTUxOQAAACCHmK+eOLE/3SmTEHz2mQerUTWuK10g2yXsCeRmqBhDJw
    AAAECj4nk0xG00zyQDEYjZzkq4DYaRGzTDQCa722CqWQsnKIeYr544sT/dKZMQfPaZB6tR
    Na4rXSDbJewJ5GaoGEMnAAAADGFsaWNlQHNrZXJyeQE=
    -----END OPENSSH PRIVATE KEY-----
""".trimIndent()

/**
 * [pem] with its bcrypt rounds rewritten — what a hostile key looks like. The count is the last
 * uint32 of kdfoptions: magic (15) + "aes256-ctr" (4+10) + "bcrypt" (4+6) + options length (4) +
 * salt (4+16).
 */
internal fun withRounds(pem: String, rounds: Long): String {
    val lines = pem.trim().lines()
    val bytes = Base64.Default.decode(lines.drop(1).dropLast(1).joinToString(""))
    val at = 15 + 14 + 10 + 4 + 20
    for (i in 0 until 4) bytes[at + i] = (rounds shr (24 - 8 * i)).toByte()
    return "${lines.first()}\n${Base64.Default.encode(bytes).chunked(70).joinToString("\n")}\n${lines.last()}\n"
}
