package app.skerry.ui.vault

import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.CredentialUsage

/**
 * Sample secrets for the offscreen/preview render of the Vault section, where there is no unlocked
 * keychain to list. Built as real [Credential] values with the meta line already resolved (parsing a
 * fake PEM would fail), so the preview goes through the same row and panel as the live path — a
 * separate mock layout would drift the moment a column changes.
 */
internal fun mockSecrets(): List<Pair<Credential, String>> = listOf(
    Credential("m1", "id_ed25519 — deploy", CredentialSecret.PrivateKey("mock-pem", passphrase = "mock")) to
        "ED25519 · SHA256:9pQk…dR2f · prod-web-01, prod-web-02",
    Credential("m2", "id_rsa — legacy jump", CredentialSecret.PrivateKey("mock-pem")) to
        "RSA-4096 · SHA256:1cTz…88Aa · jump.corp",
    Credential("m3", "db-master · postgres", CredentialSecret.Password("mock")) to
        "rotated 12 days ago · db-master",
    Credential("m4", "skerry-ca — user cert", CredentialSecret.Certificate("mock-pem", "mock-cert")) to
        "ED25519-cert · valid until 14 Sep 2026",
    Credential("m5", "vps-edge · root", CredentialSecret.Password("mock")) to
        "rotated 3 days ago · vps-edge",
    Credential("m6", "work SSH CA", CredentialSecret.KeyFile("~/.ssh/id_ed25519", "~/.ssh/id_ed25519-cert.pub")) to
        "~/.ssh/id_ed25519",
)

/** Usage trail of the mock selection, so the preview panel shows the same dates the live one would. */
internal fun mockUsage(): CredentialUsage = CredentialUsage(
    credentialId = "m1",
    addedAt = "2026-06-14T09:14:00Z",
    lastUsedAt = "2026-06-14T09:14:00Z",
    copiedAt = listOf("2026-06-14T09:14:00Z"),
)
