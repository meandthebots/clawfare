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
    /** Watch-only UTC-day cap. Defaults to maxLiveCalls (one full allocation per day). */
    val maxDailyLiveCalls: Int? = null,
    /** Reserve this much remaining quota at each reporting provider. */
    val minQuotaReserve: Int = 0,
    /** Reject execution if a configured provider cannot report numeric quota. */
    val requireNumericQuota: Boolean = false,
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
    /** Stable within a query across offer-price and FX changes; deliberately excludes transient provider IDs. */
    val productKey: String = "",
    /** Expense assumptions are compared separately so cost-input changes do not trigger airfare alerts. */
    val expensesKey: String = "",
    val priceCurrency: String = "GBP",
    val sourcePricePerPerson: Double? = null,
    val offerExpiresAt: String? = null,
)

@Serializable
data class BatchAttemptRecord(
    val observedAt: String,
    val querySignatureSha256: String,
    val investigation: String,
    val providerIds: List<String>,
    val status: String,
    val maxNetworkCalls: Int,
    val offerCount: Int,
    val warnings: List<String>,
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
                        val safeKey = (config.name + "-" + key).lowercase()
                        val legalKey = safeKey.replace(Regex("[^a-z0-9-]"), "-")
                        val slug = legalKey.take(120).trim('-')
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

        if (confirmed && planned.upperBound > 0) {
            val requested = config.scenarios.flatMap { it.query.providers }.toSet()
            val statuses = service.providerQuotas(requested)
            for (quota in statuses.filter { it.configured }) {
                val estimated =
                    planned.entries.sumOf { entry ->
                        if (quota.provider !in entry.query.providers) {
                            0
                        } else {
                            val q = service.estimate(entry.query.copy(providers = listOf(quota.provider)))
                            q.liveCalls + q.returnLookupsUpperBound
                        }
                    }
                if (estimated == 0) continue
                require(!config.requireNumericQuota || quota.remaining != null) {
                    "Cannot verify remaining quota for ${quota.provider}; no calls issued."
                }
                require(quota.remaining == null || quota.remaining >= estimated + config.minQuotaReserve) {
                    "${quota.provider} quota insufficient: ${quota.remaining} remaining, need $estimated plus reserve."
                }
            }
        }

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
                val record =
                    BatchAttemptRecord(
                        observedAt = Instant.now().toString(),
                        querySignatureSha256 = sha256(AppJson.encodeToString(SearchQuery.serializer(), entry.query)),
                        investigation = entry.signature,
                        providerIds = entry.query.providers,
                        status = if (response.ok) "ok" else "error",
                        maxNetworkCalls = upper,
                        offerCount = response.result?.itineraries?.size ?: 0,
                        warnings =
                            response.result
                                ?.warnings
                                .orEmpty()
                                .take(8),
                    )
                target.appendText(AppJson.encodeToString(BatchAttemptRecord.serializer(), record) + "\n")
            }
            reserved += upper
            val warnings = response.result?.warnings.orEmpty()
            val quotaHit =
                warnings.any { warning ->
                    warning.contains("out of credits", true) ||
                        warning.contains("rate limit", true) ||
                        warning.contains("429", true)
                }
            if (quotaHit) {
                failures += "${entry.signature}: provider quota/rate limit; stopping batch early."
                break
            }
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
                    val legs = itinerary.outbound.legs + itinerary.returning?.legs.orEmpty()
                    val identity =
                        listOf(
                            entry.signature,
                            itinerary.source,
                            itinerary.fareBrand.orEmpty(),
                            itinerary.farePolicyKey.orEmpty(),
                            itinerary.cabinKey,
                            itinerary.passengers.toString(),
                            itinerary.outboundDate,
                            itinerary.returnDate.orEmpty(),
                            legs.joinToString(";") {
                                "${it.flightNumber}:${it.from}:${it.to}:${it.departDate}:${it.departTime}"
                            },
                        ).joinToString("|")
                    val assumptions =
                        listOf(
                            scenario.positioningGbp,
                            scenario.hotelsGbp,
                            scenario.groundTransfersGbp,
                            scenario.baggageGbp,
                        ).joinToString("|")
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
                        productKey = sha256(identity),
                        expensesKey = sha256(assumptions),
                        priceCurrency = itinerary.priceCurrency,
                        sourcePricePerPerson = itinerary.priceOriginal ?: itinerary.pricePerPerson,
                        offerExpiresAt = itinerary.refundEvidence?.expiresAt,
                    )
                }
            }
        return rows.sortedWith(compareBy({ it.allInGbp == null }, { it.allInGbp ?: Double.MAX_VALUE }, { it.date }))
    }
}
