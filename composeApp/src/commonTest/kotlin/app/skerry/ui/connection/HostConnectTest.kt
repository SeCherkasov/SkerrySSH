package app.skerry.ui.connection

import app.skerry.shared.container.ContainerRuntime
import app.skerry.shared.container.ContainerSpec
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.terminal.PromptRow
import app.skerry.ui.terminal.OFFER_DWELL_MS
import app.skerry.ui.terminal.SudoPasswordOffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure helpers wiring a host to a session (host → address/label, keychain secret → auth
 * method), shared by the desktop design layer and mobile UI. No color/Compose involved here —
 * models only. A host references its keychain secret by `credentialId`: the caller resolves it into
 * a [Credential] and calls [toSshAuth].
 */
class HostConnectTest {

    private fun host(
        address: String = "10.0.0.1",
        port: Int = 22,
        username: String = "root",
    ) = Host(id = "h1", label = "Prod", address = address, port = port, username = username)

    @Test
    fun target_maps_address_port_and_user() {
        assertEquals(
            SshTarget(host = "example.com", port = 2222, username = "deploy", keepAliveSeconds = 30),
            host(address = "example.com", port = 2222, username = "deploy").toTarget(),
        )
    }

    @Test
    fun target_carries_the_keep_alive_interval() {
        assertEquals(120, host().copy(keepAliveSeconds = 120).toTarget().keepAliveSeconds)
        assertEquals(0, host().copy(keepAliveSeconds = 0).toTarget().keepAliveSeconds)
    }

    @Test
    fun subtitle_is_user_at_address_colon_port() {
        assertEquals("deploy@example.com:2222", host(address = "example.com", port = 2222, username = "deploy").connectionSubtitle())
    }

    /**
     * Every field spliced into the caption belongs to whoever wrote the profile, and a shared one
     * was written by a team member: a bidi override in the username reverses the line the user
     * reads before typing a password into it. Escapes, not the characters themselves.
     */
    @Test
    fun subtitle_drops_a_bidi_override_in_the_username() {
        assertEquals("root@example.com:22", host(address = "example.com", username = "ro\u202Eot").connectionSubtitle())
    }

    @Test
    fun subtitle_drops_the_zero_width_formatters_in_the_address() {
        assertEquals("root@example.com:22", host(address = "exam\u200Bple.com").connectionSubtitle())
    }

    /** A local shell has no host to name; a path made only of such characters must not blank it. */
    @Test
    fun local_subtitle_falls_back_when_the_path_filters_away() {
        val local = host(address = "\u202E\u200B").copy(connectionType = ConnectionType.LOCAL)
        assertEquals("local shell", local.connectionSubtitle())
    }

    @Test
    fun container_target_carries_the_spec_and_the_host_fields() {
        val profile = host().copy(
            connectionType = ConnectionType.CONTAINER,
            container = ContainerSpec(runtime = ContainerRuntime.DOCKER, target = "web"),
        )
        val target = profile.toTarget()
        assertEquals(ConnectionType.CONTAINER, target.connectionType)
        // The SSH fields still describe the host running the container CLI.
        assertEquals("10.0.0.1", target.host)
        assertEquals("root", target.username)
        assertEquals(ContainerSpec(runtime = ContainerRuntime.DOCKER, target = "web"), target.container)
    }

    @Test
    fun container_subtitle_names_the_container_and_its_host() {
        val docker = host().copy(
            connectionType = ConnectionType.CONTAINER,
            container = ContainerSpec(runtime = ContainerRuntime.DOCKER, target = "web"),
        )
        assertEquals("web · root@10.0.0.1", docker.connectionSubtitle())

        val pod = host().copy(
            connectionType = ConnectionType.CONTAINER,
            container = ContainerSpec(runtime = ContainerRuntime.KUBERNETES, target = "api-0", namespace = "prod"),
        )
        assertEquals("prod/api-0 · root@10.0.0.1", pod.connectionSubtitle())
    }

    @Test
    fun container_subtitle_without_a_spec_falls_back_to_the_host() {
        val broken = host().copy(connectionType = ConnectionType.CONTAINER)
        assertEquals("root@10.0.0.1:22", broken.connectionSubtitle())
    }

    @Test
    fun password_credential_maps_to_password_auth() {
        val c = Credential("c1", "pw", CredentialSecret.Password("s3cr3t"))
        assertEquals(SshAuth.Password("s3cr3t"), c.toSshAuth())
    }

