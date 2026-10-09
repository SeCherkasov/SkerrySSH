package app.skerry.shared.sftp

import java.nio.file.Path
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.server.SftpEventListener
import org.apache.sshd.sftp.server.SftpSubsystemFactory

/** Loopback-only SFTP fixture; the caller owns the server and its temporary filesystem. */
internal fun startSftpTestServer(
    root: Path,
    user: String,
    password: String,
    listener: SftpEventListener? = null,
    metadataDelayMillis: Long = 0,
): SshServer = SshServer.setUpDefaultServer().apply {
    host = "127.0.0.1"
    port = 0
    keyPairProvider = SimpleGeneratorHostKeyProvider()
    setPasswordAuthenticator { name, secret, _ -> name == user && secret == password }
    subsystemFactories = listOf(SftpSubsystemFactory.Builder().apply {
        listener?.let { addSftpEventListener(it) }
        if (metadataDelayMillis > 0) addSftpEventListener(object : SftpEventListener {
            override fun received(session: ServerSession, type: Int, id: Int) {
                // Exercise latency selection without adding requests to the transfer protocol.
                if (type == SftpConstants.SSH_FXP_STAT) Thread.sleep(metadataDelayMillis)
            }
        })
    }.build())
    fileSystemFactory = VirtualFileSystemFactory(root)
    start()
}
