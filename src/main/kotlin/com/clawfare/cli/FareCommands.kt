package com.clawfare.cli

import com.clawfare.db.ClawfareDatabase
import com.clawfare.export.JsonExporter
import com.clawfare.export.TopNGenerator
import com.clawfare.search.AppJson
import com.clawfare.search.BatchConfig
import com.clawfare.search.BatchSweep
import com.clawfare.search.DuffelProvider
import com.clawfare.search.Export
import com.clawfare.search.FareStore
import com.clawfare.search.ProviderHttpBudget
import com.clawfare.search.FareWatch
import com.clawfare.search.FxRateStore
import com.clawfare.search.SampleSeeder
import com.clawfare.search.SearchQuery
import com.clawfare.search.SearchService
import com.clawfare.search.SqliteResponseCache
import com.clawfare.search.providersFromEnv
import com.clawfare.web.Server
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.io.File
import java.util.concurrent.Callable

private fun fareService(): SearchService = SearchService(providersFromEnv(), SqliteResponseCache(), FareStore)

private fun splitCodes(raw: String): List<String> = raw.split(Regex("[,\\s]+")).map { it.trim().uppercase() }.filter { it.length == 3 }

@Command(
    name = "fare",
    description = ["Fetch fares from providers into an investigation"],
    mixinStandardHelpOptions = true,
    subcommands = [
        FareEstimateCommand::class,
        FareFetchCommand::class,
        FareReingestCommand::class,
        FareQuotaCommand::class,
        FareHttpBudgetCommand::class,
        FareLoadSampleCommand::class,
        FareFxRefreshCommand::class,
        FareVerifyDuffelCommand::class,
        FareBatchCommand::class,
    ],
)
class FareCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: ClawfareCommand

    override fun call(): Int {
        picocli.CommandLine(this).usage(System.out)
        return 0
    }
}

@Command(
    name = "batch",
    description = ["Plan, execute and compare private multi-route fare searches"],
    subcommands = [
        FareBatchPlanCommand::class,
        FareBatchRunCommand::class,
        FareBatchReportCommand::class,
        FareBatchCoverageCommand::class,
        FareBatchWatchCommand::class,
    ],
)
class FareBatchCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FareCommand

    override fun call(): Int {
        picocli.CommandLine(this).usage(System.out)
        return 0
    }
}

@Command(name = "plan", description = ["Estimate route/date matrix and hard network upper bound; no network"])
class FareBatchPlanCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FareBatchCommand

    @Parameters(index = "0", description = ["Private local JSON batch config"])
    lateinit var file: String

    override fun call(): Int {
        parent.parent.parent.ensureDb()
        val config = AppJson.decodeFromString(BatchConfig.serializer(), File(file).readText())
        val estimate = BatchSweep.estimate(config, fareService())
        println(
            "${estimate.entries.size} scenario/date/route searches: ${estimate.liveCalls} live, " +
                "${estimate.cachedCalls} cached, up to ${estimate.extraReturnCalls} returns.",
        )
        println("Upper bound ${estimate.upperBound}; configured hard cap ${config.maxLiveCalls}.")
        estimate.entries.forEach { println("  ${it.signature}") }
        return if (estimate.upperBound > config.maxLiveCalls) 2 else 0
    }
}

@Command(name = "run", description = ["Execute capped batch only with --confirm; otherwise dry-run"])
class FareBatchRunCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FareBatchCommand

    @Parameters(index = "0", description = ["Private local JSON batch config"])
    lateinit var file: String

    @Option(names = ["--confirm"], description = ["Explicitly authorize live searches within cap"])
    var confirm: Boolean = false

    @Option(names = ["--resume"], description = ["Skip fully completed query signatures from the private audit journal"])
    var resume: Boolean = false

    override fun call(): Int {
        parent.parent.parent.ensureDb()
        val config = AppJson.decodeFromString(BatchConfig.serializer(), File(file).readText())
        val service = fareService()
        val estimate = BatchSweep.estimate(config, service)
        println("Upper bound: ${estimate.upperBound} network calls for ${estimate.entries.size} searches; cap ${config.maxLiveCalls}.")
        if (!confirm) {
            println("Dry-run only. No network calls made. Re-run with --confirm after reviewing the estimate.")
            return 0
        }
        if (estimate.upperBound > config.maxLiveCalls) {
            Output.error("Search matrix exceeds maxLiveCalls; no provider calls made.")
            return 2
        }
        val audit =
            System.getenv("CLAWFARE_BATCH_AUDIT")?.let(::File)
                ?: File(ClawfareDatabase.defaultPath).parentFile.resolve("batch-attempts.ndjson")
        val run = BatchSweep.run(config, service, confirmed = true, auditFile = audit, resume = resume)
        println(
            "Executed ${run.executed}; resumed/skipped ${run.resumedSkipped}; " +
                "saved ${run.saved}; failures ${run.failures.size}. Audit: ${audit.absolutePath}",
        )
        run.failures.forEach { Output.error(it) }
        return if (run.failures.isEmpty()) 0 else 1
    }
}

