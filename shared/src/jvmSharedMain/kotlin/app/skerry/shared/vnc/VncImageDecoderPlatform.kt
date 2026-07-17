package app.skerry.shared.vnc

/**
 * Platform JPEG decoder for Tight JPEG rectangles: ImageIO on desktop, BitmapFactory on Android
 * (javax.imageio doesn't exist on Android, so this is expect/actual rather than shared JVM code).
 * Returns null only if a platform somehow has no decoder; the transport treats null as "no JPEG".
 */
expect fun platformVncImageDecoder(): VncImageDecoder?
