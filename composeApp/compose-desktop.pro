# ProGuard-правила для release-дистрибутива desktop (Compose `*Release`-таски).
# Без них ProGuard обфусцирует/выкидывает методы, которые JNA и libsodium-стек
# ищут из нативного кода по имени через рефлексию (Native.initIDs → dispose),
# что валит запуск: UnsatisfiedLinkError "Can't obtain static method dispose".

# --- JNA: нативные методы и рефлексия из C-кода ---
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
# Структуры/коллбэки JNA отображаются на нативную память по полям — поля не трогать.
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-keepclassmembers class * implements com.sun.jna.Callback { *; }

# --- goterl resource-loader (распаковывает и грузит libsodium.so) ---
-keep class com.goterl.** { *; }
-keepclassmembers class com.goterl.** { *; }

# --- ionspin multiplatform libsodium bindings (JNA-привязки к sodium) ---
-keep class com.ionspin.kotlin.crypto.** { *; }
-keepclassmembers class com.ionspin.kotlin.crypto.** { *; }

# Нативные методы в принципе нельзя переименовывать.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
