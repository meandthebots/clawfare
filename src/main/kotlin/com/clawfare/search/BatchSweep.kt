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
    /** Added in v2. Old attempts are explicitly unknown rather than zero results. */
    val providerObservations: List<ProviderObservation> = emptyList(),
    val estimatedCachedCalls: Int = 0,
    val estimatedSkippedCalls: Int = 0,
    val estimatedReturnCalls: Int = 0,
)

data class BatchRunSummary(
    val planned: BatchEstimate,
    val executed: Int,
    val saved: Int,
    val failures: List<String>,
    val observedAt: String,
    val resumedSkipped: Int = 0,
)

data class BatchCoverageRow(
    val signature: String,
    val origin: String,
    val destination: String,
    val date: String,
    val returning: String?,
    val market: String,
    val provider: String,
    val status: String,
    val returned: Int?,
    val eligible: Int?,
    val reasons: Map<String, Int>,
    val observedAt: String?,
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
        resume: Boolean = false,
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
        var resumedSkipped = 0
        val prior =
            if (resume) {
                readAttempts(auditFile)
                    .groupBy { it.querySignatureSha256 }
                    .mapValues { (_, records) -> records.last() }
            } else {
                emptyMap()
            }
        for (entry in planned.entries) {
            val key = sha256(AppJson.encodeToString(SearchQuery.serializer(), entry.query))
            val last = prior[key]
            if (last != null &&
                last.status in setOf("ok", "zero_returned", "filtered_all") &&
                entry.query.providers.all { provider ->
                    last.providerObservations.any { it.provider == provider && it.state == "completed" }
                }
            ) {
                resumedSkipped++
                continue
            }
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
                val observations = response.providerObservations
                val count = response.result?.itineraries?.size ?: 0
                val state =
                    when {
                        !response.ok -> "error"
                        observations.any { it.state == "failed" || it.state == "unconfigured" } -> "incomplete"
                        observations.isEmpty() -> "unknown"
                        count > 0 -> "ok"
                        observations.any { it.returned > 0 } -> "filtered_all"
                        else -> "zero_returned"
                    }
                val record =
                    BatchAttemptRecord(
                        observedAt = Instant.now().toString(),
                        querySignatureSha256 = key,
                        investigation = entry.signature,
                        providerIds = entry.query.providers,
                        status = state,
                        maxNetworkCalls = upper,
                        offerCount = count,
                        warnings = response.result?.warnings.orEmpty().take(8).map { redact(it).take(200) },
                        providerObservations = observations,
                        estimatedCachedCalls = estimate.cachedCalls,
                        estimatedSkippedCalls = estimate.skippedCalls,
                        estimatedReturnCalls = estimate.returnLookupsUpperBound,
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
        return BatchRunSummary(planned, executed, saved, failures, Instant.now().toString(), resumedSkipped)
    }

    /** The full expected matrix is the denominator: unattempted entries are never called sold-out. */
    fun coverage(
        config: BatchConfig,
        auditFile: File?,
    ): List<BatchCoverageRow> {
        val attempts = readAttempts(auditFile).groupBy { it.querySignatureSha256 }
        return expand(config).flatMap { entry ->
            val key = sha256(AppJson.encodeToString(SearchQuery.serializer(), entry.query))
            val latest = attempts[key]?.lastOrNull()
            entry.query.providers.map { provider ->
                val observation = latest?.providerObservations?.firstOrNull { it.provider == provider }
                val status =
                    when {
                        latest == null -> "not_searched"
                        observation == null -> "legacy_unknown"
                        observation.state == "failed" -> "provider_failed"
                        observation.state == "unconfigured" -> "provider_unconfigured"
                        observation.state == "cache_only" -> "cache_only"
                        observation.returned == 0 -> "zero_returned"
                        observation.eligible == 0 -> "filtered_all"
                        else -> "eligible"
                    }
                BatchCoverageRow(
                    signature = entry.signature,
                    origin = entry.query.origins.single(),
                    destination = entry.query.destinations.single(),
                    date = entry.query.outboundFrom,
                    returning = entry.query.returnFrom,
                    market = entry.query.market,
                    provider = provider,
                    status = status,
                    returned = observation?.returned,
                    eligible = observation?.eligible,
                    reasons = observation?.excludedByReason.orEmpty(),
                    observedAt = latest?.observedAt,
                )
            }
        }
    }

    /** Corrupt journals fail closed before resuming a potentially paid batch. */
    private fun readAttempts(auditFile: File?): List<BatchAttemptRecord> =
        if (auditFile?.isFile == true) {
            auditFile.readLines().filter { it.isNotBlank() }.map { line ->
                AppJson.decodeFromString(BatchAttemptRecord.serializer(), line)
            }
        } else {
            emptyList()
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
