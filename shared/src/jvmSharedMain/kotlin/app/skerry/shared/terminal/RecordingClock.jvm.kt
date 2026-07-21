package app.skerry.shared.terminal

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

actual fun epochMillis(): Long = System.currentTimeMillis()

private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

actual fun recordingStamp(): String = LocalDateTime.now().format(STAMP)