@Command(name = "coverage", description = ["Export attempted/filtered/unsearched route and provider coverage as CSV"])
class FareBatchCoverageCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FareBatchCommand

    @Parameters(index = "0", description = ["Private local JSON batch config"])
    lateinit var file: String

    @Option(names = ["--audit"], description = ["Private NDJSON attempt log"])
    var audit: String? = null

    @Option(names = ["--out"], required = true, description = ["Private coverage CSV path"])
    lateinit var out: String

    override fun call(): Int {
        val config = AppJson.decodeFromString(BatchConfig.serializer(), File(file).readText())
        val auditFile =
            audit?.let(::File)
                ?: File(ClawfareDatabase.defaultPath).parentFile.resolve("batch-attempts.ndjson")
        val rows = BatchSweep.coverage(config, auditFile)

        fun cell(raw: Any?): String {
            val text = raw?.toString().orEmpty()
            val safe = if (text.startsWith("=") || text.startsWith("+") || text.startsWith("@")) "'$text" else text
            return "\"" + safe.replace("\"", "\"\"") + "\""
        }
        val csv =
            buildString {
                appendLine(
                    "signature,origin,destination,date,returning,market,provider,status,returned,eligible,reasons,observedAt",
                )
                for (row in rows) {
                    appendLine(
                        listOf(
                            row.signature,
                            row.origin,
                            row.destination,
                            row.date,
                            row.returning,
                            row.market,
                            row.provider,
                            row.status,
                            row.returned,
                            row.eligible,
                            row.reasons.entries.joinToString(";") { "${it.key}=${it.value}" },
                            row.observedAt,
                        ).joinToString(",") { cell(it) },
                    )
                }
            }
        File(out).writeText(csv)
        println(
            "Coverage: ${rows.size} provider/route/date cells; ${rows.count { it.status == "not_searched" }} not searched, " +
                "${rows.count { it.status == "eligible" }} with eligible results. Private report: $out",
        )
        return 0
    }
}

@Command(name = "report", description = ["Export comparable party fare + stopover costs as CSV"])
class FareBatchReportCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FareBatchCommand

    @Parameters(index = "0", description = ["Private local JSON batch config"])
    lateinit var file: String

    @Option(names = ["--out"], required = true, description = ["Private CSV output path"])
    lateinit var out: String

    override fun call(): Int {
        parent.parent.parent.ensureDb()
        val config = AppJson.decodeFromString(BatchConfig.serializer(), File(file).readText())
        val rows = BatchSweep.compare(config, FareStore)

        fun cell(value: Any?): String = "\"" + value.toString().replace("\"", "\"\"") + "\""
        val fields =
            listOf(
                "signature",
                "origin",
                "destination",
                "date",
                "source",
                "label",
                "partyFareGbp",
                "allInGbp",
                "expenseEvidence",
                "retrievedAt",
                "journeyType",
            )
        val csv =
            buildString {
                appendLine(fields.joinToString(","))
                for (row in rows) {
                    appendLine(
                        listOf(
                            row.signature,
                            row.origin,
                            row.destination,
                            row.date,
                            row.source,
                            row.sourceLabel,
                            row.partyFareGbp,
                            row.allInGbp,
                            row.expenseEvidence,
                            row.retrievedAt,
                            row.journeyType,
                        ).joinToString(",") { cell(it ?: "") },
                    )
                }
            }
        File(out).writeText(csv)
        println("Exported ${rows.size} observations to $out. Unknown all-in costs stay blank.")
        return 0
    }
}

