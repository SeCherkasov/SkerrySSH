package app.skerry.shared.ssh

import kotlinx.serialization.Serializable

/**
 * Transport of a connection profile. [SSH] — interactive shell over SSH (SFTP, port forwarding,
 * metrics). [MOSH] — SSH is used only to launch `mosh-server` (same address/port/auth/jump as
 * [SSH]), then the session itself runs over mosh's encrypted UDP protocol; no SFTP/forwarding.
 * [TELNET] — raw TCP stream with Telnet option negotiation (RFC 854), no auth/encryption,
 * no SFTP/forwarding. [SERIAL] — local serial port (desktop: native port, Android: USB-OTG); in the
 * profile `address` holds the device name and `port` holds the baud rate.
 *
 * Lives in package `ssh` as a transport tag: [SshTarget.connectionType] feeds it to the transport
 * router ([RoutingTransport]), [app.skerry.shared.host.Host.connectionType] to the profile.
 * Serialized by name (like [app.skerry.shared.ai.AiPolicy]): enum order doesn't affect backward
 * compatibility; a missing field in old files defaults to [SSH].
 */
@Serializable
enum class ConnectionType { SSH, MOSH, TELNET, SERIAL }

/**
 * Whether the profile authenticates over SSH: username/credentials/jump host apply. True for
 * [ConnectionType.SSH] and [ConnectionType.MOSH] (Mosh bootstraps through an SSH hop with the
 * profile's full auth); Telnet/Serial have no authentication at all.
 */
val ConnectionType.usesSshAuth: Boolean
    get() = this == ConnectionType.SSH || this == ConnectionType.MOSH
