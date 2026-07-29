# Skerry — coding guidelines

Rules for the code itself. `CLAUDE.md` owns the *process* (TDD loop, build gate, review fan-out,
hand-off); this file owns *what the code must look like once you're writing it*. Read it before
writing code — every rule here is a class of defect we already paid for in the 2026-07-02
pre-release review and the 2026-07-03/04 refactor (~115 files, −7646 lines). The goal is to write it
right the first time so a third such pass isn't needed.

## 1. Search before you write

Before adding a class, function, or pattern — **look for the existing one**. Nine copies of a
vault-backed JSON store and nine copies of an admin-token guard exist because every new feature was
written "from scratch, next to the old one". Grep for the keywords of the task; if similar code
already lives in two places, the answer is a shared abstraction, not a third copy.

Duplication rule: **the second repetition is already a signal**, at the third extraction is
mandatory. Tests included (see `RoutesTestSupport` on the server).

### Core (`shared/`)

| Task | Abstraction |
|---|---|
| Vault-backed JSON store (list of records) | `vault/VaultRecordCodec` |
| Atomic file write with 0600 permissions | `vault/atomicWriteUtf8` (vault / log / bio) |
| Constant-time secret comparison | `vault/constantTimeEquals` |
| Port-forward state | `ssh/ForwardState` |
| "Just a stream" transport (telnet/serial) | `ssh/StreamOnlyConnection` (jvmShared) |
| Blocking read loop of a shell channel (ssh/telnet/serial) | `ssh/StreamShellChannel` |
| SGR parsing, glyph metrics, reflow | `terminal/SgrParser`, `CharMetrics`, `TerminalReflow` |
| Sync client⇆server wire DTO | module `:sync-wire` (`app.skerry.sync.wire`) — never mirror by hand |
| Remote-desktop session as the UI sees it (pixels, updates, input) | `graphics/RemoteDesktopSession`, `RemoteDesktopUpdate`, `RemoteKeyEvent`, `RemoteDesktopQuality`, `RemoteDesktopCapabilities` — RDP and VNC adapters both implement this, protocol types stay behind it |
| ARGB pixel buffer (resize/blit/fill/copyRect) | `graphics/RemoteFramebuffer` |
| PCM playback and output-device enumeration | `audio/RemoteAudio`: `AudioOutputs`, `RemoteAudioPlayerFactory`, `RemoteAudioFormat` |
| Reading/writing a protocol PDU with bounds checks | `rdp/RdpIo`: `RdpReader`, `RdpWriter`, `RdpSource`, `RdpSink`; malformed input → `rdp/RdpProtocolException` |
| RDP connection entry point and live session | `rdp/RdpTransport`: `RdpTransport`, `RdpSession`, `RdpUpdate` |
| MS-RDPEDYC dynamic channel (clipboard/display/audio/graphics) | `rdp/egfx/DynamicChannelHandler` |
| Platform crypto injected into RDP (NLA, licensing) | `rdp/nla/NtlmCrypto`, `rdp/LicenseCrypto/RdpLicenseCrypto` |
| Server-certificate trust decision (TOFU by fingerprint) | `rdp/RdpCertificate/RdpCertificateVerifier` |
| VNC connection entry point, session, auth | `vnc/VncTransport`: `VncTransport`, `VncSession`, `VncUpdate`, `VncAuth` |
| Platform codecs injected into VNC (zlib, JPEG, DES challenge) | `vnc/VncCodecPorts`: `InflaterFactory`, `VncImageDecoder`, `VncChallengeResponder` |
| Shared-session wire protocol and its encryption | `share/SessionShareFrame` (`ShareFrame`, `ShareDirection`), `share/SessionShareCodec`, `share/ShareChannel` |
| Shared-session orchestration | `share/SessionShareHost` (host), `share/SharedSessionViewer` (viewer) |
| Runbook model, text format, storage | `runbook/Runbook`, `RunbookScript`, `RunbookMarker`, `RunbookStore` (+ `VaultRunbookStore`) |
| docker/k8s exec target and command building | `container/ContainerSpec`, `ContainerCommands`, `ContainerListing`; tunnelling a session through it — `container/ContainerTransport` |
| Mosh session key, framing, timing, wire messages | `mosh/MoshKey`, `MoshFragment`, `MoshTiming`, `MoshSync`, `MoshWire` (codec in jvmShared) |
| Host tag normalisation and prod-first ordering | `tag/Tags`: `normalizeTag`, `orderTagsProdFirst` |
| Risk scoring of a production command | `guard/ProductionGuard` + `ProductionGuardPolicy` |
| App version compare, release lookup, update settings | `update/UpdateVersion`, `ReleaseInfo`, `UpdateChecker`, `UpdateSettings` |
| Server: admin-token guard | route-scoped plugin (one, in Routes) — don't inline the check per route |
| Server: rate limit, path id, parameter limits | helpers `requiredPathId` / `limitParam` / the rate-limit helper |