@Command(name = "watch", description = ["Perform one capped price-check cycle and write local alerts"])
class FareBatchWatchCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FareBatchCommand

    @Parameters(index = "0", description = ["Private local JSON batch config"])
    lateinit var file: String

    @Option(names = ["--confirm"], description = ["Authorize one live collection cycle"])
    var confirm: Boolean = false

    @Option(names = ["--drop-gbp"], defaultValue = "100.0", description = ["Minimum total-party price drop for alerts"])
    var dropGbp: Double = 100.0

    override fun call(): Int {
        parent.parent.parent.ensureDb()
        val config = AppJson.decodeFromString(BatchConfig.serializer(), File(file).readText())
        val service = fareService()
        val estimate = BatchSweep.estimate(config, service)
        println("Watch: ${estimate.entries.size} searches; upper bound ${estimate.upperBound} of cap ${config.maxLiveCalls}.")
        if (!confirm) {
            println("No network calls made. Pass --confirm for a single capped cycle.")
            return 0
        }
        val baseDir = File(ClawfareDatabase.defaultPath).parentFile
        val outcome =
            FareWatch.runOnce(
                config = config,
                service = service,
                sink = FareStore,
                lockFile = baseDir.resolve("batch-watch.lock"),
                auditFile = baseDir.resolve("batch-attempts.ndjson"),
                alertFile = baseDir.resolve("batch-alerts.ndjson"),
                thresholdGbp = dropGbp,
                confirmed = true,
            )
        println("Saved ${outcome.batch.saved} investigations, ${outcome.alerts.size} new/price-drop alerts.")
        println("These are PRICE OBSERVATIONS only, not proof of bookability or cash refunds.")
        outcome.batch.failures.forEach { Output.error(it) }
        return if (outcome.batch.failures.isEmpty()) 0 else 1
    }
}

@Command(name = "estimate", description = ["Count provider calls before spending quota"])
class FareEstimateCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FareCommand

    @Option(names = ["--origin"], required = true, description = ["Origin airports, comma-separated"])
    lateinit var origin: String

    @Option(names = ["--dest"], required = true, description = ["Destination airports, comma-separated"])
    lateinit var destination: String

    @Option(names = ["--depart"], required = true, description = ["Outbound date or YYYY-MM-DD:YYYY-MM-DD"])
    lateinit var depart: String

    @Option(names = ["--return"], description = ["Return date or YYYY-MM-DD:YYYY-MM-DD"])
    var returnRange: String? = null

    @Option(names = ["--nights"], description = ["Trip length in nights"])
    var nights: Int? = null

    @Option(names = ["--cabin"], defaultValue = "economy", description = ["Cabin"])
    var cabin: String = "economy"

    @Option(names = ["--adults"], defaultValue = "1")
    var adults: Int = 1

    @Option(names = ["--bags"], defaultValue = "0", description = ["Checked bags per person"])
    var bags: Int = 0

    @Option(names = ["--providers"], defaultValue = "serpapi,searchapi,travelpayouts,duffel")
    var providers: String = "serpapi,searchapi,travelpayouts,duffel"

    @Option(
        names = ["--exclude-airlines"],
        defaultValue = "LO",
        description = ["Blocked airline codes (comma-separated; empty to allow LOT)"],
    )
    var excludedAirlines: String = "LO"

    @Option(names = ["--nonstop"], description = ["Nonstop only"])
    var nonstop: Boolean = false

    @Option(names = ["--cache-only"], description = ["Count cached responses only"])
    var cacheOnly: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()
        // Estimates are strictly offline. Even account/quota checks are
        // conservative budgeted HTTP requests and require a separate command.
        val estimate = fareService().estimate(queryFrom(this), includeQuota = false)
        println(formatEstimate(estimate))
        return if (estimate.warnings.any {
                it.startsWith("Enter") ||
                    it.startsWith("Check") ||
                    it.startsWith("Set") ||
                    it.startsWith("No outbound") ||
                    it.contains("Narrow")
            }
        ) {
            1
        } else {
            0
        }
    }
}

