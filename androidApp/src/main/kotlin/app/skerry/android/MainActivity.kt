package app.skerry.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import app.skerry.shared.host.FileHostStore
import app.skerry.shared.ssh.FileHostKeyMismatchStore
import app.skerry.shared.ssh.FileKnownHostsStore
import app.skerry.shared.ssh.ProbeHostKeyVerifier
import app.skerry.shared.ssh.SshjTransport
import app.skerry.shared.ssh.TofuHostKeyVerifier
import app.skerry.shared.tunnel.FileTunnelStore
import app.skerry.shared.vault.AndroidBiometricKeyStore
import app.skerry.shared.vault.BouncyCastleSshKeyGenerator
import app.skerry.shared.vault.FileBioArtifactStore
import app.skerry.shared.vault.CredentialStore
import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.SshjCertificateInspector
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.ui.AppDependencies
import app.skerry.ui.design.MobileDesignApp
import app.skerry.ui.sftp.SafBridge
import app.skerry.ui.vault.AndroidLockContext
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.tunnel.TunnelManager
import app.skerry.ui.tunnel.resolveTunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import java.lang.ref.WeakReference
import java.time.Instant
import java.util.UUID

/**
 * Точка входа Android. [FragmentActivity] (а не `ComponentActivity`) обязателен для
 * `androidx.biometric.BiometricPrompt`. Граф зависимостей строится здесь по образцу desktop
 * `main.kt`: локальный зашифрованный vault в приватном `filesDir`, та же кросс-платформенная
 * крипта (ionspin) и okio-стор. SSH-транспорт на Android пока не подключён (паритет в работе),
 * поэтому за гейтом — настройки vault (биометрия + lock) до прихода полноценного мобильного UI.
 */
class MainActivity : FragmentActivity() {
    // Scope менеджера туннелей: живёт на время Activity. Отменяется в onDestroy, чтобы при
    // пересоздании (поворот и т.п.) старый scope с поллингом не оставался орфаном. Активные туннели
    // при этом сбрасываются — приемлемо для текущего этапа (полное сохранение поверх пересоздания —
    // отдельная задача через retained-холдер/ViewModel).
    private var tunnelScope: CoroutineScope? = null

    override fun onDestroy() {
        tunnelScope?.cancel()
        tunnelScope = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Контекст для проверки keyguard: авто-лок при уходе в фон должен срабатывать только при
        // реально заблокированном устройстве, а не при открытии системного пикера (см. deviceMandatesAutoLock).
        AndroidLockContext.appContext = applicationContext

        // SAF-пикеры SFTP: launcher'ы регистрируем в onCreate (требование ActivityResult API — до
        // STARTED) и отдаём в SafBridge как лямбды запуска, чтобы общий UI-код не зависел от Activity.
        // octet-stream — нейтральный MIME для произвольного бинарного скачивания; text/plain — для
        // экспорта ключа/сертификата .pub (верная иконка/обработчик в файловом менеджере); "*/*" — любой upload.
        val createDocument = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri -> SafBridge.onCreateResult(uri) }
        val createTextDocument = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri -> SafBridge.onCreateResult(uri) }
        val openDocument = registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri -> SafBridge.onOpenResult(uri) }
        SafBridge.install(
            applicationContext,
            launchCreate = { name -> createDocument.launch(name) },
            launchCreateText = { name -> createTextDocument.launch(name) },
            launchOpen = { openDocument.launch(arrayOf("*/*")) },
        )

        // libsodium (ionspin) требует асинхронной инициализации до первого вызова VaultCrypto;
        // на старте делаем это блокирующе, как desktop, чтобы граф строился уже готовым.
        runBlocking { initializeVaultCrypto() }

        val deps = buildDependencies()
        setContent { MobileDesignApp(deps) }
    }

    private fun buildDependencies(): AppDependencies {
        val dir = filesDir // приватный каталог приложения
        val crypto = IonspinVaultCrypto()
        val vault = FileVault(
            dir.resolve("vault.json").absolutePath.toPath(),
            crypto,
            deviceId(dir),
            FileSystem.SYSTEM,
        ) { System.currentTimeMillis().toString() }
        val credentials = CredentialManagerController(CredentialStore(vault)) { UUID.randomUUID().toString() }
        // SSH-транспорт (sshj, общий JVM source set). TOFU: первый ключ хоста запоминается в
        // known_hosts (с отметкой времени), при смене ключа — отказ + запись события в
        // known_hosts_mismatches, чтобы менеджер known-hosts мог показать предупреждение и дать
        // принять/отклонить новый ключ. Менеджер хостов: профили в hosts.json рядом.
        val knownHostsStore = FileKnownHostsStore(dir.resolve("known_hosts").toPath())
        val mismatchStore = FileHostKeyMismatchStore(dir.resolve("known_hosts_mismatches").toPath())
        val transport = SshjTransport(
            TofuHostKeyVerifier(knownHostsStore, mismatchStore) { Instant.now().toString() },
        )
        val knownHosts = KnownHostsController(knownHostsStore, mismatchStore) { Instant.now().toString() }
        val hosts = HostManagerController(FileHostStore(dir.resolve("hosts.json").toPath())) {
            UUID.randomUUID().toString()
        }
        // Биометрия: ключ в AndroidKeyStore, промпт хостит эта Activity. Слабая ссылка — стор не
        // удерживает Activity и при пересоздании отдаёт null, а не уничтоженную (промпт тогда NoActivity).
        val activityRef = WeakReference(this)
        val biometrics = VaultBiometrics(
            vault = vault,
            keyStore = AndroidBiometricKeyStore(applicationContext) { activityRef.get() },
            artifacts = FileBioArtifactStore(dir.resolve("vault.bio").absolutePath.toPath(), FileSystem.SYSTEM),
            deviceId = deviceId(dir),
        )
        // Глобальные туннели (привычная модель SSH-клиентов): сохранённые пробросы в tunnels.json. Активация — через
        // ОТДЕЛЬНЫЙ probe-транспорт (read-only verifier): включить можно только уже доверенный хост,
        // тихого TOFU тут быть не должно. Резолв хоста/секрета — через граф (hosts + credentials).
        val tunnelTransport = SshjTransport(ProbeHostKeyVerifier(knownHostsStore))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { tunnelScope = it }
        val tunnels = TunnelManager(
            store = FileTunnelStore(dir.resolve("tunnels.json").toPath()),
            transport = tunnelTransport,
            resolve = { resolveTunnel(it, findHost = hosts::find, findCredential = credentials::find) },
            scope = scope,
        ) { UUID.randomUUID().toString() }
        return AppDependencies(
            transport = transport,
            hosts = hosts,
            vault = vault,
            credentials = credentials,
            knownHosts = knownHosts,
            // Инспектор/генератор SSH-ключей (BouncyCastle, общий JVM source set) — отпечатки/генерация в табе Vault.
            keyGenerator = BouncyCastleSshKeyGenerator(),
            // Инспектор SSH-сертификатов (sshj) — раздел Vault → Certificates: разбор *-cert.pub.
            certificateInspector = SshjCertificateInspector(),
            biometrics = biometrics,
            tunnels = tunnels,
        )
    }

    /** Стабильный идентификатор устройства для записей vault (provenance + LWW будущего sync). */
    private fun deviceId(dir: File): String {
        val file = File(dir, "device_id")
        if (file.exists()) return file.readText().trim()
        val id = UUID.randomUUID().toString()
        file.writeText(id)
        return id
    }
}
