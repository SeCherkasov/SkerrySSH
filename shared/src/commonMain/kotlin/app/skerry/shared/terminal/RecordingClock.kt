package app.skerry.shared.terminal

/**
 * Wall-clock milliseconds since the Unix epoch. Needed by [SessionRecorder] for the asciicast
 * header's `timestamp` (when the recording was made) and to time its events. Kept as an
 * `expect` rather than pulling in a date-time library for two numbers; tests inject their own clock
 * into the recorder instead of calling this.
 */
expect fun epochMillis(): Long

/**
 * Local `yyyyMMdd-HHmmss` stamp for the exported file name ([castFileName]). Local rather than UTC:
 * the name is read by a person looking for "the recording I made this morning".
 */
expect fun recordingStamp(): String
