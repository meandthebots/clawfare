package com.clawfare.cli

import com.clawfare.db.ClawfareDatabase
import com.clawfare.export.JsonExporter
import com.clawfare.export.TopNGenerator
import com.clawfare.search.Export
import com.clawfare.search.FareStore
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
        FareLoadSampleCommand::class,
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

    @Option(names = ["--nonstop"], description = ["Nonstop only"])
    var nonstop: Boolean = false

    @Option(names = ["--cache-only"], description = ["Count cached responses only"])
    var cacheOnly: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()
        val estimate = fareService().estimate(queryFrom(this))
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

@Command(name = "quota", description = ["Show remaining provider quota. Account checks do not spend a flight search."])
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
