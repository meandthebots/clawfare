package com.clawfare.search

import kotlinx.serialization.Serializable
import java.io.File
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
    /** Never promoted to verified refundable on the basis of price alone. */
    val verification: String = "fare_and_cash_refund_unverified",
)

data class WatchOutcome(
    val batch: BatchRunSummary,
    val alerts: List<WatchAlert>,
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
                val previous = BatchSweep.compare(config, sink)
                val previousPrices =
                    previous
                        .filter { it.allInGbp != null }
                        .groupBy { it.signature + "/" + it.source + "/" + it.date }
                        .mapValues { (_, items) -> items.minOf { it.allInGbp!! } }
                val summary = BatchSweep.run(config, service, confirmed = true, auditFile = auditFile)
                val current = BatchSweep.compare(config, sink)
                val observed = Instant.now()
                val alerts =
                    current
                        .filter { row ->
                            row.allInGbp != null &&
                                row.sourceLabel == "live" &&
                                row.retrievedAt?.let { stamp ->
                                    runCatching {
                                        val age = Duration.between(Instant.parse(stamp), observed)
                                        !age.isNegative && age <= Duration.ofHours(2)
                                    }.getOrDefault(false)
                                } == true
                        }.mapNotNull { row ->
                            val before = previousPrices[row.signature + "/" + row.source + "/" + row.date]
                            val newPrice = row.allInGbp!!
                            val drop = before?.minus(newPrice)
                            if (before != null && (drop == null || drop < thresholdGbp)) {
                                null
                            } else {
                                WatchAlert(
                                    observedAt = observed.toString(),
                                    investigation = row.signature,
                                    origin = row.origin,
                                    destination = row.destination,
                                    outboundDate = row.date,
                                    oldAllInGbp = before,
                                    newAllInGbp = newPrice,
                                    changeGbp = drop,
                                    reason = if (before == null) "new_quote" else "price_drop",
                                )
                            }
                        }
                if (alerts.isNotEmpty()) {
                    alertFile.parentFile?.mkdirs()
                    alerts.forEach { alert ->
                        alertFile.appendText(AppJson.encodeToString(WatchAlert.serializer(), alert) + "\n")
                    }
                }
                return WatchOutcome(summary, alerts)
            }
        }
    }
}
