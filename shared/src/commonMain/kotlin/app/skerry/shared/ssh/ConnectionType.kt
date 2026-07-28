package app.skerry.shared.ssh

import kotlinx.serialization.Serializable

/**
 * Transport of a connection profile. [SSH] — interactive shell over SSH (SFTP, port forwarding,
 * metrics). [MOSH] — SSH is used only to launch `mosh-server` (same address/port/auth/jump as
 * [SSH]), then the session itself runs over mosh's encrypted UDP protocol; no SFTP/forwarding.
 * [TELNET] — raw TCP stream with Telnet option negotiation (RFC 854), no auth/encryption,
 * no SFTP/forwarding. [SERIAL] — local serial port (desktop: native port, Android: USB-OTG); in the
 * profile `address` holds the device name and `port` holds the baud rate. [VNC] — remote desktop
 * over the RFB protocol (framebuffer + input, not a byte stream): `address`/`port` name the RFB
 * server (default 5900), `credentialId` holds the optional VNC password (no username); it does not
 * flow through the shell/terminal stack (see [app.skerry.shared.vnc.VncTransport]). [LOCAL] — a
 * local shell over a pseudo-terminal on this machine (no network): `address` optionally holds the
 * path to the shell binary to run (blank → the system default shell), `port`/`username`/`credentialId`/jump/
 * keep-alive are unused and there is no authentication (see [app.skerry.shared.local.LocalShellTransport]).
 * [CONTAINER] — a shell inside a Docker container or Kubernetes pod: every SSH field describes the
 * host that runs the `docker`/`kubectl` CLI, and [app.skerry.shared.host.Host.container] says what
 * to exec into (see [app.skerry.shared.container.ContainerTransport]); the session is terminal-only
 * (no SFTP/forwarding — those would act on the host, not the container).
 *
 * Lives in package `ssh` as a transport tag: [SshTarget.connectionType] feeds it to the transport
 * router ([RoutingTransport]), [app.skerry.shared.host.Host.connectionType] to the profile.
 * Serialized by name (like [app.skerry.shared.ai.AiPolicy]): enum order doesn't affect backward
 * compatibility; a missing field in old files defaults to [SSH].
 */
@Serializable
enum class ConnectionType { SSH, MOSH, TELNET, SERIAL, VNC, LOCAL, CONTAINER, RDP }

/**
 * Whether the profile authenticates over SSH: username/credentials/jump host apply. True for
 * [ConnectionType.SSH], [ConnectionType.MOSH] (Mosh bootstraps through an SSH hop with the
 * profile's full auth) and [ConnectionType.CONTAINER] (the container CLI runs over an SSH hop);
 * Telnet/Serial/VNC have no SSH authentication (VNC has its own password — see [isVnc]).
 */
val ConnectionType.usesSshAuth: Boolean
    get() = this == ConnectionType.SSH || this == ConnectionType.MOSH || this == ConnectionType.CONTAINER

/**
 * Whether the live session runs over an SSH connection, so SSH session behavior applies: keep-alive
 * pings and auto-reconnect after a drop. True for [ConnectionType.SSH] and
 * [ConnectionType.CONTAINER] (its `docker`/`kubectl` exec rides an SSH channel). NOT Mosh: it
 * heartbeats itself and survives outages by design, so reconnecting there would open a brand-new
 * remote session behind the user's back.
 */
val ConnectionType.carriedBySsh: Boolean
    get() = this == ConnectionType.SSH || this == ConnectionType.CONTAINER

/**
 * Whether the profile is a VNC/RFB remote desktop. VNC authenticates with an optional password
 * (stored as [app.skerry.shared.vault.CredentialSecret.Password], like SSH) but has no username,
 * private key, jump host or keep-alive — so the form gates auth on this separately from
 * [usesSshAuth].
 *
 * Narrower than [isRemoteDesktop]: this one means the RFB protocol specifically (framebuffer stack,
 * VNC-Auth), the other means the "remote desktop" section a profile is filed under.
 */
val ConnectionType.isVnc: Boolean
    get() = this == ConnectionType.VNC

/**
 * Whether the profile is a remote desktop rather than a terminal-style connection — the split the
 * shell navigates by: remote desktops have their own catalog, their own creation form and their own
 * rail item / bottom tab, while everything else lives under the terminal section.
 *
 * VNC and RDP; the exhaustive `when`s over [ConnectionType] elsewhere are what force each new
 * transport to declare where it belongs.
 */
val ConnectionType.isRemoteDesktop: Boolean
    get() = when (this) {
        ConnectionType.VNC, ConnectionType.RDP -> true
        ConnectionType.SSH, ConnectionType.MOSH, ConnectionType.TELNET, ConnectionType.SERIAL,
        ConnectionType.LOCAL, ConnectionType.CONTAINER,
        -> false
    }

/**
 * Whether the profile is an RDP remote desktop. Unlike VNC it authenticates with a *user name* and
 * password (and optionally a domain, written into the user name as `DOMAIN\user` rather than stored
 * as its own field — that is the form every RDP client accepts and the one users already type), so
 * the connection form shows the user field for it while hiding the SSH-only options.
 */
val ConnectionType.isRdp: Boolean
    get() = this == ConnectionType.RDP
