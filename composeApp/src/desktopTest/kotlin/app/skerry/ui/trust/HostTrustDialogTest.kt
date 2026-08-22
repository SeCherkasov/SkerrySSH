package app.skerry.ui.trust

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performClick
import app.skerry.shared.trust.HostTrustCertificate
import app.skerry.shared.trust.HostTrustKind
import app.skerry.shared.trust.HostTrustRequest
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.drawnText
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_known_accept_new_key
import app.skerry.ui.generated.resources.lib_known_reject_block
import app.skerry.ui.generated.resources.trust_accept
import app.skerry.ui.generated.resources.trust_accept_new_cert
import app.skerry.ui.generated.resources.trust_cert_not_verified
import app.skerry.ui.generated.resources.trust_changed_cert_title
import app.skerry.ui.generated.resources.trust_changed_key_title
import app.skerry.ui.generated.resources.trust_new_cert_title
import app.skerry.ui.generated.resources.trust_new_key_title
import app.skerry.ui.generated.resources.trust_other_type_title
import app.skerry.ui.generated.resources.trust_reject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val FP_NEW = "SHA256:QdM7U3V+ixe/tVMcqgEELjEcGBxdx3mMDVZx5N+IhU"
private const val FP_OLD = "SHA256:8RWEGyQOQkYuLzJGdrTgGjfxmsek+GM79vMnxgGXmIA"

/** An escape byte and a bidi override, written as literals: a raw one is invisible in a diff. */
private const val ESCAPE = '\u001B'
private const val BIDI_OVERRIDE = '\u202E'

/**
 * The dialog that stands between a handshake and a key nobody has vouched for. What it must get
 * right is which answer a press lands on: the safe one leads, and on a changed key the safe one is
 * "reject".
 */
@OptIn(ExperimentalTestApi::class)
class HostTrustDialogTest {

    private fun sshKey(recorded: String? = null) = HostTrustRequest(
        kind = HostTrustKind.SshHostKey,
        host = "45.151.62.14",
        port = 22,
        keyType = "ssh-ed25519",
        fingerprint = FP_NEW,
        recordedFingerprint = recorded,
    )

    private fun rdpCertificate() = HostTrustRequest(
        kind = HostTrustKind.RdpCertificate,
        host = "rds.example.com",
        port = 3389,
        keyType = "",
        fingerprint = FP_NEW,
        certificate = HostTrustCertificate(
            subject = "CN=RDS-01.corp.example",
            issuer = "CN=RDS-01.corp.example",
            notAfterMillis = 1_800_000_000_000,
            trustedByPlatform = false,
            hostnameMatches = false,
        ),
    )

    @Test
    fun `a first key shows the fingerprint and both answers`() {
        var answered: Boolean? = null
        runForm({
            HostTrustDialog(1L, sshKey(), onAccept = { answered = true }, onReject = { answered = false })
        }) {
            val drawn = drawnText()
            assertTrue(drawn.contains(string(Res.string.trust_new_key_title)))
            assertTrue(drawn.any { it.contains(FP_NEW) }, "the fingerprint being accepted is not on screen: $drawn")
            assertTrue(drawn.any { it.contains("45.151.62.14:22") }, "the host is not named: $drawn")

            onNodeWithText(string(Res.string.trust_accept)).performClick()
            assertEquals(true, answered)
        }
    }

    @Test
    fun `rejecting a first key answers no`() {
        var answered: Boolean? = null
        runForm({
            HostTrustDialog(1L, sshKey(), onAccept = { answered = true }, onReject = { answered = false })
        }) {
            onNodeWithText(string(Res.string.trust_reject)).performClick()
            assertEquals(false, answered)
        }
    }

    @Test
    fun `a changed key shows both fingerprints and leads with the refusal`() {
        var answered: Boolean? = null
        runForm({
            HostTrustDialog(1L, sshKey(recorded = FP_OLD), onAccept = { answered = true }, onReject = { answered = false })
        }) {
            val drawn = drawnText()
            assertTrue(drawn.contains(string(Res.string.trust_changed_key_title)))
            assertTrue(drawn.any { it.contains(FP_OLD) }, "the fingerprint being replaced is not on screen: $drawn")
            assertTrue(drawn.any { it.contains(FP_NEW) }, "the offered fingerprint is not on screen: $drawn")

            onNodeWithText(string(Res.string.lib_known_reject_block)).performClick()
            assertEquals(false, answered, "the leading button on a changed key must refuse")
        }
    }