### UI (`composeApp/`)

| Task | Abstraction |
|---|---|
| Any text on screen | `ui/design/DesignPrimitives.Txt` — never a raw `Text()` |
| Any icon | `ui/design/Sym` (Material Symbols glyph) + `DesignFonts` |
| Fonts | `ui/design/DesignFoundation`: `rememberUiFont`, `rememberMono`, `rememberMaterialSymbols` |
| Buttons | `PrimaryButton`, `GhostButton`, `CancelButton`, `IconBtn` |
| Small controls | `Toggle`, `Badge`, `Dot`, `MeterBar`, `NumberStepper`, `HoverTooltip` |
| Chips | `Chip` (label, active/inactive) vs `ChipButton` (action chip: colour, outline/filled, enabled) — pick one, don't add a third |
| Rules and separators | `HLine`, `VLine` |
| Dropdowns and modals | `AnchoredDropdown`, `DropdownField`, `ModalScrim` |
| Confirming a destructive action / informing | `ui/design/ConfirmActionDialog`, `NoticeDialog` |
| Sidebar and section chrome | `ui/design/SectionChrome`: `SidebarSectionTitle`, `SectionHeader`, `EmptyState`, `SidebarSearchField` |
| Field labels, caps, avatars | `FieldLabel`, `LabelCase.labelUppercase`, `InitialsAvatar` |
| Mobile chrome | `MobilePushHeader`, `MobileScreenTitle`, `MobileFabButton`, `MobileTagsEditor` |
| Mobile form fields (label caps + input) | `ui/mobile/MobileForm.kt`: `MobileFormField` / `MobileFormInput` |
| Tunnel/snippet form state (desktop and mobile) | `TunnelFormState`, `SnippetFormState` |
| Secret display | `VaultPresentation.secretStyle` |
| Terminal screen state (incl. scrollback search) | `ui/terminal/TerminalScreenState` |
| Tiled session panes (split/resize/navigate) | `ui/session/PaneLayout` |
| Remote-desktop screen | `ui/remote/RemoteDesktopController`, `RemoteDesktopScreenState`, `RemoteDesktopPanel` |
| Streaming an AI reply / parsing it | `ui/ai/AiStreamRunner`, `AiReplyParser` |
| Sealed sync token, health monitoring | `ui/sync/SealedTokenCodec`, `ServerHealthMonitor` |
| Sync failure reason → localised text | `SyncFailureReason` + `ui/sync/syncFailureText` (don't build strings in the controller) |

## 2. Size and decomposition — when you create it, not later

`TerminalView` grew to 1587 lines, `SettingsPanel` to 1465, `SshjTransport` to 719 — each had to be
cut apart in its own pass.

- **A file approaching ~400–500 lines gets split right away**: by screen section, by subsystem
  (connection / channel / forwards), by settings tab. A new section or feature goes into a new file
  in its own package, not appended to the end of an existing one.
- One Compose file = one screen or one reusable block. A screen's panels, dialogs and sidebars go
  into their own files next to it.
- Logic (parsing, state machines, protocols) does **not** live inside a composable or a UI
  controller. It belongs in a separate class in `shared/`, or next to the UI but pure and directly
  testable (the model: `AiStreamRunner` / `AiReplyParser` with direct security tests).

## 3. Coroutines and concurrency

The most expensive defect class of the refactor. No exceptions to these:

- **Never swallow `CancellationException`.** Any `catch (e: Exception)` around suspend code must
  rethrow it (`if (e is CancellationException) throw e`) — otherwise cancellation masquerades as a
  network error (bitten by: serial/telnet channels, `KtorSyncClient`, `SecretCopyAuthorizer`).
- **Nothing heavy or blocking on the UI thread.** Argon2id (64 MiB), file IO, log reading — through
  an injected dispatcher (the `kdfDispatcher` pattern from `VaultGateController`). On Android that's
  an ANR, on desktop a freeze.
- **Reset guard flags and "busy" state in `finally`**, or a cancelled coroutine wedges the feature
  forever (bitten by: biometrics in `SecretCopyAuthorizer`, `syncNow` stuck in Busy).
- **Read-modify-write on the vault only under `vault.transaction`** (bitten by: `VaultHostStore` vs
  the background merge). General form: assignment to shared state happens under the same mutex as
  the check that guarded it (bitten by: a double connect in `SyncCoordinator` leaking a Ktor client).
- **A long-lived connection must read incoming frames** — a WebSocket handler that only writes will
  miss Close and hang until the next publish.
- **TOCTOU in the UI**: capture the operation's parameters when the confirmation dialog opens, don't
  re-read them when OK is pressed (bitten by: SFTP overwrite being redirected by panel navigation).

## 4. Security by default, not "we'll harden it later"

- Files holding secrets: **`atomicWriteUtf8` only** (atomic + 0600). A plain `writeText` broke TOFU
  `known_hosts` and left world-readable vault files.
- **Validate input before side effects**: check the length and format of `deviceId`, codes and ids
  *before* a one-time code is spent or a DB query is made (400, not 500 plus a burned code).
- Intermediate key copies are zeroed; secret comparison is constant-time where a primitive exists.
- Invisible control bytes in source: escaped literals only (`""`, `Char(0x1F)`), never a raw
  byte inside a string — it is invisible in Read/grep and silently lost on edit.
- Server and AI output is an untrusted source (policies, confirmation, bidi sanitising).

## 5. UI: tokens, resources, parity

- **No hex colours in screens** — `D.*` tokens only (the refactor replaced ~70 hardcoded colours).
  Need a new shade? Add a token, checked against the prototype's `:root` block.
- **No string literals in the UI** — everything through resources, en + ru + zh at once.
- **No raw Compose Material components** where a design primitive exists (§1, UI table). `Txt` and
  `Sym` are used by 100+ and 70+ files respectively; a raw `Text()` or a stray icon is a bug.
- Desktop and mobile **share form state and logic** (`*FormState`); only the layout differs. A new
  form starts as a shared state class, then two thin views.
- Chrome follows the `docs/design/` prototypes 1:1 — don't invent buttons, toolbars or menus that
  aren't in the mock.
- Geometry derived from settings (font size → mouse hit-testing) is recomputed when the setting
  changes rather than cached forever; objects are not allocated on every recomposition (`remember`
  with correct keys).

## 6. Architecture and testability

- Contracts and domain types live in `commonMain`; platform libraries sit behind `expect`/`actual`.
  The UI sees common contracts only.
- **Controller dependencies are injected** (example: `SyncEngine` into `SyncCoordinator`). If a
  class can't be tested without network, disk or UI, its constructor is designed wrong.
- Concurrent controllers need tests for cancellation and re-entry — exactly what was missing for the
  bugs in §3. The TDD loop itself is in `CLAUDE.md` → *How we work*.
- **Delete dead code in the same commit that orphaned it**: old stores, screens, controllers and
  their tests, unused Gradle dependencies. "Leave it for now" is a future refactor.
- Dependencies only through the version catalog (`libs.versions.toml`), never raw coordinates.

## Self-review checklist before finishing a task

1. Did I create a copy of an existing pattern? (grep for something similar)
2. Did any file grow past ~500 lines because of my edits?
3. Does every `catch` around suspend code rethrow `CancellationException`? Guard flags in `finally`?
4. Nothing blocking on the UI thread? Shared state under a mutex/transaction?
5. Colours via tokens, strings via resources (en + ru + zh), text via `Txt`, icons via `Sym`,
   forms via shared state?
6. Secret-bearing files through `atomicWriteUtf8`? Validation before side effects?
7. Is the new code covered by tests (cancellation and races included), and is the dead code gone?
8. Build, test and review gates from `CLAUDE.md` → *How we work* all run?
