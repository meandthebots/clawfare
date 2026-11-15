package com.clawfare.search

import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant

/** Keep the actual trip-specific JSON configuration on private/local disk. */
@Serializable
data class BatchScenario(
    val id: String,
    val query: SearchQuery,
    /** All additions are whole-party GBP costs, not costs per traveller. */
    val positioningGbp: Double? = null,
    val hotelsGbp: Double? = null,
    val groundTransfersGbp: Double? = null,
    val baggageGbp: Double? = null,
)

@Serializable
data class BatchConfig(
    val name: String = "fare-batch",
    val scenarios: List<BatchScenario>,
    /** Maximum possible provider network calls, including return lookup upper bounds. */
    val maxLiveCalls: Int = 0,
)

data class BatchEntry(
    val scenario: BatchScenario,
    val query: SearchQuery,
    val signature: String,
)

data class BatchEstimate(
    val entries: List<BatchEntry>,
    val liveCalls: Int,
    val cachedCalls: Int,
    val skippedCalls: Int,
    val extraReturnCalls: Int,
    val warnings: List<String>,
) {
    val upperBound: Int get() = liveCalls + extraReturnCalls
}

data class BatchRow(
    val signature: String,
    val origin: String,
    val destination: String,
    val date: String,
    val source: String,
    val sourceLabel: String,
    val pricePerPersonGbp: Double?,
    val partyFareGbp: Double?,
    val allInGbp: Double?,
    val expenseEvidence: String,
    val retrievedAt: String?,
    val journeyType: String,
)

data class BatchRunSummary(
    val planned: BatchEstimate,
    val executed: Int,
    val saved: Int,
    val failures: List<String>,
    val observedAt: String,
)

/**
 * Progressive, bounded route/date matrix: a single saved investigation per
 * route/date pair, with its own durable price history.
 */
object BatchSweep {
    fun expand(config: BatchConfig): List<BatchEntry> =
        config.scenarios.flatMap { scenario ->
            val normalized = normalizeQuery(scenario.query)
            require(scenario.id.matches(Regex("[a-zA-Z][a-zA-Z0-9-]{0,35}"))) { "Invalid scenario identifier" }
            val pairs = datePairs(normalized)
            require(pairs.size * normalized.origins.size * normalized.destinations.size <= 1500) {
                "Scenario matrix too large; split searches into batches"
            }
            pairs.flatMap { (departure, returning) ->
                normalized.origins.flatMap { origin ->
                    normalized.destinations.map { destination ->
                        val key = listOf(scenario.id, origin, destination, departure, returning.orEmpty()).joinToString("-")
                        val slug = (config.name + "-" + key).lowercase().replace(Regex("[^a-z0-9-]"), "-").take(120)
                        val q =
                            normalized.copy(
                                origins = listOf(origin),
                                destinations = listOf(destination),
                                outboundFrom = departure,
                                outboundTo = departure,
                                returnFrom = returning,
                                returnTo = returning,
                                investigation = slug,
                            )
                        BatchEntry(scenario, q, slug)
                    }
                }
            }
        }

    fun estimate(
        config: BatchConfig,
        service: SearchService,
    ): BatchEstimate {
        val entries = expand(config)
        require(entries.map { it.signature }.distinct().size == entries.size) { "Duplicate route/date investigation key" }
        val estimates = entries.map { entry -> service.estimate(entry.query) }
        return BatchEstimate(
            entries,
            estimates.sumOf { it.liveCalls },
            estimates.sumOf { it.cachedCalls },
            estimates.sumOf { it.skippedCalls },
            estimates.sumOf { it.returnLookupsUpperBound },
            estimates.flatMap { it.warnings }.distinct(),
        )
    }

    fun run(
        config: BatchConfig,
        service: SearchService,
        confirmed: Boolean = false,
        auditFile: File? = null,
    ): BatchRunSummary {
        val planned = estimate(config, service)
        require(config.maxLiveCalls >= 0) { "maxLiveCalls must not be negative" }
        require(planned.upperBound <= config.maxLiveCalls) {
            "Batch upper bound ${planned.upperBound} exceeds hard network cap ${config.maxLiveCalls}; no calls issued."
        }
        require(confirmed || planned.upperBound == 0) { "No network calls without explicit --confirm." }

        val failures = mutableListOf<String>()
        var saved = 0
        var reserved = 0
        var executed = 0
        for (entry in planned.entries) {
            val remaining = config.maxLiveCalls - reserved
            val estimate = service.estimate(entry.query)
            val upper = estimate.liveCalls + estimate.returnLookupsUpperBound
            if (upper > remaining) {
                failures += "${entry.signature}: remaining quota cap exceeded"
                break
            }
            val response = service.search(entry.query.copy(confirmed = confirmed, maxLiveCalls = remaining))
            executed++
            if (response.ok) saved++ else failures += "${entry.signature}: ${response.error ?: "unknown search error"}"
            // Append every attempted scenario, including zero-result and failed searches.
            auditFile?.let { target ->
                target.parentFile?.mkdirs()
                target.appendText(
                    "${Instant.now()}\t${entry.signature}\t${if (response.ok) "ok" else "error"}\t$upper\t" +
                        "${response.result?.itineraries?.size ?: 0}\n",
                )
            }
            reserved += upper
        }
        return BatchRunSummary(planned, executed, saved, failures, Instant.now().toString())
    }

    /**
     * Unknown positioning, hotel, transfer or bag costs remain unknown.
     * Cached/mock tickets cannot be promoted to a verified all-in quote.
     */
    fun compare(
        config: BatchConfig,
        sink: FareSink,
    ): List<BatchRow> {
        val entries = expand(config)
        val rows =
            entries.flatMap { entry ->
            val result = sink.load(entry.signature)?.result ?: return@flatMap emptyList()
            result.itineraries.map { itinerary ->
                val fare = itinerary.pricePerPerson?.let { it * itinerary.passengers }
                val scenario = entry.scenario
                val knownCosts =
                    listOf(scenario.positioningGbp, scenario.hotelsGbp, scenario.groundTransfersGbp, scenario.baggageGbp)
                val complete = knownCosts.all { it != null }
                val finalPrice =
                    if (fare != null && complete && itinerary.sourceLabel == "live") {
                        fare + knownCosts.filterNotNull().sum()
                    } else {
                        null
                    }
                BatchRow(
                    signature = entry.signature,
                    origin = itinerary.origin,
                    destination = itinerary.destination,
                    date = itinerary.outboundDate,
                    source = itinerary.source,
                    sourceLabel = itinerary.sourceLabel,
                    pricePerPersonGbp = itinerary.pricePerPerson,
                    partyFareGbp = fare,
                    allInGbp = finalPrice,
                    expenseEvidence = if (complete) "User-supplied whole-party costs" else "Incomplete positioning or bag costs",
                    retrievedAt = itinerary.fetchedAt,
                    journeyType = if (itinerary.returning == null) "one_way" else "round_trip",
                )
            }
            }
        return rows.sortedWith(compareBy({ it.allInGbp == null }, { it.allInGbp ?: Double.MAX_VALUE }, { it.date }))
    }
}
