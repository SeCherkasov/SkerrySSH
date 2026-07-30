package app.skerry.ui.host

import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.host.MAX_NOTES_LENGTH
import app.skerry.ui.identity.CredentialDraft
import app.skerry.ui.identity.CredentialKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class NewConnectionFormStateTest {

    @Test
    fun defaults_port_22_and_blank_rest() {
        val f = NewConnectionFormState()
        assertEquals("22", f.port)
        assertFalse(f.canSave) // name/address/username blank
    }

    @Test
    fun requires_name_address_username_and_valid_port() {
        val f = NewConnectionFormState().apply {
            name = "prod-web-01"; address = "192.168.1.45"; username = "root"
        }
        assertTrue(f.canSave)
        f.username = "   "
        assertFalse(f.canSave)
    }

    @Test
    fun invalid_or_out_of_range_port_blocks_save() {
        val f = NewConnectionFormState().apply {
            name = "h"; address = "a"; username = "u"
        }
        f.port = "abc"; assertFalse(f.canSave)
        f.port = "0"; assertFalse(f.canSave)
        f.port = "70000"; assertFalse(f.canSave)
        f.port = "2222"; assertTrue(f.canSave)
    }

    @Test
    fun toDraft_trims_and_maps_blank_group_to_null() {
        val f = NewConnectionFormState().apply {
            name = "  prod  "; address = " 10.0.0.1 "; port = " 2222 "; username = " root "; group = "  "
        }
        val draft = f.toDraft(id = "keep-me")
        assertEquals("keep-me", draft.id)
        assertEquals("prod", draft.label)
        assertEquals("10.0.0.1", draft.address)
        assertEquals(2222, draft.port)
        assertEquals("root", draft.username)
        assertNull(draft.group)
    }

    @Test
    fun toDraft_keeps_non_blank_group() {
        val f = NewConnectionFormState().apply {
            name = "h"; address = "a"; username = "u"; group = "Production"
        }
        assertEquals("Production", f.toDraft().group)
        assertNull(f.toDraft().id)
    }

    @Test
    fun toDraft_carries_credential_id() {
        val f = NewConnectionFormState().apply { name = "h"; address = "a"; username = "u" }
        assertEquals("cred-7", f.toDraft(credentialId = "cred-7").credentialId)
        assertNull(f.toDraft().credentialId)
    }

    @Test
    fun toDraft_carries_jump_host_and_fromHost_prefills_it() {
        val f = NewConnectionFormState().apply { name = "h"; address = "a"; username = "u"; jumpHostId = "bastion-1" }
        assertEquals("bastion-1", f.toDraft().jumpHostId)
        assertNull(NewConnectionFormState().toDraft().jumpHostId)

        val host = Host(id = "h1", label = "Web", address = "web", username = "root", jumpHostId = "bastion-1")
        assertEquals("bastion-1", NewConnectionFormState.fromHost(host).jumpHostId)
    }

    @Test
    fun keep_alive_defaults_to_30_travels_the_draft_and_prefills_from_host() {
        val f = NewConnectionFormState().apply { name = "h"; address = "a"; username = "u" }
        assertEquals(30, f.keepAliveSeconds)
        f.keepAliveSeconds = 0
        assertEquals(0, f.toDraft().keepAliveSeconds)

        val host = Host(id = "h1", label = "Web", address = "web", username = "root", keepAliveSeconds = 120)
        assertEquals(120, NewConnectionFormState.fromHost(host).keepAliveSeconds)
    }

    @Test
    fun notes_travel_the_draft_normalized_and_prefill_from_host() {
        val f = NewConnectionFormState().apply { name = "h"; address = "a"; username = "u" }
        assertEquals("", f.notes)
        assertNull(f.toDraft().notes) // blank notes are stored as absent, like an empty group

        f.notes = "  reboot window: Sun 03:00  "
        assertEquals("reboot window: Sun 03:00", f.toDraft().notes)

        f.notes = "x".repeat(MAX_NOTES_LENGTH + 40)
        assertEquals(MAX_NOTES_LENGTH, f.toDraft().notes?.length)

        val host = Host(id = "h1", label = "Web", address = "web", username = "root", notes = "ask ops before reboot")
        assertEquals("ask ops before reboot", NewConnectionFormState.fromHost(host).notes)
        assertEquals("", NewConnectionFormState.fromHost(host.copy(notes = null)).notes)
    }

    @Test
    fun switching_away_from_ssh_drops_the_jump_host() {
        val f = NewConnectionFormState().apply { jumpHostId = "bastion-1" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.TELNET)
        assertNull(f.jumpHostId)
    }

    @Test
    fun mosh_requires_username_and_auth_like_ssh() {
        val f = NewConnectionFormState().apply { name = "h"; address = "a" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.MOSH)
        assertEquals("22", f.port) // SSH and Mosh share the default port (the SSH hop's)
        assertFalse(f.canSave) // username is required, same as SSH
        f.username = "root"
        assertTrue(f.canSave)
    }

    @Test
    fun switching_to_mosh_keeps_jump_host_and_resolves_credentials() {
        val f = NewConnectionFormState().apply { name = "h"; address = "a"; username = "u"; jumpHostId = "bastion-1" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.MOSH)
        assertEquals("bastion-1", f.jumpHostId) // Mosh rides the SSH hop, the jump stays valid
        f.authMode = AuthMode.EXISTING
        f.existingCredentialId = "cred-1"
        assertEquals("cred-1", f.resolveCredentialId { null })
    }

    @Test
    fun vnc_defaults_to_port_5900_and_needs_no_username() {
        val f = NewConnectionFormState().apply { name = "desk"; address = "10.0.0.9" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.VNC)
        assertEquals("5900", f.port) // RFB display :0
        // VNC has no username; the default ASK auth is enough to save.
        assertTrue(f.canSave)
        assertEquals(app.skerry.shared.ssh.ConnectionType.VNC, f.toDraft().connectionType)
        assertEquals(5900, f.toDraft().port)
    }

    @Test
    fun rdp_audio_choice_travels_into_the_draft_and_back_out_of_the_profile() {
        val f = NewConnectionFormState().apply { name = "desk"; address = "10.0.0.9"; username = "admin" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.RDP)
        // Off by default: a profile that never asked for sound must not start opening a device.
        assertNull(f.toDraft().rdp)

        f.rdpAudioOutput = true
        f.rdpAudioDeviceId = "USB Headset"
        val draft = f.toDraft()
        assertTrue(checkNotNull(draft.rdp).audioOutput)
        assertEquals("USB Headset", draft.rdp?.audioOutputDeviceId)

        val host = Host(
            "1", "desk", "10.0.0.9", 3389, "admin",
            connectionType = app.skerry.shared.ssh.ConnectionType.RDP,
            rdp = app.skerry.shared.rdp.RdpSpec(audioOutput = true, audioOutputDeviceId = "USB Headset"),
        )
        assertTrue(NewConnectionFormState.fromHost(host).rdpAudioOutput)
        assertEquals("USB Headset", NewConnectionFormState.fromHost(host).rdpAudioDeviceId)
    }

    @Test
    fun rdp_image_quality_travels_into_the_draft_and_back_out_of_the_profile() {
        val f = NewConnectionFormState().apply { name = "desk"; address = "10.0.0.9"; username = "admin" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.RDP)
        // The default is the picture every RDP session had before the profile could choose, so an
        // untouched form still saves no RDP settings at all.
        assertEquals(app.skerry.shared.rdp.RdpImageQuality.Medium, f.rdpQuality)
        assertNull(f.toDraft().rdp)

        f.rdpQuality = app.skerry.shared.rdp.RdpImageQuality.High
        assertEquals(app.skerry.shared.rdp.RdpImageQuality.High, checkNotNull(f.toDraft().rdp).quality)

        val host = Host(
            "1", "desk", "10.0.0.9", 3389, "admin",
            connectionType = app.skerry.shared.ssh.ConnectionType.RDP,
            rdp = app.skerry.shared.rdp.RdpSpec(quality = app.skerry.shared.rdp.RdpImageQuality.Low),
        )
        assertEquals(app.skerry.shared.rdp.RdpImageQuality.Low, NewConnectionFormState.fromHost(host).rdpQuality)
    }

    @Test
    fun rdp_form_carries_the_farm_routing_token_it_was_prefilled_with() {
        // The token isn't editable (it comes from an imported .rdp file), but the form is what hands
        // the profile's RDP settings back on save — dropping it would send the next connection to an
        // arbitrary host of the farm.
        val host = Host(
            "1", "rds", "rds.example.com", 3389, "CORP\\alice",
            connectionType = app.skerry.shared.ssh.ConnectionType.RDP,
            rdp = app.skerry.shared.rdp.RdpSpec(loadBalanceInfo = "tsv://x"),
        )
        val f = NewConnectionFormState.fromHost(host)
        f.rdpAudioOutput = true

        val rdp = checkNotNull(f.toDraft(id = "1").rdp)
        assertEquals("tsv://x", rdp.loadBalanceInfo)
        assertTrue(rdp.audioOutput)
    }

    @Test
    fun turning_rdp_audio_off_forgets_the_device_it_played_on() {
        val f = NewConnectionFormState().apply { name = "d"; address = "a"; username = "u" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.RDP)
        f.rdpAudioOutput = true
        f.rdpAudioDeviceId = "USB Headset"
        f.rdpAudioOutput = false

        assertNull(f.toDraft().rdp)
    }

    @Test
    fun vnc_out_of_range_port_blocks_save() {
        val f = NewConnectionFormState().apply { name = "d"; address = "a" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.VNC)
        f.port = "70000"; assertFalse(f.canSave)
        f.port = "5901"; assertTrue(f.canSave)
    }

    @Test
    fun vnc_stores_a_password_credential_without_username() {
        val f = NewConnectionFormState().apply { name = "d"; address = "10.0.0.9" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.VNC)
        f.authMode = AuthMode.NEW_PASSWORD
        assertFalse(f.canSave) // password blank
        f.password = "sekret"
        assertTrue(f.canSave)
        val cap = Captures()
        assertEquals("cred-id", f.resolveCredentialId(cap.saveCredential))
        assertEquals(CredentialKind.PASSWORD, cap.credentialDraft?.kind)
        assertEquals("sekret", cap.credentialDraft?.password)
    }

    @Test
    fun rdp_stores_the_password_typed_in_the_form() {
        // RDP has no anonymous logon: dropping the password here left the profile secretless and the
        // connect prompt asking for it every time.
        val f = NewConnectionFormState().apply { name = "d"; address = "10.0.0.9"; username = "Administrator" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.RDP)
        f.authMode = AuthMode.NEW_PASSWORD
        f.password = "sekret"
        assertTrue(f.canSave)
        val cap = Captures()
        assertEquals("cred-id", f.resolveCredentialId(cap.saveCredential))
        assertEquals(CredentialKind.PASSWORD, cap.credentialDraft?.kind)
        assertEquals("sekret", cap.credentialDraft?.password)
    }

    @Test
    fun rdp_keeps_a_selected_saved_secret() {
        val f = NewConnectionFormState().apply { name = "d"; address = "a"; username = "u" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.RDP)
        f.authMode = AuthMode.EXISTING
        f.existingCredentialId = "saved-1"
        assertEquals("saved-1", f.resolveCredentialId { error("an existing secret must not be rewritten") })
    }

    @Test
    fun vnc_ask_auth_resolves_to_null() {
        val f = NewConnectionFormState().apply { name = "d"; address = "a" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.VNC)
        assertEquals(AuthMode.ASK, f.authMode)
        assertNull(f.resolveCredentialId { error("ask must not save a credential") })
    }

    @Test
    fun switching_to_vnc_drops_key_auth_state() {
        // Started as SSH with a key: switching to VNC must not carry the key over — VNC auth is
        // password-only and a key credential would silently degrade to no auth at connect.
        val f = NewConnectionFormState().apply { name = "d"; address = "a"; username = "u" }
        f.authMode = AuthMode.NEW_KEY
        f.privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----"
        f.passphrase = "pp"
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.VNC)
        assertEquals(AuthMode.ASK, f.authMode)
        assertEquals("", f.privateKeyPem)
        assertEquals("", f.passphrase)
        assertNull(f.resolveCredentialId { error("no key credential may be created for VNC") })
    }

    @Test
    fun switching_to_vnc_drops_existing_credential_selection() {
        // The form can't tell a key secret from a password one by id, so the selection resets.
        val f = NewConnectionFormState().apply { name = "d"; address = "a"; username = "u" }
        f.authMode = AuthMode.EXISTING
        f.existingCredentialId = "key-cred"
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.VNC)
        assertEquals(AuthMode.ASK, f.authMode)
        assertNull(f.existingCredentialId)
    }

    @Test
    fun switching_to_vnc_keeps_new_password_auth() {
        val f = NewConnectionFormState().apply { name = "d"; address = "a"; username = "u" }
        f.authMode = AuthMode.NEW_PASSWORD
        f.password = "sekret"
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.VNC)
        assertEquals(AuthMode.NEW_PASSWORD, f.authMode)
        assertEquals("sekret", f.password)
        assertTrue(f.canSave)
    }

    // Container profiles (Docker / Kubernetes exec over the host's SSH)

    @Test
    fun container_needs_ssh_fields_plus_a_container_name() {
        val f = NewConnectionFormState().apply { name = "web on prod"; address = "10.0.0.5" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.CONTAINER)
        assertEquals("22", f.port) // the CLI rides the host's SSH port
        assertFalse(f.canSave) // username missing
        f.username = "ops"
        assertFalse(f.canSave) // nothing to exec into yet
        f.containerTarget = "web"
        assertTrue(f.canSave)
    }

    @Test
    fun container_draft_carries_a_normalized_spec() {
        val f = NewConnectionFormState().apply { name = "api"; address = "10.0.0.5"; username = "ops" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.CONTAINER)
        f.containerRuntime = app.skerry.shared.container.ContainerRuntime.KUBERNETES
        f.containerTarget = "  api-0 "
        f.containerNamespace = " prod "
        f.containerPodContainer = "  "
        f.containerShell = " bash "
        assertEquals(
            app.skerry.shared.container.ContainerSpec(
                runtime = app.skerry.shared.container.ContainerRuntime.KUBERNETES,
                target = "api-0",
                namespace = "prod",
                podContainer = "",
                shell = "bash",
            ),
            f.toDraft().container,
        )
    }

    @Test
    fun non_container_profiles_store_no_spec() {
        val f = NewConnectionFormState().apply { name = "h"; address = "a"; username = "u" }
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.CONTAINER)
        f.containerTarget = "web"
        f.chooseConnectionType(app.skerry.shared.ssh.ConnectionType.SSH)
        assertNull(f.toDraft().container)
    }

    @Test
    fun fromHost_prefills_the_container_spec() {
        val spec = app.skerry.shared.container.ContainerSpec(
            runtime = app.skerry.shared.container.ContainerRuntime.KUBERNETES,
            target = "api-0", namespace = "prod", podContainer = "app", shell = "bash",
        )
        val host = Host(
            id = "h1", label = "api", address = "10.0.0.5", username = "ops",
            connectionType = app.skerry.shared.ssh.ConnectionType.CONTAINER, container = spec,
        )
        val f = NewConnectionFormState.fromHost(host)
        assertEquals(app.skerry.shared.container.ContainerRuntime.KUBERNETES, f.containerRuntime)
        assertEquals("api-0", f.containerTarget)
        assertEquals("prod", f.containerNamespace)
        assertEquals("app", f.containerPodContainer)
        assertEquals("bash", f.containerShell)
        assertEquals(spec, f.toDraft(id = host.id).container)
    }

    // Authentication

    private fun validBase() = NewConnectionFormState().apply { name = "h"; address = "a"; username = "u" }

    // Capture helper: saveCredential returns the secret id and captures the draft.
    private class Captures {
        var credentialDraft: CredentialDraft? = null
        val saveCredential: (CredentialDraft) -> String? = { credentialDraft = it; "cred-id" }
    }

    @Test
    fun default_auth_is_ask_and_resolves_to_null_without_saving() {
        val f = validBase()
        assertEquals(AuthMode.ASK, f.authMode)
        assertTrue(f.canSave) // ASK does not require a secret
        val cap = Captures()
        val id = f.resolveCredentialId(cap.saveCredential)
        assertNull(id)
        assertNull(cap.credentialDraft) // secret not created
    }

    @Test
    fun existing_credential_requires_selection_and_resolves_to_its_id() {
        val f = validBase().apply { authMode = AuthMode.EXISTING }
        assertFalse(f.canSave) // nothing selected
        f.existingCredentialId = "saved-1"
        assertTrue(f.canSave)
        val cap = Captures()
        assertEquals("saved-1", f.resolveCredentialId(cap.saveCredential))
        assertNull(cap.credentialDraft) // existing credential is not recreated
    }

    @Test
    fun new_password_requires_value_and_creates_credential() {
        val f = validBase().apply { authMode = AuthMode.NEW_PASSWORD; username = "root"; address = "10.0.0.1" }
        assertFalse(f.canSave) // password blank
        f.password = "s3cr3t"
        assertTrue(f.canSave)
        val cap = Captures()
        val id = f.resolveCredentialId(cap.saveCredential)
        assertEquals("cred-id", id) // returns the id of the created secret
        assertEquals(CredentialKind.PASSWORD, cap.credentialDraft?.kind)
        assertEquals("s3cr3t", cap.credentialDraft?.password)
        assertEquals("root@10.0.0.1", cap.credentialDraft?.label)
        assertNull(cap.credentialDraft?.id) // creates a new one
    }

    @Test
    fun new_key_requires_pem_and_creates_credential() {
        val f = validBase().apply { authMode = AuthMode.NEW_KEY; username = "ci"; address = "build.host" }
        assertFalse(f.canSave) // PEM blank
        f.privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----"
        f.passphrase = "pp"
        assertTrue(f.canSave)
        val cap = Captures()
        val id = f.resolveCredentialId(cap.saveCredential)
        assertEquals("cred-id", id)
        assertEquals(CredentialKind.PRIVATE_KEY, cap.credentialDraft?.kind)
        assertEquals(f.privateKeyPem, cap.credentialDraft?.privateKeyPem)
        assertEquals("pp", cap.credentialDraft?.passphrase)
        assertEquals("ci@build.host", cap.credentialDraft?.label)
    }

    @Test
    fun new_password_with_failed_credential_save_resolves_to_null() {
        val f = validBase().apply { authMode = AuthMode.NEW_PASSWORD; password = "s3cr3t" }
        val id = f.resolveCredentialId(saveCredential = { null }) // secret not saved (e.g. no vault)
        assertNull(id)
    }

    // Editing an existing host: fromHost prefills the form

    @Test
    fun fromHost_prefills_fields_and_round_trips_via_draft() {
        val host = Host(
            id = "h1", label = "prod-web-01", address = "10.0.0.5", port = 2222,
            username = "root", group = "Production", credentialId = "cred-9",
        )
        val f = NewConnectionFormState.fromHost(host)
        assertEquals("prod-web-01", f.name)
        assertEquals("10.0.0.5", f.address)
        assertEquals("2222", f.port)
        assertEquals("root", f.username)
        assertEquals("Production", f.group)
        // bound secret -> EXISTING mode with the same id; form is valid immediately
        assertEquals(AuthMode.EXISTING, f.authMode)
        assertEquals("cred-9", f.existingCredentialId)
        assertTrue(f.canSave)
        // Saving an edit keeps the host id and binding without recreating the secret.
        val credentialId = f.resolveCredentialId { error("existing credential must not be re-saved") }
        val draft = f.toDraft(id = host.id, credentialId = credentialId)
        assertEquals("h1", draft.id)
        assertEquals("cred-9", draft.credentialId)
        assertEquals("prod-web-01", draft.label)
    }

    @Test
    fun fromHost_without_credential_defaults_to_ask_and_blank_group() {
        val host = Host(id = "h2", label = "box", address = "a", port = 22, username = "u")
        val f = NewConnectionFormState.fromHost(host)
        assertEquals(AuthMode.ASK, f.authMode)
        assertNull(f.existingCredentialId)
        assertEquals("", f.group)
        assertNull(f.resolveCredentialId { error("ask must not save a credential") })
    }

    // Duplicating a host: same profile under a new name, saved as a new record

    @Test
    fun duplicateOf_prefills_a_copy_that_saves_as_a_new_host() {
        val host = Host(
            id = "h1", label = "prod-web-01", address = "10.0.0.5", port = 2222,
            username = "root", group = "Production", credentialId = "cred-9",
            tags = listOf("prod"), jumpHostId = "bastion-1", keepAliveSeconds = 120,
        )
        val f = NewConnectionFormState.duplicateOf(host, name = "prod-web-01 Copy")
        assertEquals("prod-web-01 Copy", f.name)
        assertEquals(host.connectionType, f.connectionType)
        assertEquals(host.aiPolicy, f.aiPolicy)
        assertTrue(f.canSave)
        // The copy shares the original's secret — nothing is re-saved to the vault.
        val credentialId = f.resolveCredentialId { error("duplicate must not create a new credential") }
        val draft = f.toDraft(id = null, credentialId = credentialId)
        assertNull(draft.id) // a new profile, not an in-place edit of the source
        assertEquals("cred-9", draft.credentialId)
        assertEquals("10.0.0.5", draft.address)
        assertEquals(2222, draft.port)
        assertEquals("root", draft.username)
        assertEquals("Production", draft.group)
        assertEquals(listOf("prod"), draft.tags)
        assertEquals("bastion-1", draft.jumpHostId)
        assertEquals(120, draft.keepAliveSeconds)
    }

    @Test
    fun duplicateOf_credential_less_host_stays_in_ask_mode() {
        val host = Host(id = "h2", label = "box", address = "a", port = 22, username = "u")
        val f = NewConnectionFormState.duplicateOf(host, name = "box Copy")
        assertEquals(AuthMode.ASK, f.authMode)
        val draft = f.toDraft(id = null, credentialId = f.resolveCredentialId { error("ask must not save a credential") })
        assertNull(draft.id)
        assertNull(draft.credentialId)
    }

    // Tags (single-tag canonicalization is in app.skerry.shared.host.HostTagsTest)

    @Test
    fun addTag_normalizes_dedupes_and_keeps_order() {
        val f = NewConnectionFormState()
        f.addTag("#Prod")
        f.addTag("docker")
        f.addTag("PROD") // duplicate after normalization is ignored
        assertEquals(listOf("prod", "docker"), f.tags)
    }

    @Test
    fun addTag_hoists_prod_pill_to_the_front() {
        val f = NewConnectionFormState()
        f.addTag("web")
        f.addTag("db")
        f.addTag("prod")
        assertEquals(listOf("prod", "web", "db"), f.tags)
    }

    @Test
    fun addTag_keeps_prod_when_the_cap_is_hit() {
        val f = NewConnectionFormState()
        f.addTag((1..app.skerry.shared.tag.MAX_TAGS_PER_RECORD + 5).joinToString(",") { "tag$it" })
        f.addTag("prod")
        assertEquals("prod", f.tags.first())
    }

    @Test
    fun addTag_splits_on_commas() {
        val f = NewConnectionFormState()
        f.addTag("prod, #docker ,, db")
        assertEquals(listOf("prod", "docker", "db"), f.tags)
    }

    @Test
    fun addTag_caps_total_count() {
        val f = NewConnectionFormState()
        f.addTag((1..50).joinToString(",") { "tag$it" })
        assertEquals(app.skerry.shared.tag.MAX_TAGS_PER_RECORD, f.tags.size)
    }

    @Test
    fun addTag_blank_is_noop() {
        val f = NewConnectionFormState()
        f.addTag("   ")
        f.addTag("#")
        assertEquals(emptyList(), f.tags)
    }

    @Test
    fun removeTag_drops_the_tag() {
        val f = NewConnectionFormState().apply { addTag("prod"); addTag("docker") }
        f.removeTag("prod")
        assertEquals(listOf("docker"), f.tags)
    }

    @Test
    fun toDraft_carries_tags() {
        val f = NewConnectionFormState().apply { name = "h"; address = "a"; username = "u"; addTag("prod") }
        assertEquals(listOf("prod"), f.toDraft().tags)
        assertEquals(emptyList(), NewConnectionFormState().apply { name = "h"; address = "a"; username = "u" }.toDraft().tags)
    }

    @Test
    fun fromHost_restores_tags() {
        val host = Host(id = "h3", label = "box", address = "a", port = 22, username = "u", tags = listOf("prod", "db"))
        val f = NewConnectionFormState.fromHost(host)
        assertEquals(listOf("prod", "db"), f.tags)
    }
}

