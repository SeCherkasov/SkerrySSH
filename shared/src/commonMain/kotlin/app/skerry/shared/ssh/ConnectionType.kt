package app.skerry.shared.ssh

import kotlinx.serialization.Serializable

/**
 * Transport of a connection profile. [SSH] — interactive shell over SSH (SFTP, port forwarding,
 * metrics). [TELNET] — raw TCP stream with Telnet option negotiation (RFC 854), no auth/encryption,
 * no SFTP/forwarding. [SERIAL] — local serial port (desktop: native port, Android: USB-OTG); in the
 * profile `address` holds the device name and `port` holds the baud rate.
 *
 * Lives in package `ssh` as a transport tag: [SshTarget.connectionType] feeds it to the transport
 * router ([RoutingTransport]), [app.skerry.shared.host.Host.connectionType] to the profile.
 * Serialized by name (like [app.skerry.shared.ai.AiPolicy]): enum order doesn't affect backward
 * compatibility; a missing field in old files defaults to [SSH].
 */
@Serializable
enum class ConnectionType { SSH, TELNET, SERIAL }
