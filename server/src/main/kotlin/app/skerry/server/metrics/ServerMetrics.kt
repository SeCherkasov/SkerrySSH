package app.skerry.server.metrics

import app.skerry.server.config.MetricsExposure
import app.skerry.server.config.ServerConfig
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.binder.MeterBinder
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Prometheus metrics for one server instance. Deliberate boundaries:
 *
 * - **No unbounded labels.** Every label value comes from an enum or a fixed route allow-list, never
 *   from client input: `accountId`, `deviceId`, `recordId`, `teamId`, `scopeId` and IPs are all
 *   client-chosen, so a label carrying one would let a user grow the registry until it OOMs — and on
 *   a zero-knowledge server it would also publish the very metadata the design protects.
 * - **Nothing expensive at scrape time.** Inventory gauges read a snapshot refreshed in the
 *   background ([InventoryCollector]); the SQLite pool is a single connection.
 * - **Per instance, not a singleton.** The tests start `configureServer` many times in one JVM, so a
 *   global registry would accumulate duplicate meters and leak the GC notification listener.
 */
class ServerMetrics(
    private val config: ServerConfig,
    val registry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
    version: String = "unknown",
    startTimeMillis: Long = System.currentTimeMillis(),
) : Closeable {

    // AutoCloseable, not Closeable: that is what Micrometer's JvmGcMetrics implements.
    private val binders = mutableListOf<AutoCloseable>()

    private val wsSessions = AtomicInteger()
    /** Null until the first successful collection: an absent number must not read as zero. */
    @Volatile
    private var inventory: InventorySnapshot? = null
    private val inventoryLastSuccessSeconds = AtomicLong(0)
    /** NaN until the first probe: "not measured yet" is not the same fact as "down". */
    private val dbUp = AtomicReference(Double.NaN)

    init {
        // The HTTP timer's own tags are enough; `address` is constant per instance and `throwable`
        // duplicates skerry_http_unhandled_exceptions_total.
        registry.config().meterFilter(MeterFilter.ignoreTags("address", "throwable"))
        registry.config().meterFilter(HistogramBuckets)

        Gauge.builder("skerry.build.info") { 1.0 }
            .description("Server build information")
            .tag("version", version)
            .register(registry)
        Gauge.builder("skerry.server.start.time") { startTimeMillis / 1000.0 }
            .description("Process start, unix epoch seconds")
            .baseUnit("seconds")
            .register(registry)

        Gauge.builder("skerry.sync.ws.sessions") { wsSessions.get().toDouble() }
            .description("Open /sync WebSocket sessions")
            .register(registry)

        registerInventoryGauges()

        // JVM/process instrumentation is only worth its listeners when someone can actually scrape.
        if (config.metrics != MetricsExposure.OFF) bindRuntimeMetrics()
    }

    // --- HTTP ---

    fun requestRejected(reason: RejectReason) = count("skerry.http.rejected.requests", "reason" to reason.label)

    fun unhandledException() = count("skerry.http.unhandled.exceptions")

    // --- auth / security ---

    fun authAttempt(kind: AuthKind, outcome: AuthOutcome) =
        count("skerry.auth.attempts", "kind" to kind.label, "outcome" to outcome.label)

    fun tokensIssued(type: TokenType) = count("skerry.auth.tokens.issued", "type" to type.label)

    fun jwtRejected(reason: JwtRejection) = count("skerry.auth.jwt.rejected", "reason" to reason.label)

    fun adminAuthFailure() = count("skerry.admin.auth.failures")

    fun metricsAuthFailure() = count("skerry.metrics.auth.failures")

    fun registrationRejected(reason: RegistrationRejection) =
        count("skerry.registration.rejected", "reason" to reason.label)

    fun deviceRevoked(by: RevokedBy) = count("skerry.devices.revoked", "by" to by.label)

    fun teamAuthzDenied(reason: TeamDenial) = count("skerry.team.authz.denied", "reason" to reason.label)

    // --- sync ---

    fun recordsReceived(scope: SyncScope, records: Int, bytes: Long) {
        count("skerry.sync.records.received", "scope" to scope.label, amount = records.toDouble())
        count("skerry.sync.push.bytes", "scope" to scope.label, amount = bytes.toDouble())
    }

    fun recordsPulled(scope: SyncScope, records: Int) =
        count("skerry.sync.records.pulled", "scope" to scope.label, amount = records.toDouble())

    fun notificationPublished(kind: NotifyKind) = count("skerry.sync.notify.published", "kind" to kind.label)

    fun wsSessionOpened() {
        wsSessions.incrementAndGet()
        count("skerry.sync.ws.sessions.opened")
    }

    fun wsSessionClosed(reason: WsCloseReason, durationSeconds: Double) {
        wsSessions.decrementAndGet()
        count("skerry.sync.ws.sessions.closed", "reason" to reason.label)
        registry.timer("skerry.sync.ws.session.duration")
            .record((durationSeconds * 1_000).toLong(), TimeUnit.MILLISECONDS)
    }

    fun wsFrameSent(kind: NotifyKind) = count("skerry.sync.ws.frames.sent", "kind" to kind.label)

    // --- database probe (fed by DbProbe) ---

    fun dbProbe(up: Boolean, durationSeconds: Double) {
        dbUp.set(if (up) 1.0 else 0.0)
        registry.timer("skerry.db.probe.duration")
            .record((durationSeconds * 1_000).toLong(), TimeUnit.MILLISECONDS)
    }

    // --- inventory ---

    fun inventoryCollected(snapshot: InventorySnapshot, atMillis: Long = System.currentTimeMillis()) {
        inventory = snapshot
        inventoryLastSuccessSeconds.set(atMillis / 1000)
    }

    /**
     * A failed collection deliberately leaves the previous snapshot in place *and* leaves the
     * freshness timestamp behind: alerting on `time() - …last_success_seconds` is what turns a stale
     * gauge into a visible fault instead of a silent lie.
     */
    fun inventoryFailed() = count("skerry.inventory.errors")

    /** Unix seconds of the last successful inventory collection, or null if there has never been one. */
    val inventoryLastSuccessSecondsOrNull: Long?
        get() = inventoryLastSuccessSeconds.get().takeIf { it > 0 }

    fun scrape(): String = registry.scrape()

    override fun close() {
        binders.forEach { runCatching { it.close() } }
        binders.clear()
        registry.close()
    }

    private fun bindRuntimeMetrics() {
        val gc = JvmGcMetrics()
        binders += gc
        listOf<MeterBinder>(
            ClassLoaderMetrics(),
            JvmMemoryMetrics(),
            gc,
            JvmThreadMetrics(),
            ProcessorMetrics(),
            UptimeMetrics(),
            FileDescriptorMetrics(),
        ).forEach { it.bindTo(registry) }
    }

    private fun registerInventoryGauges() {
        Gauge.builder("skerry.db.up") { dbUp.get() }
            .description("1 when the database answered the last probe, 0 when it did not")
            .register(registry)
        Gauge.builder("skerry.inventory.last.success.time") { inventoryLastSuccessSeconds.get().toDouble() }
            .description("Last successful inventory collection, unix epoch seconds (0 = never)")
            .baseUnit("seconds")
            .register(registry)

        fun inventoryGauge(name: String, description: String, unit: String? = null, tags: Tags = Tags.empty(), value: (InventorySnapshot) -> Number) {
            Gauge.builder(name) { inventory?.let { value(it).toDouble() } ?: Double.NaN }
                .description(description)
                .tags(tags)
                .apply { if (unit != null) baseUnit(unit) }
                .register(registry)
        }

        inventoryGauge("skerry.accounts", "Accounts on the instance") { it.accounts }
        inventoryGauge("skerry.devices", "Devices", tags = Tags.of("state", "active")) { it.activeDevices }
        inventoryGauge("skerry.devices", "Devices", tags = Tags.of("state", "revoked")) { it.revokedDevices }
        inventoryGauge("skerry.records", "Vault records", tags = Tags.of("state", "live")) { it.liveRecords }
        inventoryGauge("skerry.records", "Vault records", tags = Tags.of("state", "tombstone")) { it.tombstones }
        inventoryGauge("skerry.team.records", "Team records", tags = Tags.of("state", "live")) { it.liveTeamRecords }
        inventoryGauge("skerry.team.records", "Team records", tags = Tags.of("state", "tombstone")) { it.teamTombstones }
        inventoryGauge("skerry.storage.bytes", "Ciphertext stored", "bytes", Tags.of("scope", "account")) { it.storageBytes }
        inventoryGauge("skerry.storage.bytes", "Ciphertext stored", "bytes", Tags.of("scope", "team")) { it.teamStorageBytes }
        inventoryGauge("skerry.db.file.bytes", "Database size on disk (includes indexes and free pages)", "bytes") { it.databaseBytes }
        inventoryGauge("skerry.pairing.sessions", "Pairing sessions", tags = Tags.of("state", "pending")) { it.pendingPairings }
        inventoryGauge("skerry.pairing.sessions", "Pairing sessions", tags = Tags.of("state", "expired")) { it.expiredPairings }
        inventoryGauge("skerry.teams", "Teams") { it.teams }
        inventoryGauge("skerry.team.members", "Team members", tags = Tags.of("status", "active")) { it.activeMembers }
        inventoryGauge("skerry.team.members", "Team members", tags = Tags.of("status", "invited")) { it.invitedMembers }
        inventoryGauge("skerry.activity.log.rows", "Retained audit-log rows") { it.activityRows }
        // Config, not inventory: it is known from the start, so it must not wait for a collection.
        Gauge.builder("skerry.registration.open") { if (config.registrationOpen) 1.0 else 0.0 }
            .description("1 when POST /auth/register accepts new accounts")
            .register(registry)
    }

    private fun count(name: String, vararg tags: Pair<String, String>, amount: Double = 1.0) {
        val labels = tags.fold(Tags.empty()) { acc, (key, value) -> acc.and(key, value) }
        registry.counter(name, labels).increment(amount)
    }

    private companion object {
        /**
         * Explicit SLO buckets instead of Micrometer's percentile histogram: the default would emit
         * ~70 buckets per series (thousands of series for a handful of routes). Boundaries chosen
         * against this server's own shape — 5ms is "answered without touching the database", 250ms
         * is the top of normal for a record push, 5s already looks like a blocked SQLite connection.
         */
        val SLO_SECONDS = doubleArrayOf(0.005, 0.025, 0.1, 0.25, 1.0, 5.0)
    }

    /** Applies [SLO_SECONDS] to the timers we own; Micrometer wants service-level objectives in nanoseconds. */
    private object HistogramBuckets : MeterFilter {
        private val timerNames = setOf(HTTP_REQUESTS_METRIC, "skerry.db.probe.duration")

        override fun configure(id: Meter.Id, config: DistributionStatisticConfig): DistributionStatisticConfig =
            when (id.name) {
                in timerNames -> DistributionStatisticConfig.builder()
                    .percentilesHistogram(false)
                    .serviceLevelObjectives(*SLO_SECONDS.map { it * 1_000_000_000 }.toDoubleArray())
                    .build()
                    .merge(config)
                "skerry.sync.ws.session.duration" -> DistributionStatisticConfig.builder()
                    .percentilesHistogram(false)
                    .serviceLevelObjectives(
                        *doubleArrayOf(1.0, 10.0, 60.0, 300.0, 1_800.0, 7_200.0)
                            .map { it * 1_000_000_000 }.toDoubleArray(),
                    )
                    .build()
                    .merge(config)
                else -> config
            }
    }
}