@Command(name = "fetch", description = ["Fetch fares into an investigation. Asks for confirmation when a live call is required."])
class FareFetchCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FareCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["--origin"], required = true)
    lateinit var origin: String

    @Option(names = ["--dest"], required = true)
    lateinit var destination: String

    @Option(names = ["--depart"], required = true)
    lateinit var depart: String

    @Option(names = ["--return"])
    var returnRange: String? = null

    @Option(names = ["--nights"])
    var nights: Int? = null

    @Option(names = ["--cabin"], defaultValue = "economy")
    var cabin: String = "economy"

    @Option(names = ["--adults"], defaultValue = "1")
    var adults: Int = 1

    @Option(names = ["--bags"], defaultValue = "0")
    var bags: Int = 0

    @Option(names = ["--providers"], defaultValue = "serpapi,searchapi,travelpayouts,duffel")
    var providers: String = "serpapi,searchapi,travelpayouts,duffel"

    @Option(
        names = ["--exclude-airlines"],
        defaultValue = "LO",
        description = ["Blocked airline codes (comma-separated; empty to allow LOT)"],
    )
    var excludedAirlines: String = "LO"

    @Option(names = ["--nonstop"])
    var nonstop: Boolean = false

    @Option(names = ["--cache-only"], description = ["Re-read cached responses. No network."])
    var cacheOnly: Boolean = false

    @Option(names = ["--confirm"], description = ["Spend quota. Without this, only the estimate is printed."])
    var confirm: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()
        val query =
            queryFrom(
                origin = origin,
                destination = destination,
                depart = depart,
                returnRange = returnRange,
                nights = nights,
                cabin = cabin,
                adults = adults,
                bags = bags,
                excludedAirlines = excludedAirlines,
                providers = providers,
                nonstop = nonstop,
                cacheOnly = cacheOnly,
                confirm = confirm,
                investigation = slug,
            )
        val response = fareService().search(query)
        if (response.needsConfirmation) {
            println(formatEstimate(response.estimate))
            println("Nothing was fetched. Re-run with --confirm to spend quota, or --cache-only to reuse cached responses.")
            return 2
        }
        if (!response.ok || response.result == null) {
            Output.error(response.error ?: "Search failed")
            return 1
        }
        val result = response.result
        println("Saved ${result.itineraries.size} itineraries on ${result.id}.")
        result.warnings.forEach { println("  $it") }
        return 0
    }
}

@Command(
    name = "fx-refresh",
    description = ["Fetch and persist dated GBP-based reference exchange rates"],
    mixinStandardHelpOptions = true,
)
class FareFxRefreshCommand : Callable<Int> {
    override fun call(): Int =
        try {
            val snapshot = FxRateStore.refresh()
            println("Saved reference rates for ${snapshot.date} to ${FxRateStore.defaultFile().absolutePath}.")
            0
        } catch (e: Exception) {
            Output.error("FX refresh failed: ${e.message ?: "unknown error"}")
            1
        }
}

@Command(
    name = "verify-duffel",
    description = ["Refresh a live Duffel offer by ID (read-only, never books)"],
    mixinStandardHelpOptions = true,
)
class FareVerifyDuffelCommand : Callable<Int> {
    @Parameters(index = "0", description = ["Exact Duffel offer ID (off_...)"])
    lateinit var offerId: String

    @Option(names = ["--confirm"], description = ["Authorize one live offer GET request"])
    var confirm: Boolean = false

    override fun call(): Int {
        if (!confirm) {
            println("No API request made. Re-run with --confirm to refresh this specific Duffel offer.")
            return 2
        }
        val provider = providersFromEnv().filterIsInstance<DuffelProvider>().single()
        val latest =
            try {
                provider.refreshOfferWithPricing(offerId)
            } catch (e: Exception) {
                Output.error("Offer recheck failed: ${e.message ?: "unknown error"}")
                return 1
            }
        val evidence = latest.refund
        println("Offer ${latest.offerId}: total ${latest.totalAmount} ${latest.totalCurrency}")
        println("Observed: ${evidence.observedAt}; expires: ${evidence.expiresAt ?: "unknown"}")
        println(
            "Refund status: ${evidence.refundStatus}; airline penalty: " +
                "${evidence.penaltyAmount ?: "unknown"} ${evidence.penaltyCurrency ?: ""}",
        )
        println("Return method: ${evidence.method}; extra servicing fee: ${evidence.servicingFeeAmount ?: "unknown"}")
        println("Confirmed cash refund to original payment method: NO — requires independent fare evidence.")
        return 0
    }
}