    @Test
    fun private_key_credential_maps_with_passphrase() {
        val c = Credential("c2", "key", CredentialSecret.PrivateKey("PEMDATA", "phrase"))
        assertEquals(SshAuth.PublicKey("PEMDATA", "phrase"), c.toSshAuth())
    }

    @Test
    fun private_key_credential_without_passphrase_keeps_null() {
        val c = Credential("c3", "key", CredentialSecret.PrivateKey("PEMDATA", null))
        assertEquals(SshAuth.PublicKey("PEMDATA", null), c.toSshAuth())
    }

    @Test
    fun certificate_credential_maps_to_certificate_auth() {
        val c = Credential("c4", "cert", CredentialSecret.Certificate("PEMDATA", "CERTDATA", "phrase"))
        assertEquals(SshAuth.Certificate("PEMDATA", "CERTDATA", "phrase"), c.toSshAuth())
    }

    @Test
    fun certificate_credential_without_passphrase_keeps_null() {
        val c = Credential("c5", "cert", CredentialSecret.Certificate("PEMDATA", "CERTDATA", null))
        assertEquals(SshAuth.Certificate("PEMDATA", "CERTDATA", null), c.toSshAuth())
    }

    // --- The sudo offer (issue #360): what a session may hand back to a sudo prompt ---

    @Test
    fun sudo_offer_is_absent_while_the_setting_is_off() {
        assertNull(sudoOfferFor(sshTarget("deploy"), SshAuth.Password("hunter2"), enabled = false))
    }

    @Test
    fun sudo_offer_needs_a_password_credential() {
        assertNull(sudoOfferFor(sshTarget("deploy"), SshAuth.PublicKey("PEMDATA", null), enabled = true))
        assertNull(sudoOfferFor(sshTarget("deploy"), SshAuth.Password(""), enabled = true))
    }

    /** With no account name there is nothing to match a prompt against — see [SudoPasswordOffer]. */
    @Test
    fun sudo_offer_needs_a_username() {
        assertNull(sudoOfferFor(sshTarget("   "), SshAuth.Password("hunter2"), enabled = true))
    }

    @Test
    fun sudo_offer_answers_a_prompt_for_the_connected_account() {
        val offer = sudoOfferFor(sshTarget("deploy"), SshAuth.Password("hunter2"), enabled = true)
        assertNotNull(offer)
        offer.observe(PromptRow(0, "[sudo] password for deploy: "), now = 0)
        assertEquals("hunter2", offer.take(now = OFFER_DWELL_MS))

        offer.observe(PromptRow(0, "[sudo] password for root: "), now = 0)
        assertNull(offer.take(now = OFFER_DWELL_MS), "a prompt for another account was answered")
    }

    /**
     * A container profile execs into an image once the host's SSH leg is up: the shell on screen is
     * not the account that authenticated, and the host's password has no business in it.
     */
    @Test
    fun sudo_offer_is_absent_for_a_container_profile() {
        val target = sshTarget("deploy").copy(connectionType = ConnectionType.CONTAINER)
        assertNull(sudoOfferFor(target, SshAuth.Password("hunter2"), enabled = true))
    }

    /** The hint has to name whose secret it is about to send — an inner ssh looks the same. */
    @Test
    fun sudo_offer_names_the_account_and_host() {
        val offer = sudoOfferFor(sshTarget("deploy"), SshAuth.Password("hunter2"), enabled = true)
        assertNotNull(offer)
        assertEquals("deploy@10.0.0.5", offer.account)
    }

    /** The secret must not reach a log or a crash report through a stray interpolation. */
    @Test
    fun sudo_offer_redacts_its_password() {
        val offer = SudoPasswordOffer("deploy", "deploy@10.0.0.5", "hunter2")
        assertTrue("hunter2" !in offer.toString(), offer.toString())
    }

    private fun sshTarget(username: String) = SshTarget(host = "10.0.0.5", port = 22, username = username)

    @Test
    fun short_cipher_drops_vendor_suffix() {
        assertEquals("chacha20-poly1305", shortCipher("chacha20-poly1305@openssh.com"))
        assertEquals("aes256-gcm", shortCipher("aes256-gcm@openssh.com"))
    }

    @Test
    fun short_cipher_keeps_plain_name() {
        assertEquals("aes256-ctr", shortCipher("aes256-ctr"))
    }

    @Test
    fun short_cipher_trims_and_handles_blank_or_null() {
        assertEquals("chacha20-poly1305", shortCipher("  chacha20-poly1305@openssh.com  "))
        assertEquals(null, shortCipher(null))
        assertEquals(null, shortCipher("   "))
        assertEquals(null, shortCipher("@"))
    }
}
