# ProGuard-правила для release-дистрибутива desktop (Compose `*Release`-таски).
# Без них ProGuard обфусцирует/выкидывает методы, которые JNA и libsodium-стек
# ищут из нативного кода по имени через рефлексию (Native.initIDs → dispose),
# что валит запуск: UnsatisfiedLinkError "Can't obtain static method dispose".

# --- okio (файловый I/O: vault/hosts/tunnels/known_hosts) ---
# ПЕРВОПРИЧИНА «Storage is damaged» в release: ProGuard-оптимизация специализировала
# тип возврата okio (создавала `Okio.buffer$...` с возвратом RealBufferedSource при теле,
# отдающем BufferedSource) → JVM роняла класс VerifyError «Bad return type» на ПЕРВОМ же
# чтении файла (`fileSystem.read` в FileVault.unlock), что выглядело как битый vault.
# Keep сохраняет API okio, а отключение специализации типов/слияния классов убирает
# источник VerifyError (эти оптимизации systematically ломают okio-байткод).
-keep class okio.** { *; }
-keepclassmembers class okio.** { *; }
-dontwarn okio.**
-optimizations !method/specialization/*,!class/merging/*

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

# --- SSH-стек: sshj + BouncyCastle (крипто) ---
# Без этих правил release-сборка коннектится с ошибкой вида
# «Cannot invoke java.lang.Throwable.getMessage() because getCause() is null»:
# sshj резолвит фабрики шифров/KEX/подписей и крипто-провайдер BouncyCastle по
# именам классов через рефлексию (SecurityUtils → Class.forName(BouncyCastleProvider),
# Factory.Named). ProGuard переименовывает/выкидывает эти классы — согласование
# алгоритмов падает, реальное исключение теряет cause, и наверх всплывает голый NPE.
# BouncyCastle регистрируется как JCE-провайдер и грузит алгоритмы рефлексивно —
# трогать его внутренности нельзя.
-keep class org.bouncycastle.** { *; }
-keepclassmembers class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# sshj и его ASN.1-стек (com.hierynomus:asn-one). Пакеты net.schmizz.keepalive
# и net.schmizz.concurrent — соседи net.schmizz.sshj, а не подпакеты, поэтому
# берём весь net.schmizz.** (иначе KeepAliveProvider/Event/Promise теряются).
-keep class net.schmizz.** { *; }
-keepclassmembers class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keepclassmembers class com.hierynomus.** { *; }
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**

# slf4j: фасад логирования sshj/BouncyCastle (без биндинга в дистрибутиве — no-op).
-dontwarn org.slf4j.**

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

# --- Наши @Serializable-модели целиком ---
# Канонических правил выше хватает для большинства моделей (hosts.json/tunnels.json
# читаются), но НЕ для vault: `VaultFileBody`/`VaultMeta` — internal data class, а
# `RecordType` — @Serializable enum (его сериализатор kotlinx кэширует в синтетических
# членах самого enum-класса — `$$serializer`/Companion-правила их не держат). В release
# ProGuard ломал именно decode VaultFileBody → vault показывал «Storage is damaged»
# даже на свежесозданном файле (decode при этом успешно проходит в debug). Модели
# приложения малы — держим их и сгенерированные сериализаторы целиком.
-keep @kotlinx.serialization.Serializable class app.skerry.** { *; }
-keepclassmembers class app.skerry.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class app.skerry.**$$serializer { *; }