    @Test
    fun `accepting a changed key is possible, on the second button`() {
        var answered: Boolean? = null
        runForm({
            HostTrustDialog(1L, sshKey(recorded = FP_OLD), onAccept = { answered = true }, onReject = { answered = false })
        }) {
            onNodeWithText(string(Res.string.lib_known_accept_new_key)).performClick()
            assertEquals(true, answered)
        }
    }

    @Test
    fun `a changed certificate leads with the refusal, in words about a certificate`() {
        // The changed-key branch is shared, and its labels are written for an SSH key: "Block this
        // key" on a dialog about a TLS certificate names something the user is not being shown.
        var answered: Boolean? = null
        val changed = rdpCertificate().copy(recordedFingerprint = FP_OLD)
        runForm({ HostTrustDialog(1L, changed, onAccept = { answered = true }, onReject = { answered = false }) }) {
            val drawn = drawnText()
            assertTrue(drawn.contains(string(Res.string.trust_changed_cert_title)))
            assertFalse(
                drawn.contains(string(Res.string.lib_known_reject_block)),
                "a certificate dialog offered to block a key: $drawn",
            )

            onNodeWithText(string(Res.string.trust_accept_new_cert)).performClick()
            assertEquals(true, answered)
        }
    }

    @Test
    fun `a key type the host has no record of is not drawn as a plain first contact`() {
        // Nothing is being replaced, so there is no second fingerprint to show — but the host is
        // known, and a dialog that reads "first connection" is the one an interception wants.
        val downgraded = sshKey().copy(recordedKeyTypes = listOf("rsa-sha2-512", "ssh-ed25519"))
        var answered: Boolean? = null
        runForm({
            HostTrustDialog(1L, downgraded, onAccept = { answered = true }, onReject = { answered = false })
        }) {
            val drawn = drawnText()
            assertTrue(drawn.contains(string(Res.string.trust_other_type_title)))
            assertTrue(
                drawn.any { it.contains("rsa-sha2-512") && it.contains("ssh-ed25519") },
                "the keys the host is already known by are not named: $drawn",
            )

            onNodeWithText(string(Res.string.lib_known_reject_block)).performClick()
            assertEquals(false, answered, "the leading button on a known host must refuse")
        }
    }

    @Test
    fun `a certificate is shown with what it says about itself`() {
        runForm({ HostTrustDialog(1L, rdpCertificate(), onAccept = {}, onReject = {}) }) {
            val drawn = drawnText()
            assertTrue(drawn.contains(string(Res.string.trust_new_cert_title)))
            assertTrue(drawn.any { it.contains("RDS-01.corp.example") }, "subject/issuer missing: $drawn")
            assertTrue(
                drawn.contains(string(Res.string.trust_cert_not_verified)),
                "a self-signed certificate must say so: $drawn",
            )
        }
    }

    @Test
    fun `a real issuer name is shown whole, not cut at the authority`() {
        // "CN=HARICA DV TLS RSA,O=Hellenic Academ…" is what a single elided line makes of a public
        // CA's DN — the part that says who vouched for the host is the part that disappears.
        val issuer = "CN=HARICA DV TLS RSA,O=Hellenic Academic and Research Institutions CA,C=GR"
        val offered = rdpCertificate()
        val request = offered.copy(certificate = offered.certificate!!.copy(issuer = issuer))

        runForm({ HostTrustDialog(1L, request, onAccept = {}, onReject = {}) }) {
            assertTrue(
                drawnText().any { it.contains("Hellenic Academic and Research Institutions CA") },
                "the issuer was cut before it named the authority: ${drawnText()}",
            )
        }
    }