/** The form is opened per section (hosts list vs remote desktops), which fixes its protocol set. */
class SectionFormStateTest {

    @Test
    fun a_terminal_form_starts_on_ssh_with_its_port() {
        val form = NewConnectionFormState.forSection(HostSection.Terminal)
        assertEquals(ConnectionType.SSH, form.connectionType)
        assertEquals("22", form.port)
    }

    @Test
    fun a_remote_desktop_form_starts_on_vnc_with_its_port() {
        val form = NewConnectionFormState.forSection(HostSection.RemoteDesktops)
        assertEquals(ConnectionType.VNC, form.connectionType)
        assertEquals("5900", form.port)
    }

    @Test
    fun a_remote_desktop_form_saves_a_profile_filed_under_remote_desktops() {
        // The whole point of the split: what you create here shows up there and nowhere else.
        val form = NewConnectionFormState.forSection(HostSection.RemoteDesktops).apply {
            name = "lab-desktop"
            address = "10.0.0.5"
        }
        assertTrue(form.canSave)
        val draft = form.toDraft()
        assertEquals(ConnectionType.VNC, draft.connectionType)
        assertEquals(5900, draft.port)
    }

    @Test
    fun a_remote_desktop_form_needs_no_username() {
        // VNC authenticates with a password only, so requiring a user would block saving.
        val form = NewConnectionFormState.forSection(HostSection.RemoteDesktops).apply {
            name = "screen"
            address = "10.0.0.5"
        }
        assertEquals("", form.username)
        assertTrue(form.canSave)
    }

