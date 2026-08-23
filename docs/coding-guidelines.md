# Skerry — coding guidelines

Rules for the code itself. `CLAUDE.md` owns the *process* (TDD loop, build gate, review fan-out,
hand-off); this file owns *what the code must look like once you're writing it*. Read it before
writing code — every rule here is a class of defect we already paid for in the 2026-07-02
pre-release review and the 2026-07-03/04 refactor (~115 files, −7646 lines). The goal is to write it
right the first time so a third such pass isn't needed.

The rules here that can be decided without judgement — translations complete in en + ru + zh, no
hardcoded UI literal, `Txt`/`Sym` instead of raw `Text`/`Icon`, design tokens instead of hex, no
Kotest or MockK, dependencies through the version catalog, `atomicWriteUtf8` on secret paths, a key
binding shipping with its Settings row — are enforced by `tools/harness/checks.py` and block the
commit. The rest is on you and on the reviewers. Nothing here is duplicated there; the checks read
these rules, they do not restate them.

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
| Out-of-band step status from the shell, and hiding the echo of what the client typed | `terminal/TerminalStepMark` (`STEP_MARK_OSC`, `TerminalStepMark`) + `TerminalEmulator.expectStep`, `terminal/TerminalEchoFilter` — the runbook layer only builds the probes (`runbook/RunbookMarker`) |
| Sync client⇆server wire DTO | module `:sync-wire` (`app.skerry.sync.wire`) — never mirror by hand |
| Remote-desktop session as the UI sees it (pixels, updates, input) | `graphics/RemoteDesktopSession`, `RemoteDesktopUpdate`, `RemoteKeyEvent`, `RemoteDesktopQuality`, `RemoteDesktopCapabilities` — RDP and VNC adapters both implement this, protocol types stay behind it |
| ARGB pixel buffer (resize/blit/fill/copyRect) | `graphics/RemoteFramebuffer` |
| PCM playback and output-device enumeration | `audio/RemoteAudio`: `AudioOutputs`, `RemoteAudioPlayerFactory`, `RemoteAudioFormat` |
| Open/reopen/teardown of a playback device | `audio/PcmPlayer`: `PcmPlayer` over `PcmSink` + `PcmSinkOpener` — a platform sink answers for the device only, never for when to open it |
| Reading/writing a protocol PDU with bounds checks | `rdp/RdpIo`: `RdpReader`, `RdpWriter`, `RdpSource`, `RdpSink`; malformed input → `rdp/RdpProtocolException` |
| RDP connection entry point and live session | `rdp/RdpTransport`: `RdpTransport`, `RdpSession`, `RdpUpdate` |
| MS-RDPEDYC dynamic channel (clipboard/display/audio/graphics) | `rdp/egfx/DynamicChannelHandler` |
| Platform H.264 decoder injected into RDP | `rdp/egfx/H264Decoder`: `H264DecoderFactory` + `YuvFrame` — the platform answers for the decoder only, the wire and the 4:4:4 assembly are `AvcCodec`'s. `available` is what decides whether AVC is advertised at all |
| Finding an executable on PATH (desktop) | `process/resolveExecutableOnPath` (ffmpeg, wl-clipboard) |
| Platform crypto injected into RDP (NLA, licensing) | `rdp/nla/NtlmCrypto`, `rdp/LicenseCrypto/RdpLicenseCrypto` |
| Server-certificate trust decision (TOFU by fingerprint) | `rdp/RdpCertificate/RdpCertificateVerifier` |
| Asking the user to vouch for a host's identity (SSH key or RDP certificate, new or changed) | `trust/HostTrust`: `HostTrustRequest`, `HostTrustPrompt` (suspend, the UI) + `HostTrustDecider` (sync, what a handshake can call), bridged by `trust/asDecider` in jvmShared |
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
| Buttons | `PrimaryButton`, `GhostButton`, `CancelButton`, `IconBtn` (hover/tooltip chrome); `ui/design/GlyphButton` for a touch glyph box — square tap target, `Role.Button`, optional long-press — don't hand-roll the `Box`+`Sym`+`clickable` shape again |
| Small controls | `Toggle`, `Badge`, `Dot`, `MeterBar`, `NumberStepper`, `HoverTooltip` (raw popup — a *row's* note goes through `RowNote` below) |
| A stored note on a row or in a panel | `ui/design/RowNote`: `rememberRowNote` + `RowNoteTooltip` for the hover variant (the dwell, the filter and the popup-suppression the host sidebar, the snippet library and the terminal palette all share), `NoteBlock` for the static one (keychain secret, run panel, list row, card) — it names itself for a screen reader, which a bare dim `Txt` does not. `MAX_NOTE_CHARS` is how much of a note any of them draws, `NOTE_PEEK_LINES` how many lines a row in a list peeks at; what is *stored* is capped by `shared/text/normalizeNotes` |
| A setting as a label + switch row | `ToggleRow` (optional second line); `SettingToggleRow` / `MobileSyncToggleRow` are its settings and sync scales and should converge onto it |
| A fact as a label + value row in a detail panel | `KeyValueRow`; `InfoRow` (session info panel) and `CardRow` (tunnel dashboard) are the same shape and should converge onto it |
| Chips | `Chip` (label, active/inactive) vs `ChipButton` (action chip: colour, outline/filled, enabled) — pick one, don't add a third |
| Rules and separators | `HLine`, `VLine` |
| A modal question a connection asks and a person answers | `ui/design/PromptQueue` — the turnstile (two hosts asking at once queue instead of overwriting each other's dialog), the deadline that starts when the question reaches the screen, and the answer bound to the question it was given for. The 2FA prompt (`KeyboardInteractivePromptController`) and the host-key question (`HostTrustPromptController`) are the same shape; a third must not write it a third time |
| A key fingerprint on screen | `ui/design/FingerprintBox` — monospaced, wrapping, tinted by what it means; the known-hosts panel and the trust dialog show the same key |
| Dropdowns and modals | `AnchoredDropdown`, `DropdownField`, `ModalScrim`, `modalBody` (scrolling body under pinned actions) |
| Pop-up menu (right click, "⋮") | `ui/design/MenuPanel`: `MenuPanel` + one of its two rows — `MenuItem` (label only; destructive via `color`) or `MenuActionRow` (the glyph the same action has in a toolbar, then the label). Pick one, don't add a third for an action; a row carrying *state* is the remote desktop's own pair (`RemoteDesktopMenus`' `MenuRow` picked-with-a-check and `CheckRow` a switch), local to that menu. Every menu opened by a right click or a "⋮" wears the panel, the platform text context menu included (through `ui/design/SkerryTextContextMenu`) — a dropdown anchored to a form field belongs to the row above instead. Four hand-rolled copies had drifted onto the field-border tint, which is the same colour as the panel line on the default theme and a different one on each of the other eight (issue #224). The panel measures itself against its widest row, with the floor both rows carry. `width` is for the menus that must not: one holding text it cannot measure (a remote clipboard preview) and the menu beside it, which would otherwise open at a different width from the same bar; a host catalog, which is a list; and the work bar's overflow, whose row *set* changes with the window and would reopen at a different width after every resize. A menu that scrolls puts the scroll *inside* the panel, so the frame bounds the viewport instead of sliding away with the rows |
| Confirming a destructive action / informing | `ui/design/ConfirmActionDialog`, `NoticeDialog` |
| A command a confirmation quotes | `ui/design/CommandQuote` + `ClippedNotice`. It owns the whole rule the assistant's card, the mobile AI bar and the production guard's dialog have to obey: wrap rather than scroll sideways (a line drawn past the right edge is invisible, not shortened), grow to N lines then scroll, a focus stop while it scrolls, measured in lines rather than pixels, cut at `MAX_DRAWN_COMMAND_CHARS`, and anything that does not draw as itself spelled out as `<U+202E>`. What the box cannot show, `ClippedNotice` states with the command's real length. Three hand-rolled copies had already drifted apart before it existed (issue #223) |
| A command a *row* draws | `ui/design/CommandQuote.CommandLine` — the list surface of the same rule: escaped and bounded exactly like the quote, monospaced, pinned to `TextDirection.Ltr`, ellipsized at `maxLines`, no live region and no scroll of its own. The snippet library row, the terminal palette, the phone card, the runbook step rows and the share picker had all written it by hand. A row that cannot show the whole line is not the surface that may run it — that is what the quote is for |
| A tag as a chip | `ui/design/UntrustedText.tagChipLabel` — the `#` the model value does not carry, and nothing that draws as nothing. One definition: host chips and snippet chips are the same chip (`snippetTagLabel` and `hostTagChipLabel` were two copies of it) |
| A list drawn as folders | `ui/design/Folders` + `FolderSectionHeader`: `foldersOf`/`hasFolders`/`folderNames` sort and bucket, `FolderSections` emits header-plus-rows into the caller's own layout, `FolderCollapse` + `folderCollapseKey(scope, name)` hold the fold. The snippet library, the runbooks and the keychain all file records the way the host sidebar does; the host sidebar keeps its own header because there the row is a drag handle and a drop target. Two rules the key carries: the bucket for unfiled records is keyed by something no record can hold, and the name reaches the persisted set only as a digest — that set is written outside the vault, and a keychain folder name is what the payload is encrypted to keep |
| Filing a record into a folder | `ui/design/GroupSelect.GroupSelectField` on desktop, `ui/mobile/MobileGroupSelect.MobileGroupSelectField` on a phone — "No group", the folders in use, "New group…" and the dialog behind it. A select, never free text: typing lets a `Production` and a `production` become two folders that look like one. The phone's create dialog is mounted at the sheet root and the value hoisted to the caller, because the overlay stands above the form. What is stored goes through `shared/text/normalizeGroup` |
| A text field inside a modal | `ui/design/FormTextField.ModalTextField` — border, placeholder, caret rules and the accessible name of every dialog field; `MobileFormInput` is its phone twin (row above) |
| Sidebar and section chrome | `ui/design/SectionChrome`: `SidebarSectionTitle`, `SectionHeader`, `EmptyState`, `SidebarSearchField` |
| Field labels, caps, avatars | `FieldLabel`, `LabelCase.labelUppercase`, `InitialsAvatar` |
| A caption and its input as one unit | `ui/design/FieldLabelScope`: `FormField(label) { … }` publishes the caption, `Modifier.fieldName()` on the input adopts it as its accessible name. A caption written as a sibling `Txt`/`FieldLabel` leaves the input anonymous — the shape that left the vault gate and the sync pairing screen with unnamed password boxes. Outside a `FormField`, name the input from its placeholder: `fieldName(fallback = …)`. Store captions in sentence case; `FormField` uppercases for drawing so the *name* is not shouted |
| A stable handle on navigation for a click test | `ui/app/UiTags` — a rail button draws a font ligature and every label is localized, so neither is a handle. Narrow on purpose: navigation, screens, and the form controls with no usable text |
| The real shell on screen for a UI test | `desktopTest/ui/desktop/ShellHarness`: `runDesktopShell` / `runMobileShell` over the demo graph, `runForm` for one composable, plus `onField` / `onCatalog` / `onTab` / `press`. Both shells run on the desktop JVM — the mobile UI is common code, so Android parity is checked without an emulator. Don't hand-roll a `runComposeUiTest` shell |
| A clipboard for a test | `desktopTest/ui/design/FakeSystemClipboard` — records writes, hands back what it holds, and refuses reads or the first N writes the way `wl-copy`/`wl-paste` do; `commonTest/ui/design/FakeDirectClipboard` for the platform path underneath it (present or not, accepting or refusing). Six copies of the two had grown across four test files |
| Mobile chrome | `MobilePushHeader`, `MobileScreenTitle`, `MobileFabButton`, `MobileTagsEditor` |
| Mobile form fields (label caps + input) | `ui/mobile/MobileForm.kt`: `MobileFormField` / `MobileFormInput` |
| Caret and select-on-focus for a field whose value is a caller-owned `String` | `ui/design/FieldDraft`: `rememberFieldDraft` + `Modifier.fieldFocus`, told whether the field is masked and single-line so it applies the never-select rules itself; `rememberSeededDraft` for a find or filter bar, which selects its query only while it is the one the bar opened with. A field that owns its own `TextFieldValue` and selects on *open* (`PathJumpField`, `TerminalSearchBar`) is the other, deliberate shape — don't hand-roll a third |
| Tunnel/snippet form state (desktop and mobile) | `TunnelFormState`, `SnippetFormState` |
| Secret display | `VaultPresentation.secretStyle` |
| One line of text a server or a team member wrote | `ui/design/UntrustedText`: `untrustedLabel` (row names, host labels, team and space names, container and metric rows) and `sanitizeServerText` for the longer multi-line kind (a prompt, a failure reason). Cap, drop the control and format characters, cut without splitting a surrogate pair. A profile's own free-form note keeps its lines: same helper, `sanitizeServerText(note, MAX_NOTE_CHARS, allowNewlines = true)`. Don't write a fourth variant — `spaceLabel`, `Host.rowLabel` and `HostMetrics.hostText` are all this one. A machine name goes through `sanitizeServerHost`: the same filter, elided in the middle, because a host cut at the head alone loses the domain it actually sits in |
| Terminal screen state (incl. scrollback search) | `ui/terminal/TerminalScreenState` |
| Tiled session panes (split/resize/navigate) | `ui/session/PaneLayout` |
| Remote-desktop screen | `ui/remote/RemoteDesktopController`, `RemoteDesktopScreenState` |
| System clipboard text (both directions) | `ui/terminal/ClipboardText`: `rememberSystemClipboard()` → `SystemClipboard.read()` / `write()`. It picks the clipboard: the platform's own path where one exists (`wl-clipboard` on Wayland, which AWT cannot see) and Compose everywhere else, and it keeps both directions on the same buffer. A bare `clipboard.setClipEntry(...)` writes where no native Wayland app can paste from and reads back nothing — the terminal, the assistant panel and the remote-desktop bridge had each written that line by hand, and the bridge was dead on Wayland for it (issue #282). A refused write throws where that path owns the clipboard — there is no second buffer to fall back to — and a refused read throws rather than reading as empty, so a surface can tell "nothing to send" from "this did not work". The vault's password copy stays on AWT: its sensitivity hint and clear timer have no `wl-copy` equivalent |
| Remote-desktop session controls (both platforms) | `ui/remote/RemoteDesktopMenus` (icon button, menu host, display menu, its rows, screenshot action) and `ui/remote/RemoteClipboardMenu` (the clipboard menu, its actions, and how a string the server chose is drawn), laid out by `RemoteDesktopBar` + `RemoteBarState` on desktop and `RemoteDesktopPanel` on a phone |
| Streaming an AI reply / parsing it | `ui/ai/AiStreamRunner`, `AiReplyParser` |
| Sealed sync token, health monitoring | `ui/sync/SealedTokenCodec`, `ServerHealthMonitor` |
| Sync failure reason → localised text | `SyncFailureReason` + `ui/sync/syncFailureText` (don't build strings in the controller) |
| A state that changes on its own and has to be heard | `ui/design/StatusAnnouncer` — a polite live region that carries the message itself and stays composed across the change (a node inserted together with its text announces nothing). Put it above the `when` that picks the card, not inside a branch, and give it the empty string for the states worth no announcement. `AiSection`, `AiModelPicker` and `MobileAiScreen` still set `liveRegion` by hand on a visible `Txt` and should converge onto this |
| The error line under a form | `ui/sync/SyncFormError` — icon + text in `sunset`, composed even when there is no error so the announcer outlives it. `announce = false` when the screen already keeps an announcer above the branch this row sits in |
| Keeping the keyboard on a widget that lives by it (terminal, remote framebuffer) | `ui/design/KeyboardClaim`: `ClaimKeyboard` claims focus back after a modal closes, after the window returns and when chrome hands it back, and `Modifier.handsKeyboardBack()` marks the chrome that takes it on a mouse press (sidebar handle, host rows, group toggles). Compose clears focus on a lasting window blur and restores nothing, so without this a live session silently swallows every keystroke until it is clicked. The rule that makes it safe is ownership: the claim is only ever made for the widget the keyboard last belonged to — a field beside the session (a connect password, the assistant, the hosts filter) keeps it |
| A per-key state store over a store the caller pins one key of | `shared/sync/KeyedStateStore` (the sync cursor per `ServerLink`, a team space's cursor per link + space) |

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
- A locale quotes with its own marks: `“ ”` in en and zh, `« »` in ru, never the straight `"`,
  and corner brackets nowhere. In `composeResources` (not `androidApp/src/main/res`, which is
  AAPT and behaves the Android way) the plugin decodes `\n`, `\t`, `\uXXXX` and `\\` and nothing
  else, so an Android-style `\"` or `\'` is drawn with the backslash on it — and substitution is
  a regex over `%N$s`/`%N$d`, not `String.format`, so `%%` stays doubled and a bare `%s` is never
  filled. `StringEscapeTest` sweeps every string in every locale for all four.
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