@Command(name = "reingest", description = ["Parse cached provider responses again. Does not call the network."])
class FareReingestCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FareCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    override fun call(): Int {
        parent.parent.ensureDb()
        val stored = FareStore.storedQuery(slug)
        if (stored == null) {
            Output.error("Investigation '$slug' has no stored provider query. Fetch it first, or load the sample.")
            return 1
        }
        val response =
            fareService().search(
                stored.copy(cacheOnly = true, confirmed = true, investigation = slug),
            )
        if (!response.ok || response.result == null) {
            Output.error(response.error ?: "Re-ingest failed")
            return 1
        }
        println("Re-ingested ${response.result.itineraries.size} itineraries on $slug from cached responses.")
        response.result.warnings.forEach { println("  $it") }
        return 0
    }
}

@Command(name = "quota", description = ["Query provider account quotas (HTTP request; counts against conservative local cap)"])
class FareQuotaCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FareCommand

    override fun call(): Int {
        parent.parent.ensureDb()
        for (provider in providersFromEnv()) {
            val quota = provider.quota()
            val remaining = quota.remaining?.let { " remaining $it" } ?: ""
            val limit = quota.limit?.let { " of $it" } ?: ""
            val note = quota.note?.let { " — $it" } ?: ""
            println("${quota.provider}: ${if (quota.configured) "configured" else "not configured"}$remaining$limit$note")
        }
        return 0
    }
}

@Command(name = "http-budget", description = ["Inspect private provider HTTP reservations without network access"])
class FareHttpBudgetCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FareCommand

    override fun call(): Int {
        val budget = ProviderHttpBudget.fromEnvironment()
        for (provider in listOf("serpapi", "searchapi", "travelpayouts", "duffel")) {
            val usage = budget.usage(provider)
            println(
                "${usage.provider}: ${usage.reservedToday}/${usage.dailyLimit ?: "unconfigured"} reserved today; " +
                    "${usage.reservedThisMonth}/${usage.monthlyLimit ?: "no cap"} this month; " +
                    "${usage.unfinished} unfinished. Charge status is unknown unless confirmed by provider billing.",
            )
        }
        return 0
    }
}

@Command(name = "load-sample", description = ["Import the Florida / Mexico sample into the database. No API calls."])
class FareLoadSampleCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FareCommand

    override fun call(): Int {
        parent.parent.ensureDb()
        val result = SampleSeeder.ensure()
        println(
            "Loaded ${result.id}: ${result.itineraries.size} long-haul, ${result.addons.size} add-ons, ${result.vendors.size} seller rows.",
        )
        return 0
    }
}

@Command(
    name = "serve",
    description = ["Serve the flight-search web UI from the database"],
)
class ServeCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: ClawfareCommand

    @Option(names = ["--port", "-p"], description = ["Port (or CLAWFARE_PORT)"], defaultValue = "0")
    var port: Int = 0

    override fun call(): Int {
        parent.ensureDb()
        SampleSeeder.ensure()
        val chosen =
            when {
                port > 0 -> port
                !System.getenv("CLAWFARE_PORT").isNullOrBlank() -> System.getenv("CLAWFARE_PORT").toInt()
                else -> 8080
            }
        if (!ClawfareDatabase.isInitialized()) {
            Output.error("Database is not connected")
            return 1
        }
        Server.start(chosen)
        return 0
    }
}

@Command(
    name = "report",
    description = ["Export an investigation"],
    mixinStandardHelpOptions = true,
    subcommands = [
        ReportJsonCommand::class,
        ReportTopCommand::class,
        ReportHtmlCommand::class,
        ReportCsvCommand::class,
    ],
)
class ReportCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: ClawfareCommand

    override fun call(): Int {
        picocli.CommandLine(this).usage(System.out)
        return 0
    }
}

@Command(name = "json", description = ["Export flights as JSON"])
class ReportJsonCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: ReportCommand

    @Parameters(index = "0")
    lateinit var slug: String

    override fun call(): Int {
        parent.parent.ensureDb()
        println(JsonExporter.exportByInvestigation(slug))
        return 0
    }
}

@Command(name = "top", description = ["Top-N markdown ranking"])
class ReportTopCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: ReportCommand

    @Parameters(index = "0")
    lateinit var slug: String

    @Option(names = ["--limit"], defaultValue = "10")
    var limit: Int = 10

    override fun call(): Int {
        parent.parent.ensureDb()
        println(TopNGenerator.generateByInvestigation(slug, limit))
        return 0
    }
}