    @Test
    fun an_interactive_profile_saves_without_any_secret() {
        // The server does the asking, so there is nothing to fill in and nothing to store.
        val form = NewConnectionFormState().apply {
            name = "bastion"
            address = "10.0.0.9"
            username = "ops"
            authMode = AuthMode.INTERACTIVE
        }

        assertTrue(form.canSave)
        val credentialId = form.resolveCredentialId { fail("an interactive profile must not write a secret") }
        assertNull(credentialId)
        assertTrue(form.toDraft(credentialId = credentialId).interactiveAuth)
    }

    @Test
    fun editing_an_interactive_profile_keeps_the_mode() {
        val host = Host(
            id = "h1",
            label = "bastion",
            address = "10.0.0.9",
            username = "ops",
            interactiveAuth = true,
        )

        assertEquals(AuthMode.INTERACTIVE, NewConnectionFormState.fromHost(host).authMode)
    }

    @Test
    fun an_rdp_domain_is_saved_as_part_of_the_user_name() {
        val form = NewConnectionFormState().apply {
            chooseConnectionType(ConnectionType.RDP)
            name = "rds"
            address = "rds.example.com"
            username = "alice"
            domain = "CORP"
            authMode = AuthMode.NEW_PASSWORD
            password = "s3cret"
        }

        // `DOMAIN\user` is what the transport splits apart again, so the profile keeps one field.
        assertEquals("CORP\\alice", form.toDraft().username)
    }

    @Test
    fun editing_an_rdp_profile_splits_the_domain_back_out() {
        val host = Host(
            id = "h1",
            label = "rds",
            address = "rds.example.com",
            username = "CORP\\alice",
            connectionType = ConnectionType.RDP,
        )

        val form = NewConnectionFormState.fromHost(host)

        assertEquals("CORP", form.domain)
        assertEquals("alice", form.username)
        assertEquals("CORP\\alice", form.toDraft(id = "h1").username)
    }

    @Test
    fun a_domain_typed_for_a_non_rdp_profile_is_not_glued_onto_the_user_name() {
        val form = NewConnectionFormState().apply {
            name = "web"
            address = "10.0.0.1"
            username = "root"
            domain = "CORP"
        }

        assertEquals("root", form.toDraft().username)
    }
}
