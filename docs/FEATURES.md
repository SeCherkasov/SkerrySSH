# Skerry — detailed feature list

The [README](../README.md) keeps a high-level summary; this is the full detail.

## Connections

- SSH (sshj + BouncyCastle), SSH certificates
- SSH jump hosts (ProxyJump), with the jump route shown in the connection info panel
- Per-host keep-alive interval (off / 30 / 60 / 120 s) with dead-link detection
- SFTP (dual-pane commander) with type-to-jump path bars
- Port forwarding: local (`-L`), remote (`-R`), dynamic/SOCKS (`-D`)
- Mosh: native client implementation (AES-128-OCB datagrams, state-sync protocol) — the session
  survives network outages, roaming and sleep; `mosh-server` is launched over the profile's SSH
  auth (jump hosts included) and needs the `mosh` package installed on the remote host plus open
  UDP 60000–61000; typed setup errors explain each of these server-side requirements
- Telnet (custom IAC-negotiation codec)
- Serial: jSerialComm on desktop; USB-OTG on Android (CDC/FTDI/CP210x/CH34x chipsets)

## Terminal

- Custom grid emulation: VT line-drawing, Unicode/combining characters, SGR,
  OSC 8/4/52/104, bracketed paste
- Session tabs with split view, SSH auto-reconnect, drag-to-reorder
- Live host metrics (RTT) in the status bar
- JetBrains Mono rendering, scrollback reverse-search
- Clickable URLs (OSC 8 hyperlinks and bare URLs)

## Vault

- Always-on encryption: Argon2id key derivation, XChaCha20-Poly1305 (libsodium);
  zero-knowledge — the master password never leaves the device
- Biometric unlock (BiometricPrompt) with reset/recovery flow, `FLAG_SECURE` on Android
- Keys, passwords, identities, certificates

## Sync (self-hosted, optional)

- Zero-knowledge sync: authKey/dataKey split, XChaCha20-Poly1305 payloads,
  SRP-6a authentication (the server stores a verifier, never the password), JWT sessions
- Live sync: push-on-change over WebSocket, tombstone propagation, cursor persistence,
  selective sync by record type
- Device pairing via QR (ZXing + CameraX + ML Kit, on-device), admin console
- See [server/README.md](../server/README.md) for deployment

## Teams (sharing, optional)

- E2E zero-knowledge sharing of hosts and snippets within a team, on top of sealed-envelope
  invitations; owner/member roles, ACL revocation

## Snippets & AI

- Command library with snippet type-ahead in the terminal
- AI assistant (BYOK OpenAI, per-host policies Strict/Balanced/Permissive/Off) with SSE
  streaming; secret redaction before cloud requests — see
  [AI and privacy](../README.md#ai-and-privacy)
- On-device local AI: the app downloads GGUF models itself and runs them via llama.cpp
  (catalog: Qwen3, Phi-4 Mini) — the Strict policy works fully offline
- Suggested commands never auto-run; risk classification adds an extra confirmation for
  dangerous commands

## Localization

- Strings live in compose-resources (`composeApp/src/commonMain/composeResources/values*`);
  a language switcher (`LocalAppLocale`) drives both the UI and the AI assistant's reply
  language (INFO/ASK)
- Languages: English, Russian