/** Name of the HTTP server timer; also referenced by the route-label allow-list test. */
const val HTTP_REQUESTS_METRIC = "skerry.http.server.requests"

/** One background snapshot of instance inventory — everything that would need a table scan. */
data class InventorySnapshot(
    val accounts: Long,
    val activeDevices: Long,
    val revokedDevices: Long,
    val liveRecords: Long,
    val tombstones: Long,
    val storageBytes: Long,
    val liveTeamRecords: Long,
    val teamTombstones: Long,
    val teamStorageBytes: Long,
    val pendingPairings: Long,
    val expiredPairings: Long,
    val teams: Long,
    val activeMembers: Long,
    val invitedMembers: Long,
    val activityRows: Long,
    val databaseBytes: Long,
)

enum class AuthKind(val label: String) {
    REGISTER("register"),
    SRP_CHALLENGE("srp_challenge"),
    SRP_VERIFY("srp_verify"),
    REFRESH("refresh"),
    CHANGE_PASSWORD("change_password"),
    PAIRING_CLAIM("pairing_claim"),
}

enum class AuthOutcome(val label: String) { OK("ok"), DENIED("denied"), ERROR("error") }

enum class TokenType(val label: String) { ACCESS("access"), REFRESH("refresh") }

enum class JwtRejection(val label: String) {
    WRONG_TYPE("wrong_type"),
    DEVICE_REVOKED("device_revoked"),
    MISSING_CLAIMS("missing_claims"),
}

enum class RegistrationRejection(val label: String) { CLOSED("closed"), CAP_REACHED("cap_reached") }

enum class RevokedBy(val label: String) { USER("user"), ADMIN("admin") }

enum class TeamDenial(val label: String) {
    NOT_MEMBER("not_member"),
    NOT_ACCEPTED("not_accepted"),
    ROLE("role"),
    SCOPE("scope"),
}

enum class SyncScope(val label: String) { ACCOUNT("account"), TEAM("team") }

enum class NotifyKind(val label: String) { ACCOUNT("account"), TEAM("team"), MEMBERSHIP("membership") }

enum class WsCloseReason(val label: String) {
    CLIENT_CLOSE("client_close"),
    DEVICE_REVOKED("device_revoked"),
    NO_PRINCIPAL("no_principal"),
    ERROR("error"),
}

enum class RejectReason(val label: String) { LENGTH_REQUIRED("length_required"), BODY_TOO_LARGE("body_too_large") }