    @Test
    fun `a hostile host name cannot draw its own chrome either`() {
        // An RDP server picks the host string itself, through the redirection PDU's target FQDN —
        // and this is the one line that says which machine the user is vouching for.
        val hostile = rdpCertificate().copy(
            host = "ok.example" + BIDI_OVERRIDE + "\nAlready trusted. Press Accept." + ESCAPE + "[31m" +
                "y".repeat(4000),
            keyType = "ssh-" + BIDI_OVERRIDE + "ed25519",
        )
        runForm({ HostTrustDialog(1L, hostile, onAccept = {}, onReject = {}) }) {
            val drawn = drawnText()
            assertTrue(
                drawn.none { it.contains(BIDI_OVERRIDE) || it.contains(ESCAPE) || it.contains("\n") },
                "the host wrote raw control bytes or lines of its own: $drawn",
            )
            assertTrue(drawn.all { it.length < 1000 }, "a flooded host name was drawn whole")
            onNodeWithText(string(Res.string.trust_accept)).performClick()
        }
    }

    @Test
    fun `a padded host name still shows the domain it really sits in`() {
        // A name cut only at the head reads as the corporate host, with the port appended as if the
        // line were whole — and the label the decision turns on is the one that disappeared.
        val padded = "vpn.corp.example.com." + "a".repeat(140) + ".evil.net"
        runForm({ HostTrustDialog(1L, rdpCertificate().copy(host = padded), onAccept = {}, onReject = {}) }) {
            val endpoint = drawnText().single { it.startsWith("vpn.corp.example.com.") }
            assertTrue(endpoint.contains(".evil.net"), "the domain the host sits in was cut away: $endpoint")
            assertTrue(endpoint.contains('\u2026'), "a cut name was drawn as if it were whole: $endpoint")
        }
    }

    @Test
    fun `the pane a screen reader announces names the host being answered for`() {
        // "New host key" on its own is not a question anyone can answer: the machine it is about is
        // the fact the decision turns on, and it must not be several stops past the buttons.
        runForm({ HostTrustDialog(1L, sshKey(), onAccept = {}, onReject = {}) }) {
            val pane = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .mapNotNull { it.config.getOrNull(SemanticsProperties.PaneTitle) }
            assertTrue(
                pane.any { it.contains(string(Res.string.trust_new_key_title)) && it.contains("45.151.62.14:22") },
                "the pane title does not say which host is being asked about: $pane",
            )
        }
    }

    @Test
    fun `the refusing answer holds the keyboard when the question opens`() {
        // The caret is cleared off whatever had it before the dialog is drawn, so something inside
        // has to take it — otherwise the keys go nowhere, the question opens unannounced, and the
        // only route to the buttons is a mouse. Enter must reach the answer that changes nothing.
        var accepted = false
        var refused = false
        runForm({ HostTrustDialog(3L, sshKey(), onAccept = { accepted = true }, onReject = { refused = true }) }) {
            onNodeWithTag(UiTags.FORM_CANCEL).assertIsFocused()
            onNodeWithTag(UiTags.FORM_CANCEL).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
        }
        assertTrue(refused, "the focused answer did not refuse")
        assertFalse(accepted, "the key was trusted by a press that should have refused")
    }

    @Test
    fun `a hostile certificate cannot draw its own chrome`() {
        // Subject and issuer are the server's text. Control characters and bidi overrides would
        // otherwise reorder the sentence around the buttons; a flood would push them off screen.
        val offered = rdpCertificate()
        val hostile = offered.copy(
            certificate = offered.certificate!!.copy(
                subject = "CN=ok" + BIDI_OVERRIDE + "evil" + ESCAPE + "[31m",
                issuer = "CN=" + "x".repeat(4000),
            ),
        )
        runForm({ HostTrustDialog(1L, hostile, onAccept = {}, onReject = {}) }) {
            val drawn = drawnText()
            assertTrue(
                drawn.none { it.contains(BIDI_OVERRIDE) || it.contains(ESCAPE) },
                "raw control bytes reached the screen",
            )
            assertTrue(drawn.all { it.length < 1000 }, "a flood of text was drawn whole")
            // The answers are still reachable under whatever the server sent.
            onNodeWithText(string(Res.string.trust_accept)).performClick()
        }
    }
}