@Command(name = "html", description = ["Self-contained HTML snapshot of the filtered view"])
class ReportHtmlCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: ReportCommand

    @Parameters(index = "0")
    lateinit var slug: String

    @Option(names = ["--out"], required = true, description = ["Output file"])
    lateinit var out: String

    override fun call(): Int {
        parent.parent.ensureDb()
        val result = FareStore.project(slug)
        if (result == null) {
            Output.error("Investigation '$slug' was not found")
            return 1
        }
        val html = Export.html(Server.page(), result.copy(suggestedFilters = result.suggestedFilters))
        File(out).writeText(html)
        println("Wrote $out")
        return 0
    }
}

@Command(name = "csv", description = ["CSV of the filtered itineraries or seller rows"])
class ReportCsvCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: ReportCommand

    @Parameters(index = "0")
    lateinit var slug: String

    @Option(names = ["--out"], required = true)
    lateinit var out: String

    @Option(names = ["--vendors"], description = ["Export the seller comparison"])
    var vendors: Boolean = false

    @Option(names = ["--max-stops"])
    var maxStops: Int? = null

    @Option(names = ["--nonstop"])
    var nonstop: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()
        val result = FareStore.project(slug)
        if (result == null) {
            Output.error("Investigation '$slug' was not found")
            return 1
        }
        val filters =
            result.suggestedFilters.copy(
                maxStops = maxStops ?: result.suggestedFilters.maxStops,
                nonstopOnly = nonstop || result.suggestedFilters.nonstopOnly,
            )
        val csv = if (vendors) Export.vendorCsv(result) else Export.itineraryCsv(result, filters)
        File(out).writeText(csv)
        println("Wrote $out")
        return 0
    }
}

private fun queryFrom(command: FareEstimateCommand): SearchQuery =
    queryFrom(
        origin = command.origin,
        destination = command.destination,
        depart = command.depart,
        returnRange = command.returnRange,
        nights = command.nights,
        cabin = command.cabin,
        adults = command.adults,
        bags = command.bags,
        excludedAirlines = command.excludedAirlines,
        providers = command.providers,
        nonstop = command.nonstop,
        cacheOnly = command.cacheOnly,
        confirm = false,
        investigation = null,
    )

private fun queryFrom(
    origin: String,
    destination: String,
    depart: String,
    returnRange: String?,
    nights: Int?,
    cabin: String,
    adults: Int,
    bags: Int,
    excludedAirlines: String,
    providers: String,
    nonstop: Boolean,
    cacheOnly: Boolean,
    confirm: Boolean,
    investigation: String?,
): SearchQuery {
    val (outFrom, outTo) = dateRange(depart)
    val (retFrom, retTo) = if (returnRange.isNullOrBlank()) null to null else dateRange(returnRange)
    return SearchQuery(
        origins = splitCodes(origin),
        destinations = splitCodes(destination),
        outboundFrom = outFrom,
        outboundTo = outTo,
        returnFrom = retFrom,
        returnTo = retTo,
        nights = nights,
        cabin = cabin,
        adults = adults,
        checkedBags = bags,
        excludedAirlines = excludedAirlines.split(Regex("[,\\s]+")).map { it.trim().uppercase() }.filter { it.isNotBlank() },
        currency = "GBP",
        providers = providers.split(Regex("[,\\s]+")).map { it.trim().lowercase() }.filter { it.isNotEmpty() },
        nonstopOnly = nonstop,
        cacheOnly = cacheOnly,
        confirmed = confirm,
        investigation = investigation,
    )
}

private fun dateRange(raw: String): Pair<String, String> {
    val parts = raw.split(":")
    return if (parts.size == 2) parts[0] to parts[1] else raw to raw
}

private fun formatEstimate(estimate: com.clawfare.search.Estimate?): String {
    if (estimate == null) return "No estimate."
    return buildString {
        appendLine(
            "${estimate.liveCalls} live calls, ${estimate.cachedCalls} cached, ${estimate.skippedCalls} skipped, up to ${estimate.returnLookupsUpperBound} return lookups.",
        )
        estimate.lines.forEach { appendLine("  $it") }
        estimate.warnings.forEach { appendLine(it) }
        estimate.quota.forEach { quota ->
            appendLine("${quota.provider}: ${quota.note ?: if (quota.configured) "configured" else "not configured"}")
        }
    }.trimEnd()
}
