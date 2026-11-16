package com.clawfare.search

import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant

@Serializable
data class WatchAlert(
    val observedAt: String,
    val investigation: String,
    val origin: String,
    val destination: String,
    val outboundDate: String,
    val oldAllInGbp: Double? = null,
    val newAllInGbp: Double,
    val changeGbp: Double? = null,
    val reason: String,
    /** Hash of exact fare product, cost assumptions, amount and event kind; no tokens or itinerary leaked in IDs. */
    val alertId: String = "",
    /** Never promoted to verified refundable on the basis of price alone. */
    val verification: String = "fare_and_cash_refund_unverified",
)

@Serializable
data class DailyBudgetReservation(
    val createdAt: String,
    val utcDay: String,
    val maxNetworkCalls: Int,
    /** Hash only; actual itinerary and dates remain in the private search config. */
    val configHash: String,
)

data class WatchOutcome(
    val batch: BatchRunSummary,
    val alerts: List<WatchAlert>,
    val suppressedFxOnlyChanges: Int = 0,
    val suppressedExpenseOnlyChanges: Int = 0,
)

/**
 * One bounded monitoring cycle for an external scheduler. Never starts a
 * background daemon, books tickets, sends email or invents availability.
 */
object FareWatch {
    fun runOnce(
        config: BatchConfig,
        service: SearchService,
        sink: FareSink,
        lockFile: File,
        auditFile: File,
        alertFile: File,
        thresholdGbp: Double,
        confirmed: Boolean,
    ): WatchOutcome {
        require(confirmed) { "Watch mode needs explicit --confirm for this cycle." }
        require(thresholdGbp > 0.0) { "Price-drop threshold must be positive." }
        lockFile.parentFile?.mkdirs()
        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = channel.tryLock() ?: error("Another collection cycle is already running for this file.")
            lock.use {
                // Reserve the worst-case call count durably BEFORE starting any paid
                // flight requests. Lost/crashed cycles consume budget conservatively.
                val planned = BatchSweep.estimate(config, service)
                val dailyLimit = config.maxDailyLiveCalls ?: config.maxLiveCalls
                require(dailyLimit >= 0) { "Daily call budget must not be negative." }
                val ledger = File(auditFile.parentFile, "batch-budget.ndjson")
                val today = Instant.now().toString().take(10)
                val reservations =
                    if (ledger.isFile) {
                        ledger.readLines().filter { it.isNotBlank() }.map { line ->
                            AppJson.decodeFromString(DailyBudgetReservation.serializer(), line)
                        }
                    } else {
                        emptyList()
                    }
                val used = reservations.filter { it.utcDay == today }.sumOf { it.maxNetworkCalls.toLong() }
                val requested = planned.upperBound.toLong()
                require(used + requested <= dailyLimit.toLong()) {
                    "Daily call budget exceeded: $used reserved, $requested requested, $dailyLimit allowed."
                }
                if (requested > 0L) {
                    val record =
                        DailyBudgetReservation(
                            createdAt = Instant.now().toString(),
                            utcDay = today,
                            maxNetworkCalls = planned.upperBound,
                            configHash = sha256(AppJson.encodeToString(BatchConfig.serializer(), config)),
                        )
                    ledger.parentFile?.mkdirs()
                    FileOutputStream(ledger, true).use { output ->
                        val line = AppJson.encodeToString(DailyBudgetReservation.serializer(), record) + System.lineSeparator()
                        output.write(line.toByteArray(Charsets.UTF_8))
                        output.fd.sync()
                    }
                }

                val previous = BatchSweep.compare(config, sink)
                val previousByProduct =
                    previous
                        .filter { it.allInGbp != null }
                        .associateBy { it.productKey }
                val summary = BatchSweep.run(config, service, confirmed = true, auditFile = auditFile)
                val current = BatchSweep.compare(config, sink)
                val observed = Instant.now()
                var fxOnlyChanges = 0
                var expenseOnlyChanges = 0
                val candidates =
                    current.filter { row ->
                        row.allInGbp != null &&
                            row.sourceLabel == "live" &&
                            (row.offerExpiresAt == null ||
                                runCatching { Instant.parse(row.offerExpiresAt).isAfter(observed) }.getOrDefault(false)) &&
                            row.retrievedAt?.let { stamp ->
                                runCatching {
                                    val age = Duration.between(Instant.parse(stamp), observed)
                                    !age.isNegative && age <= Duration.ofHours(2)
                                }.getOrDefault(false)
                            } == true
                    }.mapNotNull { row ->
                        val before = previousByProduct[row.productKey]
                        if (before != null && before.expensesKey != row.expensesKey) {
                            expenseOnlyChanges++
                            return@mapNotNull null
                        }
                        if (before != null &&
                            before.priceCurrency == row.priceCurrency &&
                            row.priceCurrency != "GBP" &&
                            before.sourcePricePerPerson != null &&
                            before.sourcePricePerPerson == row.sourcePricePerPerson
                        ) {
                            if (before.allInGbp != row.allInGbp) fxOnlyChanges++
                            return@mapNotNull null
                        }
                        if (before != null && before.priceCurrency != row.priceCurrency) return@mapNotNull null
                        val beforeTotal = before?.allInGbp
                        val newTotal = row.allInGbp!!
                        val drop = beforeTotal?.minus(newTotal)
                        if (beforeTotal != null && (drop == null || drop < thresholdGbp)) return@mapNotNull null
                        val reason = if (beforeTotal == null) "new_quote" else "price_drop"
                        val alertId =
                            sha256(
                                listOf(
                                    row.productKey,
                                    row.expensesKey,
                                    row.priceCurrency,
                                    row.sourcePricePerPerson.toString(),
                                    newTotal.toString(),
                                    reason,
                                ).joinToString("|"),
                            )
                        WatchAlert(
                            observedAt = observed.toString(),
                            investigation = row.signature,
                            origin = row.origin,
                            destination = row.destination,
                            outboundDate = row.date,
                            oldAllInGbp = beforeTotal,
                            newAllInGbp = newTotal,
                            changeGbp = drop,
                            reason = reason,
                            alertId = alertId,
                        )
                    }
                // Read the entire durable journal before appending anything; a
                // partially written/corrupt journal must not cause repeat alerts.
                val existing =
                    if (alertFile.isFile) {
                        alertFile.readLines().filter { it.isNotBlank() }.map { line ->
                            AppJson.decodeFromString(WatchAlert.serializer(), line)
                        }
                    } else {
                        emptyList()
                    }
                val sentIds = existing.map { it.alertId }.filter { it.isNotBlank() }.toSet()
                val alerts = candidates.distinctBy { it.alertId }.filterNot { it.alertId in sentIds }
                if (alerts.isNotEmpty()) {
                    alertFile.parentFile?.mkdirs()
                    FileOutputStream(alertFile, true).use { output ->
                        for (alert in alerts) {
                            val line = AppJson.encodeToString(WatchAlert.serializer(), alert) + System.lineSeparator()
                            output.write(line.toByteArray(Charsets.UTF_8))
                        }
                        output.fd.sync()
                    }
                }
                return WatchOutcome(summary, alerts, fxOnlyChanges, expenseOnlyChanges)
            }
        }
    }
}
