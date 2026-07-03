package app.skerry.shared.ssh

import java.io.IOException
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.bouncycastle.jce.provider.BouncyCastleProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordUtils

/** Desktop-реализация [SshTransport] поверх sshj (JVM). */
class SshjTransport(
    private val hostKeyVerifier: HostKeyVerifier,
) : SshTransport {

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection =
        withContext(Dispatchers.IO) {
            ensureCryptoProvider()
            val client = SSHClient()
            // TCP connect-timeout: у sshj дефолт 0 = ждать бесконечно. Без него «Test connection» к
            // несуществующему/закрытому файрволом адресу висит без возможности отмены через UI.
            // (Протокольный таймаут KEX/I/O — отдельно, дефолт sshj ~30 c; пинг round-trip — свой.)
            client.connectTimeout = CONNECT_TIMEOUT_MILLIS
            // Согласованный при KEX шифр (client→server) перехватываем верификатором алгоритмов:
            // в sshj 0.40 он вызывается синхронно на IO-потоке внутри connect() (после NEWKEYS, до
            // возврата), а читаем после connect() — нужна потокобезопасная публикация, поэтому
            // AtomicReference. Верификатор всегда пропускает (true): проверкой шифров не занимаемся,
            // только снимаем имя для info-панели; host-key проверка — отдельная цепочка (addHostKeyVerifier).
            val negotiatedCipher = AtomicReference<String?>(null)
            client.transport.addAlgorithmsVerifier { negotiated ->
                negotiatedCipher.set(negotiated.client2ServerCipherAlgorithm)
                true
            }
            val hostKeyRejected = installHostKeyVerifier(client)

            try {
                client.connect(target.host, target.port)
            } catch (e: IOException) {
                client.close()
                // Адрес хоста в текст сообщения не выносим (логи/краш-репортеры): метаданные коннекта
                // чувствительны в zero-knowledge клиенте. Диагностический детайл остаётся в cause (e).
                if (hostKeyRejected.get()) {
                    throw SshHostKeyRejectedException("Ключ хоста отвергнут верификатором")
                }
                throw SshConnectionException("Не удалось установить соединение", e)
            }

            authenticate(client, target, auth)

            // Ident сервера sshj отдаёт без префикса (`getServerVersion()` = serverID.substring(8)),
            // восстанавливаем полную форму `SSH-2.0-<software>` как в статус-баре. Читаем синхронно
            // на этом же IO-потоке после connect() — identification exchange уже завершён, гонки нет.
            // (Вымерший `SSH-1.99-` сервер отобразился бы как `SSH-2.0-` — substring(8) одинаков; косметика.)
            val serverVersion = runCatching { client.transport.serverVersion }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { "SSH-2.0-$it" }
            SshjConnection(client, negotiatedCipher.get(), serverVersion)
        }

    /**
     * Повесить на [client] адаптер нашего [hostKeyVerifier]. Возвращённый флаг взводится при отказе:
     * verify() вызывается из IO-потока sshj, а флаг читается из корутины после connect() —
     * нужна потокобезопасная видимость, поэтому AtomicBoolean.
     */
    private fun installHostKeyVerifier(client: SSHClient): AtomicBoolean {
        val hostKeyRejected = AtomicBoolean(false)
        client.addHostKeyVerifier(object : net.schmizz.sshj.transport.verification.HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val trusted = hostKeyVerifier.verify(
                    host = hostname,
                    port = port,
                    keyType = KeyType.fromKey(key).toString(),
                    fingerprint = opensshFingerprint(key),
                )
                if (!trusted) hostKeyRejected.set(true)
                return trusted
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        return hostKeyRejected
    }

    /** Аутентифицировать уже соединённый [client] по [auth]; при неудаче закрывает клиент и бросает. */
    private fun authenticate(client: SSHClient, target: SshTarget, auth: SshAuth) {
        try {
            when (auth) {
                is SshAuth.Password -> client.authPassword(target.username, auth.secret)
                is SshAuth.PublicKey -> {
                    // loadKeys трактует строки как содержимое ключа (не путь); passphrase —
                    // одноразовый PasswordFinder. Формат (OpenSSH/PKCS) sshj определяет сам.
                    val pwdf = auth.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                    val keys = client.loadKeys(auth.privateKeyPem, null, pwdf)
                    client.authPublickey(target.username, keys)
                }
                is SshAuth.Certificate -> {
                    // Cert-auth: владение доказываем приватным ключом из PEM, а серверу предъявляем
                    // сам сертификат (публичная часть = распарсенный *-cert.pub). sshj не склеивает
                    // их из строк сам (только из файлов по соседству), поэтому собираем KeyProvider
                    // вручную: private — из PEM, public — Certificate, type — *_CERT.
                    val pwdf = auth.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                    val keys = client.loadKeys(auth.privateKeyPem, null, pwdf)
                    client.authPublickey(target.username, certificateKeyProvider(keys, auth.certificate))
                }
            }
        } catch (e: UserAuthException) {
            client.close()
            // Без имени пользователя в тексте: сообщение не должно нести идентификатор (логи/отчёты).
            throw SshAuthenticationException("Сервер не принял учётные данные", e)
        } catch (e: IOException) {
            client.close()
            throw SshConnectionException("Обрыв соединения при аутентификации", e)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
    }
}

/** Один раз на процесс: регистрация полного BouncyCastle (см. [ensureCryptoProvider]). */
private val cryptoProviderLock = Any()

@Volatile
private var cryptoProviderReady = false

/**
 * sshj полагается на полный BouncyCastle. На Android в провайдере «BC» по умолчанию сидит урезанный
 * системный BouncyCastle (класс `com.android.org.bouncycastle…`), которому не хватает шифров и
 * обмена ключами, нужных sshj, — из-за этого `connect()` падает на этапе KEX с обычным `IOException`
 * («Не удалось подключиться к host:port»). Подменяем «BC» на полноценный провайдер из bcprov,
 * который бандлится с sshj. На desktop JVM проблемы нет — guard по наличию `android.os.Build`
 * делает функцию no-op, так что рабочее поведение desktop не меняется. Идемпотентно.
 *
 * `internal` (а не `private`): тот же урезанный системный BouncyCastle ломает не только KEX при
 * коннекте, но и разбор приватного ключа (`SSHClient.loadKeys` в [app.skerry.shared.vault.BouncyCastleSshKeyGenerator.inspect]),
 * поэтому генератор/инспектор ключей раздела Vault регистрирует полный провайдер этим же вызовом.
 *
 * Под [synchronized] (а не lock-free `compareAndSet`): флаг `cryptoProviderReady` поднимаем ТОЛЬКО
 * после фактической регистрации провайдера. Иначе второй поток (например, `inspect` из таба Vault и
 * `connect()` одновременно) увидел бы поднятый флаг и начал использовать ещё урезанный «BC» в окне
 * между взведением флага и `insertProviderAt`. Двойная проверка флага оставляет общий путь без лока.
 */
internal fun ensureCryptoProvider() {
    if (cryptoProviderReady) return
    synchronized(cryptoProviderLock) {
        if (cryptoProviderReady) return
        // Явно ставим полный bcprov-провайдер «BC» первым на ОБЕИХ платформах — единообразно,
        // не полагаясь на ленивую саморегистрацию sshj:
        // - Android: системный «BC» урезан (com.android.org.bouncycastle) — не хватает шифров/KEX,
        //   его обязательно нужно заместить полным bcprov.
        // - Desktop: подстраховка — если «BC» отсутствует или это не наш bcprov, sshj.DefaultConfig
        //   .initCipherFactories запросит шифр через несуществующий «BC» → NoSuchProviderException
        //   (cause=null) → NPE рушит SSHClient(); ставим провайдер заранее, чтобы этого не случилось.
        // В обоих случаях ставим полный провайдер первым, если текущий «BC» — не наш bcprov.
        val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (existing == null || existing.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
        cryptoProviderReady = true
    }
}

/**
 * [KeyProvider] для аутентификации по сертификату: приватный ключ берётся из уже загруженного
 * [privateKeys] (PEM), а публичная часть — распарсенный из строки [certificate] объект `Certificate`
 * (sshj-декодер `Buffer.readPublicKey` для cert-типа возвращает именно его). Тип берём из первого
 * поля строки (`ssh-…-cert-v01@openssh.com`) — это `*_CERT`, по нему sshj и шлёт серверу cert-blob.
 */
private fun certificateKeyProvider(privateKeys: KeyProvider, certificate: String): KeyProvider {
    val fields = certificate.trim().split(Regex("\\s+"))
    // Битая/обрезанная строка cert (нет второго поля, невалидный base64, мусор в wire-данных) не
    // должна вылетать необработанным IndexOutOfBounds/IllegalArgument мимо обработчиков auth —
    // конвертируем в SshAuthenticationException (предъявить учётные данные не удалось).
    val (certType, certKey) = runCatching {
        require(fields.size >= 2) { "ожидался формат '<type> <base64> [comment]'" }
        KeyType.fromString(fields[0]) to Buffer.PlainBuffer(Base64.getDecoder().decode(fields[1])).readPublicKey()
    }.getOrElse { throw SshAuthenticationException("Сохранённый SSH-сертификат не удалось разобрать", it) }
    return object : KeyProvider {
        override fun getPrivate(): PrivateKey = privateKeys.private
        override fun getPublic(): PublicKey = certKey
        override fun getType(): KeyType = certType
    }
}

/** Fingerprint в формате OpenSSH: `SHA256:` + base64 без паддинга от wire-кодировки ключа. */
private fun opensshFingerprint(key: PublicKey): String {
    val encoded = Buffer.PlainBuffer().putPublicKey(key).compactData
    val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}
