# Security Policy

## Reporting a vulnerability

Please report vulnerabilities privately via GitHub:
**[Security → Report a vulnerability](https://github.com/SeCherkasov/SkerrySSH/security/advisories/new)**
(private vulnerability reporting is enabled for this repository).

Please do **not** open a public issue for anything security-sensitive. You will get an
acknowledgement as soon as possible; this is a small project without a dedicated security
team, so please allow a few days.

## Supported versions

Skerry is pre-1.0. Only the **latest release** receives security fixes; older releases are
not patched — upgrade to the latest version.

## Audit status

**The cryptography in Skerry has not been independently audited.** The design uses
established primitives and libraries — libsodium (Argon2id, XChaCha20-Poly1305),
BouncyCastle, Nimbus SRP-6a — rather than custom crypto, but the way they are composed has
not been reviewed by an external party. Treat the zero-knowledge claims below as design
goals verified by the project's own tests, not by a third-party audit.

## Cryptography overview

- **Vault (at rest)**: the master password is stretched with Argon2id
  (m=64 MiB, t=3, p=4); records are encrypted with XChaCha20-Poly1305 (libsodium).
  The master password and derived keys never leave the device.
- **Sync (optional, self-hosted)**: authKey/dataKey split; the server stores only
  ciphertext (the wrapped `dataKey`, encrypted records) and sync metadata. Clients
  authenticate with SRP-6a — the server stores a verifier, the password is never
  transmitted. Sessions use JWT.
- **Teams**: sharing is end-to-end encrypted via sealed-envelope invitations; the server
  cannot read shared records.
- **AI**: per-host policies control whether anything may be sent to a cloud provider;
  see [AI and privacy](README.md#ai-and-privacy) in the README.

## Threat model

Skerry is designed to protect against:

- **Theft of data at rest** — the vault is encrypted; without the master password the
  data is unreadable (assuming a strong master password).
- **A compromised or curious sync server** — the server only ever sees ciphertext and
  an SRP-6a verifier; it cannot decrypt vault records or recover the password.
- **Network eavesdropping on sync credentials** — SRP-6a never transmits the password.

Skerry does **not** protect against:

- **A compromised device** — malware, a keylogger, or an attacker with access to your
  unlocked session can read whatever you can.
- **Weak master passwords** — Argon2id slows down brute force but cannot make a guessable
  password safe. There is **no recovery**: a lost master password means lost data.
- **Malicious or compromised remote hosts** — a server you connect to sees everything you
  send in that session.
- **Secrets you deliberately send to a cloud AI** — the Permissive policy disables
  redaction; Balanced redaction is best-effort pattern matching, not a guarantee.

Also note: desktop installers are currently not code-signed (macOS builds are not
notarized). Verify downloads against the `SHA256SUMS.txt` attached to each release:
`sha256sum -c --ignore-missing SHA256SUMS.txt`.
