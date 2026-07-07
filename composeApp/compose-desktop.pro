# ProGuard rules for the desktop release distribution (Compose `*Release` tasks).
# Without them ProGuard obfuscates/strips methods that the JNA and libsodium stack
# look up from native code by name via reflection (Native.initIDs → dispose),
# which breaks startup: UnsatisfiedLinkError "Can't obtain static method dispose".

# --- okio (file I/O: vault/hosts/tunnels/known_hosts) ---
# ROOT CAUSE of "Storage is damaged" in release: ProGuard optimization specialized
# okio's return type (created `Okio.buffer$...` returning RealBufferedSource while the body
# yields BufferedSource) → the JVM rejected the class with VerifyError "Bad return type" on the
# VERY FIRST file read (`fileSystem.read` in FileVault.unlock), which looked like a broken vault.
# Keep preserves the okio API, and disabling type specialization/class merging removes
# the source of the VerifyError (these optimizations systematically break okio bytecode).
-keep class okio.** { *; }
-keepclassmembers class okio.** { *; }
-dontwarn okio.**
-optimizations !method/specialization/*,!class/merging/*

# --- JNA: native methods and reflection from C code ---
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
# JNA structures/callbacks are mapped onto native memory by their fields — do not touch the fields.
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-keepclassmembers class * implements com.sun.jna.Callback { *; }

# --- goterl resource-loader (unpacks and loads libsodium.so) ---
-keep class com.goterl.** { *; }
-keepclassmembers class com.goterl.** { *; }

# --- ionspin multiplatform libsodium bindings (JNA bindings to sodium) ---
-keep class com.ionspin.kotlin.crypto.** { *; }
-keepclassmembers class com.ionspin.kotlin.crypto.** { *; }

# Native methods must never be renamed at all.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# --- SSH stack: sshj + BouncyCastle (crypto) ---
# Without these rules the release build fails to connect with an error like
# "Cannot invoke java.lang.Throwable.getMessage() because getCause() is null":
# sshj resolves cipher/KEX/signature factories and the BouncyCastle crypto provider by
# class names via reflection (SecurityUtils → Class.forName(BouncyCastleProvider),
# Factory.Named). ProGuard renames/strips these classes — algorithm negotiation
# fails, the real exception loses its cause, and a bare NPE surfaces at the top.
# BouncyCastle registers itself as a JCE provider and loads algorithms reflectively —
# its internals must not be touched.
-keep class org.bouncycastle.** { *; }
-keepclassmembers class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# sshj and its ASN.1 stack (com.hierynomus:asn-one). The packages net.schmizz.keepalive
# and net.schmizz.concurrent are siblings of net.schmizz.sshj, not subpackages, so
# we take all of net.schmizz.** (otherwise KeepAliveProvider/Event/Promise get lost).
-keep class net.schmizz.** { *; }
-keepclassmembers class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keepclassmembers class com.hierynomus.** { *; }
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**

# slf4j: logging facade of sshj/BouncyCastle (no binding in the distribution — no-op).
-dontwarn org.slf4j.**

# --- kotlinx.serialization: generated serializers and Companion ---
# Without these rules ProGuard in release strips/renames the synthetic
# `$serializer` and `Companion` members of @Serializable classes. Then Json.encode/decode
# fails and vault.json/hosts.json/tunnels.json cannot be read: on startup the vault
# shows the unlock form for an already existing file and falls into "file is
# damaged or unreadable" (UnlockResult.Corrupted). The rules are the canon from the
# kotlinx.serialization README (ProGuard is supported too, via `-if`).

# Serialization annotations are read at runtime.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Companion object of @Serializable classes (serializer() is resolved through it).
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# The serializer() method on the companion object of @Serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# serializer() on @Serializable objects (object declarations).
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Synthetic $serializer classes in full (together with the field descriptors).
-keep,includedescriptorclasses class **$$serializer { *; }

# Do not obfuscate the kotlinx.serialization runtime.
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# --- Our @Serializable models in full ---
# The canonical rules above are enough for most models (hosts.json/tunnels.json
# are readable), but NOT for the vault: `VaultFileBody`/`VaultMeta` are internal data classes, and
# `RecordType` is a @Serializable enum (kotlinx caches its serializer in synthetic members
# of the enum class itself — the `$$serializer`/Companion rules do not keep those). In release
# ProGuard broke exactly the VaultFileBody decode → the vault showed "Storage is damaged"
# even on a freshly created file (while the decode passes fine in debug). The app's models
# are small — we keep them and the generated serializers in full.
-keep @kotlinx.serialization.Serializable class app.skerry.** { *; }
-keepclassmembers class app.skerry.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class app.skerry.**$$serializer { *; }
