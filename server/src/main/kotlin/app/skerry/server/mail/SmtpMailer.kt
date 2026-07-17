package app.skerry.server.mail

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.*
import java.net.Socket
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocketFactory
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Lightweight SMTP sender — zero dependencies. Opens a socket, speaks raw SMTP commands,
 * and sends plain-text UTF-8 email. STARTTLS or SMTPS is selected based on [smtpTls].
 *
 * When [enabled] is false, all methods are no-ops (mail subsystem is off).
 */
class SmtpMailer(
    private val enabled: Boolean,
    private val smtpHost: String,
    private val smtpPort: Int,
    private val smtpUser: String,
    private val smtpPassword: String,
    private val smtpFrom: String,
    private val smtpTls: Boolean,
) {
    private val logger = LoggerFactory.getLogger(SmtpMailer::class.java)

    fun send(to: String, subject: String, htmlBody: String, textBody: String) {
        if (!enabled || smtpHost.isBlank()) return
        try {
            doSend(to, subject, htmlBody, textBody)
        } catch (e: Exception) {
            logger.error("[mail] send failed: {}", e.message)
        }
    }

    private fun doSend(to: String, subject: String, htmlBody: String, textBody: String) {
        val rawSocket = Socket(smtpHost, smtpPort)
        try {
            val socket = if (smtpTls && smtpPort != 465) {
                // STARTTLS on plain socket
                val cmd = Cmd(rawSocket)
                cmd.read() // 220
                cmd.send("EHLO skerry-sync")
                cmd.readMulti()
                cmd.send("STARTTLS")
                cmd.read() // 220
                val factory = SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
                val ssl = factory.createSocket(rawSocket, smtpHost, smtpPort, true)
                rawSocket.close()
                Cmd(ssl)
            } else if (smtpPort == 465) {
                // SMTPS — TLS from the start
                val ssl = SSLSocketFactory.getDefault().createSocket(smtpHost, smtpPort)
                Cmd(ssl)
            } else {
                Cmd(rawSocket)
            }

            socket.read()       // 220
            socket.send("EHLO skerry-sync")
            socket.readMulti()

            if (smtpUser.isNotBlank()) {
                socket.send("AUTH LOGIN")
                socket.read()   // 334
                socket.send(Base64.getEncoder().encodeToString(smtpUser.toByteArray(StandardCharsets.UTF_8)))
                socket.read()   // 334
                socket.send(Base64.getEncoder().encodeToString(smtpPassword.toByteArray(StandardCharsets.UTF_8)))
                socket.read()   // 235
            }

            socket.send("MAIL FROM:<${extractEmail(smtpFrom)}>")
            socket.read()
            socket.send("RCPT TO:<$to>")
            socket.read()
            socket.send("DATA")
            socket.read()       // 354

            val message = buildString {
                append("From: $smtpFrom\r\n")
                append("To: $to\r\n")
                append("Subject: =?UTF-8?B?${Base64.getEncoder().encodeToString(subject.toByteArray(StandardCharsets.UTF_8))}?=\r\n")
                append("MIME-Version: 1.0\r\n")
                append("Content-Type: multipart/alternative; boundary=\"skerry-boundary\"\r\n")
                append("\r\n")
                append("--skerry-boundary\r\n")
                append("Content-Type: text/plain; charset=UTF-8\r\n\r\n")
                append(textBody)
                append("\r\n\r\n")
                append("--skerry-boundary\r\n")
                append("Content-Type: text/html; charset=UTF-8\r\n\r\n")
                append(htmlBody)
                append("\r\n\r\n")
                append("--skerry-boundary--\r\n")
                append(".\r\n")
            }
            socket.socket.getOutputStream().write(message.toByteArray(StandardCharsets.UTF_8))
            socket.socket.getOutputStream().flush()
            socket.read()       // 250 OK

            socket.send("QUIT")
        } finally {
            try { rawSocket.close() } catch (_: Exception) {}
        }
    }

    private fun extractEmail(from: String): String {
        val m = Regex("<([^>]+)>").find(from)
        return m?.groupValues?.get(1) ?: from.trim()
    }

    private class Cmd(val socket: Socket) {
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))

        fun send(cmd: String) {
            writer.write("$cmd\r\n")
            writer.flush()
        }

        fun read(): String {
            val line = reader.readLine() ?: throw IOException("SMTP connection closed")
            if (line.length >= 4 && (line[0] == '4' || line[0] == '5')) {
                throw IOException("SMTP error: $line")
            }
            return line
        }

        fun readMulti() {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.length < 4 || line[3] == ' ') break
            }
        }
    }
}

// --- Mail brand config from JSON ---

@Serializable
data class MailBrandConfig(
    val app_name: Map<String, String> = mapOf("en" to "Skerry"),
    val subjects: Map<String, Map<String, String>> = emptyMap(),
    val lang_fallback: String = "en",
) {
    fun appName(lang: String = "en"): String =
        app_name[lang] ?: app_name[lang_fallback] ?: "Skerry"

    fun subject(category: String, lang: String = "en"): String =
        subjects[category]?.get(lang)
            ?: subjects[category]?.get(lang_fallback)
            ?: category
}

fun loadMailConfig(path: String): MailBrandConfig {
    val logger = LoggerFactory.getLogger(SmtpMailer::class.java)
    return try {
        val file = File(path)
        if (file.exists()) {
            Json { ignoreUnknownKeys = true }.decodeFromString<MailBrandConfig>(file.readText())
        } else {
            // Fallback to classpath resource
            val stream = SmtpMailer::class.java.classLoader.getResourceAsStream("mail-config.json")
            if (stream != null) {
                Json { ignoreUnknownKeys = true }.decodeFromString<MailBrandConfig>(stream.reader().readText())
            } else {
                MailBrandConfig()
            }
        }
    } catch (e: Exception) {
        logger.error("[mail] failed to load config from {}: {}; using defaults", path, e.message)
        MailBrandConfig()
    }
}
