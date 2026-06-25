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

# --- kotlinx.serialization: сгенерированные сериализаторы и Companion ---
# Без этих правил ProGuard в release выкидывает/переименовывает синтетические
# `$serializer` и `Companion` у @Serializable-классов. Тогда Json.encode/decode
# валится, и vault.json/hosts.json/tunnels.json не читаются: vault на старте
# показывает форму разблокировки уже существующего файла и падает в «файл
# повреждён или не читается» (UnlockResult.Corrupted). Правила — канон из README
# kotlinx.serialization (поддерживаются и ProGuard через `-if`).

# Аннотации сериализации читаются в рантайме.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Companion-объект @Serializable-классов (через него резолвится serializer()).
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Метод serializer() на companion-объекте @Serializable-классов.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# serializer() у @Serializable-объектов (object).
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Синтетические $serializer-классы целиком (вместе с дескрипторами полей).
-keep,includedescriptorclasses class **$$serializer { *; }

# Рантайм kotlinx.serialization не обфусцировать.
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**
