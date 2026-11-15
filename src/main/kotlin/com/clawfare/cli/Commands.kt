package com.clawfare.cli

import com.clawfare.db.ClawfareDatabase
import com.clawfare.db.FlightDto
import com.clawfare.db.FlightQueries
import com.clawfare.db.FlightWithPrice
import com.clawfare.db.InvestigationDto
import com.clawfare.db.InvestigationQueries
import com.clawfare.db.PriceHistoryDto
import com.clawfare.db.PriceHistoryQueries
import com.clawfare.model.FlightLeg
import com.clawfare.model.FlightSegment
import com.clawfare.model.FlightValidator
import kotlinx.serialization.json.Json
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.time.Instant
import java.util.concurrent.Callable

// ============================================================================
// Investigation Commands
// ============================================================================

/**
 * Investigation parent command.
 */
@Command(
    name = "inv",
    description = ["Manage flight investigations"],
    mixinStandardHelpOptions = true,
    subcommands = [
        InvNewCommand::class,
        InvListCommand::class,
        InvShowCommand::class,
        InvDeleteCommand::class,
        InvResumeCommand::class,
        InvConfigCommand::class,
        InvUrlsCommand::class,
    ],
)
class InvCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: ClawfareCommand

    override fun call(): Int {
        CommandLine(this).usage(System.out)
        return 0
    }
}

/**
 * Create a new investigation.
 */
@Command(
    name = "new",
    description = ["Create a new investigation"],
)
class InvNewCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: InvCommand

    @Parameters(index = "0", description = ["Investigation slug (e.g., tokyo-may-2026)"])
    lateinit var slug: String

    @Option(names = ["--origin", "-o"], required = true, description = ["Origin airport code"])
    lateinit var origin: String

    @Option(names = ["--dest", "-d"], required = true, description = ["Destination airport code"])
    lateinit var destination: String

    @Option(
        names = ["--depart"],
        required = true,
        description = ["Departure date range (YYYY-MM-DD:YYYY-MM-DD)"],
    )
    lateinit var depart: String

    @Option(names = ["--return"], description = ["Return date range (YYYY-MM-DD:YYYY-MM-DD)"])
    var returnRange: String? = null

    @Option(names = ["--cabin"], description = ["Cabin class (economy, business, first)"], defaultValue = "economy")
    var cabinClass: String = "economy"

    @Option(names = ["--max-stops"], description = ["Maximum number of stops"], defaultValue = "1")
    var maxStops: Int = 1

    override fun call(): Int {
        parent.parent.ensureDb()

        // Check if slug already exists
        if (InvestigationQueries.getBySlug(slug) != null) {
            Output.error("Investigation '$slug' already exists")
            return 1
        }

        // Parse date ranges
        val departParts = depart.split(":")
        if (departParts.size != 2) {
            Output.error("Invalid depart format. Use YYYY-MM-DD:YYYY-MM-DD")
            return 1
        }

        val (returnStart, returnEnd) =
            if (returnRange != null) {
                val parts = returnRange!!.split(":")
                if (parts.size != 2) {
                    Output.error("Invalid return format. Use YYYY-MM-DD:YYYY-MM-DD")
                    return 1
                }
                parts[0] to parts[1]
            } else {
                null to null
            }

        val now = Instant.now().toString()
        val dto =
            InvestigationDto(
                slug = slug,
                origin = origin.uppercase(),
                destination = destination.uppercase(),
                departStart = departParts[0],
                departEnd = departParts[1],
                returnStart = returnStart,
                returnEnd = returnEnd,
                cabinClass = cabinClass,
                maxStops = maxStops,
                createdAt = now,
                updatedAt = now,
            )

        try {
            InvestigationQueries.create(dto)
            Output.success("Created investigation: $slug")
            println(Output.formatInvestigation(dto))
            return 0
        } catch (e: Exception) {
            Output.error("Failed to create investigation: ${e.message}")
            return 1
        }
    }
}

/**
 * List all investigations.
 */
@Command(
    name = "list",
    description = ["List all investigations"],
)
class InvListCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: InvCommand

    @Option(names = ["--json"], description = ["Output as JSON"])
    var jsonOutput: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        val investigations = InvestigationQueries.listAll()

        if (jsonOutput) {
            println(Output.toJson(investigations))
        } else {
            println(Output.formatInvestigationTable(investigations))
        }

        return 0
    }
}

/**
 * Show investigation details.
 */
@Command(
    name = "show",
    description = ["Show investigation details"],
)
class InvShowCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: InvCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["--json"], description = ["Output as JSON"])
    var jsonOutput: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        val investigation = InvestigationQueries.getBySlug(slug)
        if (investigation == null) {
            Output.error("Investigation '$slug' not found")
            return 1
        }

        if (jsonOutput) {
            println(Output.toJson(investigation))
        } else {
            println(Output.formatInvestigation(investigation, detailed = true))

            // Also show flight count
            val flights = FlightQueries.listByInvestigation(slug)
            println("  Flights:  ${flights.size}")
        }

        return 0
    }
}

/**
 * Delete an investigation.
 */
@Command(
    name = "delete",
    description = ["Delete an investigation and all its flights"],
)
class InvDeleteCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: InvCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["--force", "-f"], description = ["Skip confirmation"])
    var force: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        val investigation = InvestigationQueries.getBySlug(slug)
        if (investigation == null) {
            Output.error("Investigation '$slug' not found")
            return 1
        }

        // Count associated flights
        val flights = FlightQueries.listByInvestigation(slug)

        if (!force && flights.isNotEmpty()) {
            Output.warn("This will delete ${flights.size} flight(s). Use --force to confirm.")
            return 1
        }

        // Delete flights first
        if (flights.isNotEmpty()) {
            FlightQueries.deleteByInvestigation(slug)
        }

        // Delete investigation
        if (InvestigationQueries.delete(slug)) {
            Output.success("Deleted investigation '$slug' and ${flights.size} flight(s)")
            return 0
        } else {
            Output.error("Failed to delete investigation")
            return 1
        }
    }
}

/**
 * Resume an investigation - output context for continuing work.
 */
@Command(
    name = "resume",
    description = ["Output investigation context for resuming work"],
)
class InvResumeCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: InvCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["--json"], description = ["Output as JSON"])
    var jsonOutput: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        val investigation = InvestigationQueries.getBySlug(slug)
        if (investigation == null) {
            Output.error("Investigation '$slug' not found")
            return 1
        }

        val flightsWithPrices = FlightQueries.listWithPrices(slug)
        val flights = FlightQueries.listByInvestigation(slug)
        val priceHistory = flights.flatMap { f -> PriceHistoryQueries.getByFlightId(f.id) }

        if (jsonOutput) {
            val context =
                mapOf(
                    "investigation" to investigation,
                    "flights" to flights,
                    "price_history" to priceHistory,
                )
            println(Output.toJson(context))
        } else {
            println("=== RESUME: $slug ===")
            println()
            println("--- Investigation ---")
            println(Output.formatInvestigation(investigation, detailed = true))
            println()
            println("--- Flights (${flightsWithPrices.size}) ---")
            if (flightsWithPrices.isEmpty()) {
                println("No flights recorded yet.")
            } else {
                // Group by cabin class
                val byClass = flightsWithPrices.groupBy { it.bookingClass ?: "unknown" }
                byClass.forEach { (cabin, list) ->
                    println("\n$cabin:")
                    list.sortedBy { it.priceAmount }.forEach { f ->
                        val price = Output.formatPrice(f.priceAmount, f.priceCurrency)
                        val route = "${f.origin}→${f.destination}"
                        val stops = Output.parseStops(f.outboundJson)
                        println("  $price | $route | ${stops}stop | ${f.source} | ${f.id}")
                    }
                }

                // Price range
                val minPrice = flightsWithPrices.minOfOrNull { it.priceAmount }
                val maxPrice = flightsWithPrices.maxOfOrNull { it.priceAmount }
                val currency = flightsWithPrices.firstOrNull()?.priceCurrency ?: "GBP"
                println()
                println("Price range: ${Output.formatPrice(minPrice ?: 0.0, currency)} - ${Output.formatPrice(maxPrice ?: 0.0, currency)}")
            }

            // Flights needing refresh (checked > 24h ago)
            val staleFlights =
                flightsWithPrices.filter { f ->
                    val checkedAt = java.time.Instant.parse(f.priceCheckedAt)
                    val dayAgo =
                        java.time.Instant
                            .now()
                            .minusSeconds(86400)
                    checkedAt.isBefore(dayAgo)
                }
            if (staleFlights.isNotEmpty()) {
                println()
                println("--- Needs Price Refresh (${staleFlights.size}) ---")
                staleFlights.forEach { f ->
                    println("  ${f.id}: ${f.sourceUrl}")
                }
            }
        }

        return 0
    }
}

/**
 * Update investigation config.
 */
@Command(
    name = "config",
    description = ["Update investigation configuration"],
)
class InvConfigCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: InvCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["--max-price"], description = ["Maximum price to track"])
    var maxPrice: Double? = null

    @Option(names = ["--depart-after"], description = ["Minimum departure time (HH:MM)"])
    var departAfter: String? = null

    @Option(names = ["--depart-before"], description = ["Maximum departure time (HH:MM)"])
    var departBefore: String? = null

    @Option(names = ["--min-days"], description = ["Minimum trip duration in days"])
    var minTripDays: Int? = null

    @Option(names = ["--max-days"], description = ["Maximum trip duration in days"])
    var maxTripDays: Int? = null

    @Option(names = ["--must-include"], description = ["Date that must be included (YYYY-MM-DD)"])
    var mustIncludeDate: String? = null

    @Option(names = ["--max-layover"], description = ["Maximum layover in minutes"])
    var maxLayoverMinutes: Int? = null

    @Option(names = ["--exclude-airline"], description = ["Add airline code to exclusion list (e.g. LO)"])
    var excludeAirline: String? = null

    @Option(names = ["--include-airline"], description = ["Add airline code to inclusion list (e.g. TK)"])
    var includeAirline: String? = null

    @Option(names = ["--remove-exclude"], description = ["Remove airline code from exclusion list"])
    var removeExclude: String? = null

    @Option(names = ["--remove-include"], description = ["Remove airline code from inclusion list"])
    var removeInclude: String? = null

    @Option(names = ["--show"], description = ["Show current config"])
    var showConfig: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        val investigation = InvestigationQueries.getBySlug(slug)
        if (investigation == null) {
            Output.error("Investigation '$slug' not found")
            return 1
        }

        if (showConfig) {
            println("Investigation: $slug")
            println("  Max price: ${investigation.maxPrice ?: "not set"}")
            println("  Depart after: ${investigation.departAfter ?: "any"}")
            println("  Depart before: ${investigation.departBefore ?: "any"}")
            println("  Trip days: ${investigation.minTripDays ?: "?"}-${investigation.maxTripDays ?: "?"}")
            println("  Must include: ${investigation.mustIncludeDate ?: "not set"}")
            println("  Max layover: ${investigation.maxLayoverMinutes?.let { "${it}m (${it / 60}h ${it % 60}m)" } ?: "not set"}")
            println("  Excluded airlines: ${investigation.excludedAirlines ?: "none"}")
            println("  Included airlines: ${investigation.includedAirlines ?: "none"}")
            return 0
        }

        // Process airline list modifications
        var newExcluded = investigation.excludedAirlines
        var newIncluded = investigation.includedAirlines

        if (excludeAirline != null) {
            val code = excludeAirline!!.uppercase()
            val current =
                newExcluded
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.toMutableSet() ?: mutableSetOf()
            current.add(code)
            newExcluded = current.sorted().joinToString(",")
            println("  Added $code to exclusion list")
        }
        if (removeExclude != null) {
            val code = removeExclude!!.uppercase()
            val current =
                newExcluded
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.toMutableSet() ?: mutableSetOf()
            current.remove(code)
            newExcluded = if (current.isEmpty()) null else current.sorted().joinToString(",")
            println("  Removed $code from exclusion list")
        }
        if (includeAirline != null) {
            val code = includeAirline!!.uppercase()
            val current =
                newIncluded
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.toMutableSet() ?: mutableSetOf()
            current.add(code)
            newIncluded = current.sorted().joinToString(",")
            println("  Added $code to inclusion list")
        }
        if (removeInclude != null) {
            val code = removeInclude!!.uppercase()
            val current =
                newIncluded
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.toMutableSet() ?: mutableSetOf()
            current.remove(code)
            newIncluded = if (current.isEmpty()) null else current.sorted().joinToString(",")
            println("  Removed $code from inclusion list")
        }

        // Update config
        val updated =
            InvestigationQueries.updateConfig(
                slug,
                maxPrice = maxPrice,
                departAfter = departAfter,
                departBefore = departBefore,
                minTripDays = minTripDays,
                maxTripDays = maxTripDays,
                mustIncludeDate = mustIncludeDate,
                maxLayoverMinutes = maxLayoverMinutes,
                excludedAirlines = newExcluded,
                includedAirlines = newIncluded,
            )

        if (updated) {
            Output.success("Updated config for '$slug'")
            return 0
        } else {
            Output.error("No changes made")
            return 1
        }
    }
}

/**
 * Generate Google Flights URLs for an investigation.
 */
@Command(
    name = "urls",
    description = ["Generate Google Flights search URLs for price checking"],
)
class InvUrlsCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: InvCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["--dates"], description = ["Specific date pairs: YYYY-MM-DD:YYYY-MM-DD,..."])
    var dates: String? = null

    @Option(names = ["--sample"], description = ["Generate N sample date combinations"])
    var sample: Int? = null

    override fun call(): Int {
        parent.parent.ensureDb()

        val inv = InvestigationQueries.getBySlug(slug)
        if (inv == null) {
            Output.error("Investigation '$slug' not found")
            return 1
        }

        val urls = mutableListOf<Pair<String, String>>()

        if (dates != null) {
            // Parse specific dates
            dates!!.split(",").forEach { pair ->
                val parts = pair.split(":")
                if (parts.size == 2) {
                    val url = buildGoogleFlightsUrl(inv, parts[0], parts[1])
                    urls.add(pair to url)
                }
            }
        } else {
            // Generate date combinations from investigation range
            val departStart = java.time.LocalDate.parse(inv.departStart)
            val departEnd = java.time.LocalDate.parse(inv.departEnd)
            val returnStart = inv.returnStart?.let { java.time.LocalDate.parse(it) }
            val returnEnd = inv.returnEnd?.let { java.time.LocalDate.parse(it) }

            // Build all valid date combinations
            val allCombos = mutableListOf<Pair<java.time.LocalDate, java.time.LocalDate?>>()

            var depart = departStart
            while (!depart.isAfter(departEnd)) {
                if (returnStart != null && returnEnd != null) {
                    // Round trip - all valid return dates
                    var ret = maxOf(returnStart, depart.plusDays(inv.minTripDays?.toLong() ?: 1))
                    val maxRet =
                        if (inv.maxTripDays != null) {
                            minOf(returnEnd, depart.plusDays(inv.maxTripDays!!.toLong()))
                        } else {
                            returnEnd
                        }
                    while (!ret.isAfter(maxRet)) {
                        allCombos.add(depart to ret)
                        ret = ret.plusDays(1)
                    }
                } else {
                    // One way
                    allCombos.add(depart to null)
                }
                depart = depart.plusDays(1)
            }

            // Sample or use all
            val selected =
                if (sample != null && sample!! < allCombos.size) {
                    allCombos.shuffled().take(sample!!)
                } else {
                    allCombos
                }

            selected.forEach { (dep, ret) ->
                val dateKey = if (ret != null) "$dep:$ret" else "$dep"
                val url = buildGoogleFlightsUrl(inv, dep.toString(), ret?.toString())
                urls.add(dateKey to url)
            }
        }

        // Output URLs
        if (urls.isEmpty()) {
            Output.warn("No date combinations generated")
            return 0
        }

        println("# Google Flights URLs for $slug (${urls.size} searches)")
        println()
        urls.sortedBy { it.first }.forEach { (dates, url) ->
            println("# $dates")
            println(url)
            println()
        }

        return 0
    }

    companion object {
        /**
         * Build a Google Flights search URL for a specific date pair.
         * Public so --report can use it per-flight.
         */
        fun buildGoogleFlightsUrl(
            inv: InvestigationDto,
            departDate: String,
            returnDate: String?,
        ): String = buildGoogleFlightsUrl(inv.origin, inv.destination, inv.cabinClass, departDate, returnDate, inv.maxStops)

        fun buildGoogleFlightsUrl(
            origin: String,
            destination: String,
            cabinClass: String,
            departDate: String,
            returnDate: String?,
            maxStops: Int = 1,
        ): String {
            val cabin =
                when (cabinClass.lowercase()) {
                    "economy" -> "economy"
                    "premium_economy" -> "premium+economy"
                    "business" -> "business+class"
                    "first" -> "first+class"
                    else -> cabinClass
                }

            return buildString {
                append("https://www.google.com/travel/flights")
                append("?q=Flights+from+$origin+to+$destination")
                append("+on+$departDate")
                if (returnDate != null) {
                    append("+returning+$returnDate")
                }
                append("+$cabin")
                if (maxStops == 0) {
                    append("+nonstop")
                }
                append("&curr=GBP")
            }
        }
    }
}

// ============================================================================
// Flight Commands
// ============================================================================

/**
 * Flight parent command.
 */
@Command(
    name = "flight",
    description = ["Manage flights"],
    mixinStandardHelpOptions = true,
    subcommands = [
        FlightAddCommand::class,
        FlightListCommand::class,
        FlightShowCommand::class,
        FlightDeleteCommand::class,
        FlightUpdateCommand::class,
        FlightTagCommand::class,
        FlightUntagCommand::class,
        FlightPriceCommand::class,
        FlightMarkStaleCommand::class,
        FlightStaleCommand::class,
        FlightHistoryCommand::class,
        FlightRefreshCommand::class,
        FlightTasksCommand::class,
        FlightCoverageCommand::class,
        FlightValidateCommand::class,
        FlightImportCommand::class,
        FlightMatchCommand::class,
        FlightDedupeCommand::class,
        FlightExportTasksCommand::class,
        FlightImportResultsCommand::class,
        FlightImportDbCommand::class,
    ],
)
class FlightCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: ClawfareCommand

    override fun call(): Int {
        CommandLine(this).usage(System.out)
        return 0
    }
}

/**
 * Add a new flight manually with full segment details.
 *
 * For simpler entry, consider using `flight import --add-new` with JSON input.
 * See `flight import --schema` for the simplified format.
 */
@Command(
    name = "add",
    description = ["Add a new flight to an investigation (for full control - consider 'flight import --add-new' for simpler entry)"],
)
class FlightAddCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var investigationSlug: String

    @Option(
        names = ["--link", "-l"],
        required = true,
        description = [
            "Specific flight booking URL (NOT a search page)",
            "Click through to flight details and use Share button or copy that URL",
        ],
    )
    lateinit var shareLink: String

    @Option(names = ["--source", "-s"], required = true, description = ["Data source (e.g., google_flights, kayak, amex)"])
    lateinit var source: String

    @Option(names = ["--price", "-p"], required = true, description = ["Price amount (> 0)"])
    var priceAmount: Double = 0.0

    @Option(names = ["--currency", "-c"], required = true, description = ["3-letter currency code (e.g., GBP, USD, EUR)"])
    lateinit var priceCurrency: String

    @Option(names = ["--market", "-m"], required = true, description = ["Price market (e.g., UK, US)"])
    lateinit var priceMarket: String

    @Option(
        names = ["--outbound"],
        required = true,
        description = [
            "Outbound segment JSON with structure:",
            "{depart_airport, arrive_airport, depart_time, arrive_time, duration_minutes, stops, legs: [{airline, airline_code, flight_number, depart_airport, arrive_airport, depart_time, arrive_time, duration_minutes}]}",
            "Times: ISO 8601 format (e.g., 2026-05-14T10:30:00Z or 2026-05-14T10:30:00+01:00)",
        ],
    )
    lateinit var outboundJson: String

    @Option(
        names = ["--return"],
        description = ["Return segment JSON (same format as --outbound, required for round_trip)"],
    )
    var returnJson: String? = null

    @Option(names = ["--type"], description = ["Trip type: round_trip or one_way"], defaultValue = "round_trip")
    var tripType: String = "round_trip"

    @Option(names = ["--structure"], description = ["Ticket structure: single or two_one_ways"], defaultValue = "single")
    var ticketStructure: String = "single"

    @Option(names = ["--notes"], description = ["Notes"])
    var notes: String? = null

    @Option(names = ["--tags"], description = ["Comma-separated tags"])
    var tags: String? = null

    @Option(names = ["--cabin"], description = ["Cabin class: economy, premium_economy, business, first"])
    var bookingClass: String? = null

    @Option(names = ["--aircraft"], description = ["Aircraft type (e.g., 777-300, A350)"])
    var aircraftType: String? = null

    @Option(names = ["--fare"], description = ["Fare brand (e.g., Business Light, Business Saver)"])
    var fareBrand: String? = null

    @Option(names = ["--disqualified"], description = ["Disqualification reason (e.g., overnight layover)"])
    var disqualified: String? = null

    override fun call(): Int {
        parent.parent.ensureDb()

        // Validate link first
        val (linkValid, linkError) = FlightValidator.validateShareLink(shareLink)
        if (!linkValid) {
            Output.error(linkError ?: "Invalid share link")
            return 1
        }

        // Verify investigation exists
        if (InvestigationQueries.getBySlug(investigationSlug) == null) {
            Output.error("Investigation '$investigationSlug' not found")
            return 1
        }

        // Check if flight with this link already exists
        if (FlightQueries.getByShareLink(shareLink) != null) {
            Output.error("Flight with this link already exists (links must be unique)")
            return 1
        }

        // Parse outbound segment to get origin/destination
        val outbound: FlightSegment
        try {
            outbound = Json.decodeFromString(outboundJson)
        } catch (e: Exception) {
            Output.error("Invalid outbound JSON: ${e.message}")
            println()
            println("Required segment structure:")
            println("  {")
            println("    \"depart_airport\": \"LHR\",")
            println("    \"arrive_airport\": \"NRT\",")
            println("    \"depart_time\": \"2026-05-14T10:30:00Z\",")
            println("    \"arrive_time\": \"2026-05-15T06:00:00Z\",")
            println("    \"duration_minutes\": 720,")
            println("    \"stops\": 0,")
            println("    \"legs\": [{ ... see --help for leg structure }]")
            println("  }")
            return 1
        }

        // Parse return if provided
        val returnSegment: FlightSegment? =
            returnJson?.let {
                try {
                    Json.decodeFromString(it)
                } catch (e: Exception) {
                    Output.error("Invalid return JSON: ${e.message}")
                    return 1
                }
            }

        // Check return is required for round_trip
        if (tripType == "round_trip" && returnSegment == null) {
            Output.error("Return segment required for round_trip (use --return or --type one_way)")
            return 1
        }

        // Enforce investigation constraints (airline, layover, trip duration)
        val investigation = InvestigationQueries.getBySlug(investigationSlug)!!
        val violations = FlightValidator.checkConstraints(outbound, returnSegment, investigation)
        if (violations.isNotEmpty()) {
            Output.error("Flight violates investigation constraints:")
            violations.forEach { v -> println("  ✗ [${v.code}] ${v.message}") }
            return 1
        }

        val id = FlightValidator.generateId(shareLink)
        val now = Instant.now().toString()

        val tagsJson = tags?.let { Output.encodeTags(it.split(",").map { t -> t.trim() }) }

        val dto =
            FlightDto(
                id = id,
                investigationSlug = investigationSlug,
                // Deprecated: keeping for backward compat, canonical link in price_history
                shareLink = shareLink,
                source = source,
                tripType = tripType,
                ticketStructure = ticketStructure,
                origin = outbound.departAirport,
                destination = outbound.arriveAirport,
                outboundJson = outboundJson,
                returnJson = returnJson,
                bookingClass = bookingClass,
                aircraftType = aircraftType,
                fareBrand = fareBrand,
                disqualified = disqualified,
                notes = notes,
                tags = tagsJson,
                capturedAt = now,
            )

        try {
            FlightQueries.create(dto)

            // Canonical: price + link in price_history
            PriceHistoryQueries.create(
                PriceHistoryDto(
                    flightId = id,
                    amount = priceAmount,
                    currency = priceCurrency.uppercase(),
                    sourceUrl = shareLink,
                    checkedAt = now,
                    priceMarket = priceMarket.uppercase(),
                ),
            )

            Output.success("Added flight: $id")
            println("  Price: ${Output.formatPrice(priceAmount, priceCurrency.uppercase())}")
            println("  Route: ${outbound.departAirport} → ${outbound.arriveAirport}")
            return 0
        } catch (e: Exception) {
            Output.error("Failed to add flight: ${e.message}")
            return 1
        }
    }
}

/**
 * List flights.
 */
@Command(
    name = "list",
    description = ["List flights for an investigation"],
)
class FlightListCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var investigationSlug: String

    @Option(names = ["--sort"], description = ["Sort by (price, date, route)"], defaultValue = "price")
    var sortBy: String = "price"

    @Option(names = ["--limit"], description = ["Limit number of results"])
    var limit: Int? = null

    @Option(names = ["--tag"], description = ["Filter by tag"])
    var tagFilter: String? = null

    @Option(names = ["--max-price"], description = ["Maximum price filter"])
    var maxPrice: Double? = null

    @Option(names = ["--fresh"], description = ["Show only fresh (non-stale) prices"])
    var freshOnly: Boolean = false

    @Option(names = ["--stale"], description = ["Show only stale prices"])
    var staleOnly: Boolean = false

    @Option(names = ["--json"], description = ["Output as JSON"])
    var jsonOutput: Boolean = false

    @Option(names = ["--prices"], description = ["Show price history summary (min/max/change)"])
    var showPrices: Boolean = false

    @Option(names = ["--history"], description = ["Show full price history per flight"])
    var showHistory: Boolean = false

    @Option(names = ["--report"], description = ["Detailed report with dates, days, routes, connections, prices, links"])
    var showReport: Boolean = false

    @Option(names = ["--all"], description = ["Show all flights including those violating constraints"])
    var showAll: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        // Verify investigation exists
        val investigation = InvestigationQueries.getBySlug(investigationSlug)
        if (investigation == null) {
            Output.error("Investigation '$investigationSlug' not found")
            return 1
        }

        var flights = FlightQueries.listWithPrices(investigationSlug)

        // Apply constraint filtering (default: hide violations)
        var hiddenCount = 0
        if (!showAll) {
            val (passing, violating) =
                flights.partition { fwp ->
                    try {
                        val outbound: FlightSegment = Json.decodeFromString(fwp.flight.outboundJson)
                        val returnSeg: FlightSegment? = fwp.flight.returnJson?.let { Json.decodeFromString(it) }
                        FlightValidator.checkConstraints(outbound, returnSeg, investigation).isEmpty()
                    } catch (_: Exception) {
                        true // can't parse → don't filter out
                    }
                }
            hiddenCount = violating.size
            flights = passing
        }

        // Apply filters
        if (tagFilter != null) {
            flights =
                flights.filter { flight ->
                    flight.tags?.let { Output.parseTags(it).contains(tagFilter) } ?: false
                }
        }

        if (maxPrice != null) {
            flights = flights.filter { it.priceAmount <= maxPrice!! }
        }

        // Filter by stale status
        if (freshOnly) {
            flights = flights.filter { !it.flight.stale }
        }
        if (staleOnly) {
            flights = flights.filter { it.flight.stale }
        }

        // Sort
        flights =
            when (sortBy) {
                "price" -> flights.sortedBy { it.priceAmount }
                "date" -> flights.sortedByDescending { it.capturedAt }
                "route" -> flights.sortedBy { "${it.origin}-${it.destination}" }
                else -> flights.sortedBy { it.priceAmount }
            }

        // Limit
        if (limit != null) {
            flights = flights.take(limit!!)
        }

        if (jsonOutput) {
            println(Output.toJson(flights))
        } else if (showPrices) {
            // Show with price history summary
            println("ID       │ Current   │ Min       │ Max       │ Change    │ Airline             │ Route   │ Depart")
            println("─────────┼───────────┼───────────┼───────────┼───────────┼─────────────────────┼─────────┼───────────")

            flights.forEach { f ->
                val history = PriceHistoryQueries.getByFlightId(f.id)
                val outbound = Output.parseSegment(f.outboundJson)
                val airline =
                    outbound
                        ?.legs
                        ?.firstOrNull()
                        ?.airline
                        ?.take(19) ?: "?"
                val departDate = outbound?.departTime?.take(10) ?: "?"

                val minPrice = history.minOfOrNull { it.amount } ?: f.priceAmount
                val maxPrice = history.maxOfOrNull { it.amount } ?: f.priceAmount
                val firstPrice = history.minByOrNull { it.checkedAt }?.amount ?: f.priceAmount
                val change = f.priceAmount - firstPrice
                val changeStr =
                    when {
                        change > 0 -> "↑ +${Output.formatPrice(change, f.priceCurrency)}"
                        change < 0 -> "↓ ${Output.formatPrice(change, f.priceCurrency)}"
                        else -> "—"
                    }

                println(
                    "${f.id.take(
                        8,
                    )} │ ${Output.formatPrice(
                        f.priceAmount,
                        f.priceCurrency,
                    ).padStart(
                        9,
                    )} │ ${Output.formatPrice(
                        minPrice,
                        f.priceCurrency,
                    ).padStart(
                        9,
                    )} │ ${Output.formatPrice(
                        maxPrice,
                        f.priceCurrency,
                    ).padStart(9)} │ ${changeStr.padStart(9)} │ ${airline.padEnd(19)} │ ${f.origin}→${f.destination} │ $departDate",
                )
            }

            // Summary
            println()
            val allHistory = flights.flatMap { PriceHistoryQueries.getByFlightId(it.id) }
            val pricesDown =
                flights.count { f ->
                    val history = PriceHistoryQueries.getByFlightId(f.id)
                    val first = history.minByOrNull { it.checkedAt }?.amount ?: f.priceAmount
                    f.priceAmount < first
                }
            val pricesUp =
                flights.count { f ->
                    val history = PriceHistoryQueries.getByFlightId(f.id)
                    val first = history.minByOrNull { it.checkedAt }?.amount ?: f.priceAmount
                    f.priceAmount > first
                }
            println(
                "${flights.size} flights │ ${allHistory.size} price observations │ $pricesDown↓ down │ $pricesUp↑ up │ ${flights.size - pricesDown - pricesUp} unchanged",
            )
        } else if (showHistory) {
            // Show full price history per flight
            flights.forEach { f ->
                val history = PriceHistoryQueries.getByFlightId(f.id)
                val outbound = Output.parseSegment(f.outboundJson)
                val airline = outbound?.legs?.firstOrNull()?.airline ?: "?"
                val departDate = outbound?.departTime?.take(10) ?: "?"

                println()
                println("${f.id.take(8)} │ $airline │ ${f.origin}→${f.destination} │ $departDate")
                println("─".repeat(70))

                if (history.isEmpty()) {
                    println("  No price history")
                } else {
                    var prevPrice: Double? = null
                    history.sortedBy { it.checkedAt }.forEach { price ->
                        val change =
                            if (prevPrice != null) {
                                val diff = price.amount - prevPrice!!
                                when {
                                    diff > 0 -> "↑ +${Output.formatPrice(diff, price.currency)}"
                                    diff < 0 -> "↓ ${Output.formatPrice(diff, price.currency)}"
                                    else -> "—"
                                }
                            } else {
                                "—"
                            }

                        val timestamp = price.checkedAt.take(16).replace("T", " ")
                        val sourceTag = if (price.source != "kraftwerker") " [${price.source}]" else ""
                        val urlTag = if (price.sourceUrl.isNotBlank()) " ${price.sourceUrl}" else ""
                        val idTag = if (price.id != null) "#${price.id} " else ""
                        println(
                            "  $idTag$timestamp │ ${Output.formatPrice(
                                price.amount,
                                price.currency,
                            ).padStart(9)} │ $change$sourceTag$urlTag",
                        )
                        prevPrice = price.amount
                    }
                }
            }

            // Summary
            println()
            val allHistory = flights.flatMap { PriceHistoryQueries.getByFlightId(it.id) }
            println("${flights.size} flights │ ${allHistory.size} total price observations")
        } else if (showReport) {
            // Detailed report
            flights.forEachIndexed { index, f ->
                val outbound = Output.parseSegment(f.outboundJson)
                val returnSeg = f.flight.returnJson?.let { Output.parseSegment(it) }
                val airline = outbound?.legs?.firstOrNull()?.airline ?: "?"
                val airlineCode = outbound?.legs?.firstOrNull()?.airlineCode ?: "?"
                val departDate = outbound?.departTime?.take(10) ?: "?"
                val returnDate = returnSeg?.departTime?.take(10)

                // Trip duration
                val days =
                    if (outbound != null && returnSeg != null) {
                        try {
                            val d1 = java.time.ZonedDateTime.parse(outbound.departTime)
                            val d2 = java.time.ZonedDateTime.parse(returnSeg.departTime)
                            java.time.Duration
                                .between(d1, d2)
                                .toDays()
                                .toInt()
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }

                // Route with stops
                val outStops = outbound?.stops ?: 0
                val retStops = returnSeg?.stops ?: 0
                val outRoute =
                    if (outStops == 0) {
                        "${f.origin}→${f.destination} (nonstop)"
                    } else {
                        val vias =
                            outbound
                                ?.legs
                                ?.drop(0)
                                ?.dropLast(1)
                                ?.map { it.arriveAirport } ?: emptyList()
                        if (vias.isNotEmpty()) {
                            "${f.origin}→${vias.joinToString("→")}→${f.destination}"
                        } else {
                            "${f.origin}→${f.destination} ($outStops stop)"
                        }
                    }
                val retRoute =
                    if (returnSeg == null) {
                        null
                    } else if (retStops == 0) {
                        "${f.destination}→${f.origin} (nonstop)"
                    } else {
                        val vias =
                            returnSeg.legs
                                .drop(0)
                                .dropLast(1)
                                .map { it.arriveAirport }
                        if (vias.isNotEmpty()) {
                            "${f.destination}→${vias.joinToString("→")}→${f.origin}"
                        } else {
                            "${f.destination}→${f.origin} ($retStops stop)"
                        }
                    }

                // Connection times
                val outLayovers = Output.formatLayovers(outbound)
                val retLayovers = returnSeg?.let { Output.formatLayovers(it) }

                // Links — show what's stored. Bad links = data needs fixing.
                // Prefer booking/share links from any price history entry, fall back to latest
                val allHistory = PriceHistoryQueries.getByFlightId(f.id)
                val bestLink =
                    allHistory
                        .map { it.sourceUrl }
                        .firstOrNull { it.contains("/flights/s/") || it.contains("/flights/booking") || it.contains("/book/flight") }
                        ?: allHistory.maxByOrNull { it.checkedAt }?.sourceUrl?.takeIf { it.isNotBlank() }
                        ?: f.flight.shareLink.orEmpty()
                val link = bestLink

                // Source of the latest price
                val latestEntry = allHistory.maxByOrNull { it.checkedAt }
                val priceSource = latestEntry?.source?.takeIf { it.isNotBlank() && it != "manual" }

                if (index > 0) println()
                val sourceLabel = if (priceSource != null) " via $priceSource" else ""
                println("${Output.formatPrice(f.priceAmount, f.priceCurrency)}$sourceLabel — $airline ($airlineCode)")
                if (f.flight.tripType == "round_trip" && days != null) {
                    println("  📅 $departDate → $returnDate ($days days)")
                } else if (f.flight.tripType == "one_way") {
                    println("  📅 $departDate (one-way)")
                } else {
                    println("  📅 $departDate")
                }
                println("  ✈️  Out: $outRoute")
                if (outLayovers.isNotBlank()) println("  ⏱  Out connections: $outLayovers")
                if (retRoute != null) {
                    println("  ✈️  Ret: $retRoute")
                    if (retLayovers != null && retLayovers.isNotBlank()) println("  ⏱  Ret connections: $retLayovers")
                }
                val isShareLink = link.contains("/flights/s/") || link.contains("/flights/booking") || link.contains("/book/flight")
                if (link.isBlank()) {
                    println("  ⚠️  No link")
                } else if (isShareLink) {
                    println("  🔗 $link")
                } else {
                    println("  ⚠️  $link (not a share link)")
                }
            }
            println()
            println("${flights.size} flights")
        } else {
            println(Output.formatFlightWithPriceTable(flights))
        }

        if (hiddenCount > 0) {
            println()
            println("⚠ $hiddenCount flights hidden (constraint violations). Use --all to show them.")
        }

        return 0
    }
}

/**
 * Show flight details.
 */
@Command(
    name = "show",
    description = ["Show flight details"],
)
class FlightShowCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Flight ID"])
    lateinit var id: String

    @Option(names = ["--json"], description = ["Output as JSON"])
    var jsonOutput: Boolean = false

    @Option(names = ["--history"], description = ["Show price history"])
    var showHistory: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        // Try exact match first, then prefix match
        val flight = FlightQueries.getById(id) ?: FlightQueries.getByIdPrefix(id)
        if (flight == null) {
            Output.error("Flight '$id' not found")
            return 1
        }

        // Get latest price info
        val latestPrice = PriceHistoryQueries.getLatest(flight.id)

        if (jsonOutput) {
            println(Output.toJson(flight))
        } else {
            // Parse segments for display
            val outbound: FlightSegment? =
                try {
                    Json.decodeFromString(flight.outboundJson)
                } catch (_: Exception) {
                    null
                }

            val returnSeg: FlightSegment? =
                flight.returnJson?.let {
                    try {
                        Json.decodeFromString(it)
                    } catch (_: Exception) {
                        null
                    }
                }

            println(Output.formatFlightDetail(flight, outbound, returnSeg, latestPrice))

            if (showHistory) {
                println("\nPrice History:")
                val history = PriceHistoryQueries.getByFlightId(flight.id)
                println(Output.formatPriceHistory(history))
            }
        }

        return 0
    }
}

/**
 * Delete a flight.
 */
@Command(
    name = "delete",
    description = ["Delete a flight"],
)
class FlightDeleteCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Flight ID"])
    lateinit var id: String

    override fun call(): Int {
        parent.parent.ensureDb()

        val flight = FlightQueries.getById(id) ?: FlightQueries.getByIdPrefix(id)
        if (flight == null) {
            Output.error("Flight '$id' not found")
            return 1
        }

        if (FlightQueries.delete(id)) {
            Output.success("Deleted flight: $id")
            return 0
        } else {
            Output.error("Failed to delete flight")
            return 1
        }
    }
}

/**
 * Update flight fields.
 */
@Command(
    name = "update",
    description = ["Update flight fields (notes, disqualified, cabin, aircraft, fare, outbound, return, link)"],
)
class FlightUpdateCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Flight ID (or prefix)"])
    lateinit var id: String

    @Option(names = ["--notes"], description = ["Update notes (use empty string to clear)"])
    var notes: String? = null

    @Option(names = ["--disqualified"], description = ["Set disqualification reason (use empty string to clear)"])
    var disqualified: String? = null

    @Option(names = ["--cabin"], description = ["Update cabin class (economy, premium_economy, business, first)"])
    var bookingClass: String? = null

    @Option(names = ["--aircraft"], description = ["Update aircraft type"])
    var aircraftType: String? = null

    @Option(names = ["--fare"], description = ["Update fare brand"])
    var fareBrand: String? = null

    @Option(names = ["--outbound"], description = ["Update outbound segment JSON"])
    var outboundJson: String? = null

    @Option(names = ["--return"], description = ["Update return segment JSON"])
    var returnJson: String? = null

    @Option(names = ["--link", "-l"], description = ["Update share link"])
    var shareLink: String? = null

    @Option(names = ["--source", "-s"], description = ["Update data source"])
    var source: String? = null

    @Option(names = ["--type"], description = ["Update trip type (round_trip, one_way)"])
    var tripType: String? = null

    @Option(names = ["--structure"], description = ["Update ticket structure (single, two_one_ways)"])
    var ticketStructure: String? = null

    override fun call(): Int {
        parent.parent.ensureDb()

        val flight = FlightQueries.getById(id) ?: FlightQueries.getByIdPrefix(id)
        if (flight == null) {
            Output.error("Flight '$id' not found")
            return 1
        }

        // Build updated DTO with only changed fields
        val updated =
            flight.copy(
                notes = notes ?: flight.notes,
                disqualified = if (disqualified == "") null else (disqualified ?: flight.disqualified),
                bookingClass = bookingClass ?: flight.bookingClass,
                aircraftType = aircraftType ?: flight.aircraftType,
                fareBrand = fareBrand ?: flight.fareBrand,
                outboundJson = outboundJson ?: flight.outboundJson,
                returnJson = if (returnJson == "") null else (returnJson ?: flight.returnJson),
                shareLink = shareLink ?: flight.shareLink,
                source = source ?: flight.source,
                tripType = tripType ?: flight.tripType,
                ticketStructure = ticketStructure ?: flight.ticketStructure,
            )

        // Check if anything actually changed
        if (updated == flight) {
            println("No changes specified")
            return 0
        }

        val result = FlightQueries.update(updated)
        if (result != null) {
            val changes = mutableListOf<String>()
            if (notes != null) changes.add("notes")
            if (disqualified != null) changes.add("disqualified")
            if (bookingClass != null) changes.add("cabin")
            if (aircraftType != null) changes.add("aircraft")
            if (fareBrand != null) changes.add("fare")
            if (outboundJson != null) changes.add("outbound")
            if (returnJson != null) changes.add("return")
            if (shareLink != null) changes.add("link")
            if (source != null) changes.add("source")
            if (tripType != null) changes.add("type")
            if (ticketStructure != null) changes.add("structure")

            Output.success("Updated flight ${flight.id}: ${changes.joinToString(", ")}")
            return 0
        } else {
            Output.error("Failed to update flight")
            return 1
        }
    }
}

/**
 * Tag a flight.
 */
@Command(
    name = "tag",
    description = ["Add a tag to a flight"],
)
class FlightTagCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Flight ID"])
    lateinit var id: String

    @Parameters(index = "1", description = ["Tag to add"])
    lateinit var tag: String

    override fun call(): Int {
        parent.parent.ensureDb()

        val flight = FlightQueries.getById(id) ?: FlightQueries.getByIdPrefix(id)
        if (flight == null) {
            Output.error("Flight '$id' not found")
            return 1
        }

        val existingTags = flight.tags?.let { Output.parseTags(it) } ?: emptyList()

        if (tag in existingTags) {
            Output.warn("Flight already has tag '$tag'")
            return 0
        }

        val newTags = existingTags + tag
        val updatedFlight = flight.copy(tags = Output.encodeTags(newTags))

        if (FlightQueries.update(updatedFlight) != null) {
            Output.success("Added tag '$tag' to flight $id")
            return 0
        } else {
            Output.error("Failed to update flight")
            return 1
        }
    }
}

/**
 * Remove a tag from a flight.
 */
@Command(
    name = "untag",
    description = ["Remove a tag from a flight"],
)
class FlightUntagCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Flight ID"])
    lateinit var id: String

    @Parameters(index = "1", description = ["Tag to remove"])
    lateinit var tag: String

    override fun call(): Int {
        parent.parent.ensureDb()

        val flight = FlightQueries.getById(id) ?: FlightQueries.getByIdPrefix(id)
        if (flight == null) {
            Output.error("Flight '$id' not found")
            return 1
        }

        val existingTags = flight.tags?.let { Output.parseTags(it) } ?: emptyList()

        if (tag !in existingTags) {
            Output.warn("Flight doesn't have tag '$tag'")
            return 0
        }

        val newTags = existingTags - tag
        val updatedFlight =
            flight.copy(
                tags = if (newTags.isEmpty()) null else Output.encodeTags(newTags),
            )

        if (FlightQueries.update(updatedFlight) != null) {
            Output.success("Removed tag '$tag' from flight $id")
            return 0
        } else {
            Output.error("Failed to update flight")
            return 1
        }
    }
}

/**
 * Update flight price.
 */
@Command(
    name = "price",
    description = ["Update flight price"],
)
class FlightPriceCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Flight ID"])
    lateinit var id: String

    @Option(names = ["--amount", "-a"], required = true, description = ["New price amount"])
    var amount: Double = 0.0

    @Option(names = ["--currency", "-c"], description = ["Price currency (defaults to existing)"])
    var currency: String? = null

    @Option(names = ["--link", "-l"], description = ["Source URL where this price was observed (required for new prices)"])
    var link: String? = null

    @Option(names = ["--market", "-m"], description = ["Price market (e.g., UK, US)"])
    var market: String? = null

    @Option(names = ["--source", "-s"], description = ["Booking source (e.g., airline_direct, checknfly, expedia, google_flights)"])
    var source: String? = null

    @Option(names = ["--force", "-f"], description = ["Bypass sanity warnings (not hard errors)"])
    var force: Boolean = false

    companion object {
        // Minimum believable one-way business class price (GBP)
        private const val MIN_BUSINESS_OW_GBP = 800.0

        // Minimum believable round-trip business class price (GBP)
        private const val MIN_BUSINESS_RT_GBP = 1500.0

        // Maximum believable price (GBP) — sanity ceiling
        private const val MAX_PRICE_GBP = 30000.0

        // Percentage deviation from previous price that triggers a warning
        private const val LARGE_DEVIATION_THRESHOLD = 0.50
    }

    override fun call(): Int {
        parent.parent.ensureDb()

        val flightWithPrice = FlightQueries.getWithPrice(id) ?: FlightQueries.getWithPriceByPrefix(id)
        if (flightWithPrice == null) {
            Output.error("Flight '$id' not found (or no price history)")
            return 1
        }

        val newCurrency = (currency ?: flightWithPrice.priceCurrency).uppercase()
        val newMarket = market ?: flightWithPrice.priceMarket
        val sourceUrl = link ?: flightWithPrice.sourceUrl

        // === VALIDATION: Source URL ===
        if (link != null) {
            val (linkValid, linkError) = FlightValidator.validateShareLink(link!!)
            if (!linkValid) {
                // Check if it's a search URL warning (soft) vs hard error
                val searchWarning = FlightValidator.checkSearchUrl(link!!)
                if (searchWarning != null && force) {
                    // --force bypasses search URL warnings
                    Output.warn("Search URL accepted with --force: $searchWarning")
                } else {
                    Output.error("Invalid source URL: $linkError")
                    return 1
                }
            }
        } else if (sourceUrl.isBlank()) {
            Output.warn("No source URL provided. Consider using --link to track where this price came from.")
        }

        // === VALIDATION: Price amount ===
        if (amount <= 0) {
            Output.error("Price must be positive (got $amount)")
            return 1
        }

        // Price floor checks (hard errors — these are never valid)
        val isRT = flightWithPrice.flight.tripType == "round_trip"
        val isBusiness =
            flightWithPrice.flight.bookingClass
                ?.lowercase()
                ?.contains("business") == true
        if (newCurrency == "GBP" && isBusiness) {
            val floor = if (isRT) MIN_BUSINESS_RT_GBP else MIN_BUSINESS_OW_GBP
            val tripLabel = if (isRT) "round-trip" else "one-way"
            if (amount < floor) {
                Output.error(
                    "£${String.format("%.0f", amount)} is below the minimum believable " +
                        "business class $tripLabel price (£${String.format("%.0f", floor)}). " +
                        "This is likely an economy price or a data entry error.",
                )
                return 1
            }
        }

        // Price ceiling check (hard error)
        if (newCurrency == "GBP" && amount > MAX_PRICE_GBP) {
            Output.error(
                "£${String.format("%.0f", amount)} exceeds maximum believable price " +
                    "(£${String.format("%.0f", MAX_PRICE_GBP)}). Check the currency and amount.",
            )
            return 1
        }

        // Large deviation warning (soft — bypassable with --force)
        val oldAmount = flightWithPrice.priceAmount
        if (oldAmount > 0 && !force) {
            val deviation = kotlin.math.abs(amount - oldAmount) / oldAmount
            if (deviation > LARGE_DEVIATION_THRESHOLD) {
                val direction = if (amount > oldAmount) "increase" else "decrease"
                val pctStr = String.format("%.0f%%", deviation * 100)
                Output.warn(
                    "Price $direction of $pctStr " +
                        "(${Output.formatPrice(oldAmount, flightWithPrice.priceCurrency)} → " +
                        "${Output.formatPrice(amount, newCurrency)}). " +
                        "Use --force to confirm this is correct.",
                )
                return 1
            }
        }

        val oldPrice = Output.formatPrice(flightWithPrice.priceAmount, flightWithPrice.priceCurrency)
        val newPrice = Output.formatPrice(amount, newCurrency)
        val now = Instant.now().toString()

        // Record in price history (canonical location)
        val priceSource = source ?: "manual"
        PriceHistoryQueries.create(
            PriceHistoryDto(
                flightId = flightWithPrice.flight.id,
                amount = amount,
                currency = newCurrency,
                sourceUrl = sourceUrl,
                checkedAt = now,
                priceMarket = newMarket,
                source = priceSource,
            ),
        )

        // Update denormalized price on flight record (deprecated but kept for compat)
        // Also clear stale flag since we just verified the price
        FlightQueries.update(
            flightWithPrice.flight.copy(
                priceAmount = amount,
                priceCurrency = newCurrency,
                priceCheckedAt = now,
                stale = false,
            ),
        )

        val change = amount - flightWithPrice.priceAmount
        val changeStr =
            when {
                change > 0 -> "+${Output.formatPrice(change, newCurrency)}"
                change < 0 -> "-${Output.formatPrice(-change, newCurrency)}"
                else -> "no change"
            }

        Output.success("Updated price: $oldPrice → $newPrice ($changeStr)")
        if (flightWithPrice.flight.stale) {
            println("  ✓ Cleared stale flag")
        }
        return 0
    }
}

/**
 * Mark flights as stale (needing price refresh).
 */
@Command(
    name = "mark-stale",
    description = ["Mark flights as stale (needing price refresh)"],
)
class FlightMarkStaleCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", arity = "0..1", description = ["Investigation slug (required with --all)"])
    var slug: String? = null

    @Option(names = ["--all", "-a"], description = ["Mark all flights in investigation as stale"])
    var markAll: Boolean = false

    @Option(names = ["--older-than"], description = ["Mark flights not checked in N hours as stale"])
    var olderThanHours: Int? = null

    @Option(names = ["--id"], description = ["Mark specific flight ID as stale"])
    var flightId: String? = null

    @Option(names = ["--fresh"], description = ["Mark as fresh instead of stale (clear stale flag)"])
    var markFresh: Boolean = false

    @Option(names = ["--dry-run"], description = ["Show what would be marked without making changes"])
    var dryRun: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        val staleValue = if (markFresh) false else true
        val action = if (markFresh) "fresh" else "stale"

        // Mark specific flight
        if (flightId != null) {
            val flight = FlightQueries.getById(flightId!!) ?: FlightQueries.getByIdPrefix(flightId!!)
            if (flight == null) {
                Output.error("Flight '$flightId' not found")
                return 1
            }
            if (dryRun) {
                println("[DRY RUN] Would mark ${flight.id} as $action")
            } else {
                FlightQueries.update(flight.copy(stale = staleValue))
                Output.success("Marked flight ${flight.id} as $action")
            }
            return 0
        }

        // Need slug for bulk operations
        if (slug == null) {
            Output.error("Investigation slug required (or use --id for single flight)")
            return 1
        }

        val inv = InvestigationQueries.getBySlug(slug!!)
        if (inv == null) {
            Output.error("Investigation '$slug' not found")
            return 1
        }

        val flights = FlightQueries.listByInvestigation(slug!!)
        if (flights.isEmpty()) {
            println("No flights found in '$slug'")
            return 0
        }

        // Filter by age if specified
        val toMark =
            if (olderThanHours != null) {
                val cutoff =
                    java.time.Instant
                        .now()
                        .minusSeconds(olderThanHours!!.toLong() * 3600)
                flights.filter {
                    try {
                        java.time.Instant
                            .parse(it.priceCheckedAt)
                            .isBefore(cutoff)
                    } catch (_: Exception) {
                        true // Mark if we can't parse the date
                    }
                }
            } else if (markAll) {
                flights
            } else {
                Output.error("Specify --all, --older-than <hours>, or --id <flight-id>")
                return 1
            }

        if (toMark.isEmpty()) {
            println("No flights match the criteria")
            return 0
        }

        if (dryRun) {
            println("[DRY RUN] Would mark ${toMark.size} flights as $action:")
            toMark.take(10).forEach { println("  ${it.id}") }
            if (toMark.size > 10) println("  ... and ${toMark.size - 10} more")
        } else {
            var count = 0
            toMark.forEach { flight ->
                FlightQueries.update(flight.copy(stale = staleValue))
                count++
            }
            Output.success("Marked $count flights as $action")
        }

        return 0
    }
}

/**
 * Show price staleness status for a flight.
 */
@Command(
    name = "stale",
    description = ["Show price staleness status for a flight"],
)
class FlightStaleCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Flight ID (or prefix)"])
    lateinit var id: String

    @Option(names = ["--hours"], description = ["Consider stale after N hours"], defaultValue = "24")
    var staleHours: Int = 24

    override fun call(): Int {
        parent.parent.ensureDb()

        val flightWithPrice = FlightQueries.getWithPrice(id) ?: FlightQueries.getWithPriceByPrefix(id)
        if (flightWithPrice == null) {
            Output.error("Flight '$id' not found (or no price history)")
            return 1
        }

        val flight = flightWithPrice.flight
        val lastChecked = java.time.Instant.parse(flightWithPrice.priceCheckedAt)
        val cutoff =
            java.time.Instant
                .now()
                .minusSeconds(staleHours.toLong() * 3600)
        val isTimeStale = lastChecked.isBefore(cutoff)
        val age =
            java.time.Duration
                .between(lastChecked, java.time.Instant.now())
                .toHours()

        println("Flight: ${flightWithPrice.id}")
        println("  Last checked: ${flightWithPrice.priceCheckedAt} (${age}h ago)")
        println("  Stale flag: ${if (flight.stale) "YES" else "no"}")
        println("  Time-based: ${if (isTimeStale) "STALE (>${staleHours}h)" else "Current"}")
        println("  Latest price: ${Output.formatPrice(flightWithPrice.priceAmount, flightWithPrice.priceCurrency)}")

        return 0
    }
}

/**
 * Show price history across all flights in an investigation.
 */
@Command(
    name = "history",
    description = ["Show price history for all flights"],
)
class FlightHistoryCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["--by-flight"], description = ["Group prices by flight"])
    var byFlight: Boolean = false

    @Option(names = ["--by-source"], description = ["Group prices by source agent"])
    var bySource: Boolean = false

    @Option(names = ["--limit"], description = ["Limit entries per flight"], defaultValue = "10")
    var limit: Int = 10

    @Option(names = ["--json"], description = ["Output as JSON"])
    var jsonOutput: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        val flights = FlightQueries.listWithPrices(slug)
        if (flights.isEmpty()) {
            Output.warn("No flights in '$slug'")
            return 0
        }

        // Get all price history for the investigation
        val allHistory = PriceHistoryQueries.getByInvestigation(slug)

        if (jsonOutput) {
            println(Output.toJson(allHistory))
            return 0
        }

        if (byFlight) {
            // Group by flight
            val byFlightId = allHistory.groupBy { it.flightId }

            flights.sortedBy { it.priceAmount }.forEach { flight ->
                val history = byFlightId[flight.id] ?: return@forEach
                val outbound = Output.parseSegment(flight.outboundJson)
                val airline = outbound?.legs?.firstOrNull()?.airline ?: "?"

                println()
                println("${flight.id.take(8)} | $airline | ${flight.origin}→${flight.destination}")
                println("─".repeat(60))

                var prevPrice: Double? = null
                history.sortedByDescending { it.checkedAt }.take(limit).reversed().forEach { price ->
                    val change =
                        if (prevPrice != null) {
                            val diff = price.amount - prevPrice!!
                            when {
                                diff > 0 -> "↑ +${Output.formatPrice(diff, price.currency)}"
                                diff < 0 -> "↓ ${Output.formatPrice(diff, price.currency)}"
                                else -> "—"
                            }
                        } else {
                            "—"
                        }

                    val timestamp = price.checkedAt.take(16).replace("T", " ")
                    val sourceTag = if (price.source != "kraftwerker") " [${price.source}]" else ""
                    println("  $timestamp │ ${Output.formatPrice(price.amount, price.currency).padStart(9)} │ $change$sourceTag")
                    prevPrice = price.amount
                }
            }
        } else if (bySource) {
            // Group by source
            val bySourceName = allHistory.groupBy { it.source }

            bySourceName.forEach { (source, prices) ->
                println()
                println("Source: $source (${prices.size} observations)")
                println("─".repeat(60))

                val uniqueFlights = prices.map { it.flightId }.distinct().size
                val minPrice = prices.minOfOrNull { it.amount } ?: 0.0
                val maxPrice = prices.maxOfOrNull { it.amount } ?: 0.0
                val avgPrice = prices.map { it.amount }.average()
                val currency = prices.firstOrNull()?.currency ?: "GBP"

                println("  Flights checked: $uniqueFlights")
                println("  Price range: ${Output.formatPrice(minPrice, currency)} - ${Output.formatPrice(maxPrice, currency)}")
                println("  Average: ${Output.formatPrice(avgPrice, currency)}")

                // Recent activity
                val recent = prices.sortedByDescending { it.checkedAt }.take(5)
                println("  Recent:")
                recent.forEach { price ->
                    val flight = flights.find { it.id == price.flightId }
                    val route = flight?.let { "${it.origin}→${it.destination}" } ?: "?"
                    val timestamp = price.checkedAt.take(16).replace("T", " ")
                    println("    $timestamp │ ${Output.formatPrice(price.amount, price.currency)} │ $route")
                }
            }
        } else {
            // Chronological view - all prices sorted by time
            println("Price History for $slug (${allHistory.size} observations)")
            println("─".repeat(80))

            allHistory.sortedByDescending { it.checkedAt }.take(limit * flights.size).forEach { price ->
                val flight = flights.find { it.id == price.flightId }
                val outbound = flight?.let { Output.parseSegment(it.outboundJson) }
                val airline =
                    outbound
                        ?.legs
                        ?.firstOrNull()
                        ?.airline
                        ?.take(15) ?: "?"
                val route = flight?.let { "${it.origin}→${it.destination}" } ?: "?"
                val timestamp = price.checkedAt.take(16).replace("T", " ")
                val sourceTag = if (price.source != "kraftwerker") " [${price.source}]" else ""

                println(
                    "$timestamp │ ${Output.formatPrice(
                        price.amount,
                        price.currency,
                    ).padStart(9)} │ $route │ ${airline.padEnd(15)}$sourceTag",
                )
            }
        }

        // Summary
        println()
        println("Summary:")
        println("  Total flights: ${flights.size}")
        println("  Total price observations: ${allHistory.size}")
        val sources = allHistory.map { it.source }.distinct()
        if (sources.size > 1) {
            println("  Sources: ${sources.joinToString(", ")}")
        }

        return 0
    }
}

/**
 * List flights needing price refresh.
 */
@Command(
    name = "refresh",
    description = ["List flights needing price refresh"],
)
class FlightRefreshCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["--hours"], description = ["Consider stale after N hours"], defaultValue = "24")
    var staleHours: Int = 24

    @Option(names = ["--links"], description = ["Output only share links (for scripting)"])
    var linksOnly: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        val flightsWithPrices = FlightQueries.listWithPrices(slug)
        if (flightsWithPrices.isEmpty()) {
            if (!linksOnly) Output.warn("No flights with prices in '$slug'")
            return 0
        }

        // Get flights older than staleHours
        val cutoff =
            java.time.Instant
                .now()
                .minusSeconds(staleHours.toLong() * 3600)
        val needsRefresh =
            flightsWithPrices.filter { f ->
                val lastChecked = java.time.Instant.parse(f.priceCheckedAt)
                lastChecked.isBefore(cutoff)
            }

        if (linksOnly) {
            needsRefresh.forEach { println(it.shareLink) }
        } else {
            if (needsRefresh.isEmpty()) {
                println("All ${flightsWithPrices.size} flights up to date")
            } else {
                println("Flights needing refresh (${needsRefresh.size}/${flightsWithPrices.size}):")
                println()
                needsRefresh.forEach { f ->
                    val age =
                        java.time.Duration
                            .between(
                                java.time.Instant.parse(f.priceCheckedAt),
                                java.time.Instant.now(),
                            ).toHours()
                    println("${f.id.take(8)} | ${age}h ago | ${f.shareLink}")
                }
            }
        }

        return 0
    }
}

/**
 * Generate a task list for refreshing stale flight prices.
 * Groups flights by source/airline and provides direct links.
 */
@Command(
    name = "tasks",
    description = ["Generate task list for refreshing stale flight prices"],
)
class FlightTasksCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["--all"], description = ["Include all flights, not just stale ones"])
    var includeAll: Boolean = false

    @Option(names = ["--by-source"], description = ["Group by source website"])
    var bySource: Boolean = false

    @Option(names = ["--limit"], description = ["Limit number of tasks"])
    var limit: Int? = null

    override fun call(): Int {
        parent.parent.ensureDb()

        val inv = InvestigationQueries.getBySlug(slug)
        if (inv == null) {
            Output.error("Investigation '$slug' not found")
            return 1
        }

        val allFlights = FlightQueries.listWithPrices(slug)
        val flights = if (includeAll) allFlights else allFlights.filter { it.flight.stale }

        if (flights.isEmpty()) {
            if (includeAll) {
                println("No flights in '$slug'")
            } else {
                println("✓ No stale flights in '$slug' (${allFlights.size} total)")
            }
            return 0
        }

        println("PRICE CHECK TASKS")
        println("=================")
        println()
        println("${flights.size} flights need price verification" + if (!includeAll) " (stale)" else "")
        println()

        // Group by source domain
        val byDomain =
            flights
                .groupBy { flight ->
                    try {
                        java.net
                            .URI(flight.sourceUrl)
                            .host
                            ?.replace("www.", "") ?: "unknown"
                    } catch (_: Exception) {
                        "unknown"
                    }
                }.toSortedMap()

        for ((domain, domainFlights) in byDomain) {
            val limited = limit?.let { domainFlights.take(it) } ?: domainFlights
            println("## $domain (${domainFlights.size} flights)")
            println()

            // Group by airline within domain
            val byAirline =
                limited
                    .groupBy { flight ->
                        val segment = Output.parseSegment(flight.outboundJson)
                        segment?.legs?.firstOrNull()?.airline ?: "Unknown"
                    }.toSortedMap()

            for ((airline, airlineFlights) in byAirline) {
                println("### $airline")
                for (flight in airlineFlights) {
                    val segment = Output.parseSegment(flight.outboundJson)
                    val departDate = segment?.departTime?.take(10) ?: "?"
                    val route = "${flight.origin}→${flight.destination}"
                    val price = Output.formatPrice(flight.priceAmount, flight.priceCurrency)
                    println("  - [$departDate] $route $price")
                    println("    ${flight.sourceUrl}")
                    println("    Update: clawfare flight price ${flight.id.take(8)} --amount <NEW_PRICE> --link <URL>")
                    println()
                }
            }
            println()
        }

        println("WORKFLOW")
        println("--------")
        println("1. Open each link above")
        println("2. Note the current price")
        println("3. Run: clawfare flight price <ID> --amount <PRICE>")
        println("4. If flight unavailable: clawfare flight update <ID> --disqualified 'no longer available'")
        println()

        return 0
    }
}

/**
 * Analyze coverage gaps based on investigation constraints.
 * Shows what date/airline combinations are missing.
 */
@Command(
    name = "coverage",
    description = ["Analyze coverage gaps - find missing date/airline combinations"],
)
class FlightCoverageCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["--verbose", "-v"], description = ["Show detailed breakdown"])
    var verbose: Boolean = false

    // Airline groups for LHR→TYO searches (1 stop max)
    // Note: TAP Portugal doesn't fly to Asia, US carriers typically need 2+ stops
    private val airlineGroups =
        mapOf(
            "European" to
                listOf(
                    "BA" to "British Airways",
                    "LH" to "Lufthansa",
                    "AF" to "Air France",
                    "KL" to "KLM",
                    "LX" to "SWISS",
                    "SK" to "SAS",
                    "AY" to "Finnair",
                    "AZ" to "ITA Airways",
                    "OS" to "Austrian",
                    "LO" to "LOT Polish",
                    // "TP" to "TAP Portugal",  // No Asia routes
                ),
            "Japanese" to
                listOf(
                    "JL" to "Japan Airlines",
                    "NH" to "ANA",
                ),
            "Korean" to
                listOf(
                    "KE" to "Korean Air",
                    "OZ" to "Asiana",
                ),
            // American carriers typically require 2+ stops LHR→TYO
            // "American" to listOf(
            //     "AA" to "American Airlines",
            //     "UA" to "United",
            //     "DL" to "Delta",
            // ),
        )

    override fun call(): Int {
        parent.parent.ensureDb()

        val inv = InvestigationQueries.getBySlug(slug)
        if (inv == null) {
            Output.error("Investigation '$slug' not found")
            return 1
        }

        val flights = FlightQueries.listByInvestigation(slug)
        val flightsWithPrices = FlightQueries.listWithPrices(slug)

        println("COVERAGE ANALYSIS: $slug")
        println("=".repeat(50))
        println()

        // Print constraints
        println("CONSTRAINTS")
        println("-----------")
        println("  Route: ${inv.origin} → ${inv.destination}")
        println("  Depart: ${inv.departStart} to ${inv.departEnd}")
        inv.returnStart?.let { println("  Return: $it to ${inv.returnEnd}") }
        println("  Cabin: ${inv.cabinClass}")
        println("  Max stops: ${inv.maxStops}")
        inv.minTripDays?.let { println("  Trip length: $it-${inv.maxTripDays} days") }
        inv.mustIncludeDate?.let { println("  Must include: $it") }
        inv.maxLayoverMinutes?.let { println("  Max layover: ${it / 60}h ${it % 60}m") }
        println()

        // Analyze what we have
        val flightsByDepartDate =
            flights.groupBy { flight ->
                val segment = Output.parseSegment(flight.outboundJson)
                segment?.departTime?.take(10) ?: "unknown"
            }

        val flightsByAirline =
            flights.groupBy { flight ->
                val segment = Output.parseSegment(flight.outboundJson)
                segment?.legs?.firstOrNull()?.airlineCode ?: "??"
            }

        // Generate all valid depart dates
        val departDates = generateDateRange(inv.departStart, inv.departEnd)

        println("CURRENT COVERAGE")
        println("----------------")
        println("  Total flights: ${flights.size}")
        println("  Departure dates covered: ${flightsByDepartDate.keys.filter { it != "unknown" }.size}/${departDates.size}")
        println("  Airlines represented: ${flightsByAirline.keys.size}")
        println()

        // Find gaps
        println("COVERAGE GAPS")
        println("-------------")
        println()

        val allAirlines = airlineGroups.values.flatten()
        val missingByGroup = mutableMapOf<String, MutableList<String>>()

        for ((group, airlines) in airlineGroups) {
            val missing = mutableListOf<String>()
            for ((code, name) in airlines) {
                val hasFlights = flightsByAirline.containsKey(code)
                if (!hasFlights) {
                    missing.add("$name ($code)")
                }
            }
            if (missing.isNotEmpty()) {
                missingByGroup[group] = missing
            }
        }

        if (missingByGroup.isEmpty()) {
            println("  ✓ All airline groups have coverage")
        } else {
            for ((group, missing) in missingByGroup) {
                println("  $group airlines missing:")
                missing.forEach { println("    - $it") }
            }
        }
        println()

        // Check date coverage
        val missingDates =
            departDates.filter { date ->
                !flightsByDepartDate.containsKey(date)
            }

        if (missingDates.isEmpty()) {
            println("  ✓ All departure dates have coverage")
        } else {
            println("  Missing departure dates:")
            missingDates.forEach { println("    - $it") }
        }
        println()

        // Generate search suggestions
        println("SUGGESTED SEARCHES")
        println("------------------")
        println()

        if (missingByGroup.isNotEmpty() || missingDates.isNotEmpty()) {
            println("Google Flights searches to fill gaps:")
            println()

            // Suggest searches for missing airlines
            for ((group, missing) in missingByGroup) {
                for (airline in missing.take(3)) {
                    val code = airline.substringAfter("(").substringBefore(")")
                    println("  $airline:")
                    println("    https://www.google.com/travel/flights?q=${inv.origin}+${inv.destination}+${inv.cabinClass}+$code")
                    println("    Search: ${inv.origin} to ${inv.destination}, ${inv.departStart} - ${inv.departEnd}")
                    println("    Filter: Business class, $airline, 1 stop max")
                    println()
                }
            }

            // Suggest date-specific searches
            if (missingDates.isNotEmpty()) {
                println("  Dates needing coverage:")
                for (date in missingDates.take(5)) {
                    println("    $date: Search all preferred airlines")
                }
            }
        } else {
            println("  ✓ Good coverage! Consider:")
            println("    - Checking for price drops on existing flights")
            println("    - Looking at alternative airports (LGW, LCY)")
            println("    - Checking direct airline websites for better prices")
        }
        println()

        // Trip combination analysis
        if (inv.minTripDays != null && inv.maxTripDays != null && inv.mustIncludeDate != null) {
            println("TRIP COMBINATIONS")
            println("-----------------")
            analyzeTrips(inv, flightsWithPrices)
        }

        return 0
    }

    private fun generateDateRange(
        start: String,
        end: String,
    ): List<String> {
        val dates = mutableListOf<String>()
        var current = java.time.LocalDate.parse(start)
        val endDate = java.time.LocalDate.parse(end)
        while (!current.isAfter(endDate)) {
            dates.add(current.toString())
            current = current.plusDays(1)
        }
        return dates
    }

    private fun analyzeTrips(
        inv: InvestigationDto,
        flights: List<FlightWithPrice>,
    ) {
        val mustInclude = java.time.LocalDate.parse(inv.mustIncludeDate!!)
        val minDays = inv.minTripDays!!
        val maxDays = inv.maxTripDays!!

        // For round trips, analyze valid combinations
        val roundTrips = flights.filter { it.tripType == "round_trip" }
        val oneWayOut = flights.filter { it.tripType == "one_way" && it.origin == inv.origin }
        val oneWayBack = flights.filter { it.tripType == "one_way" && it.destination.startsWith(inv.origin.take(2)) }

        println()
        println("  Round-trip options: ${roundTrips.size}")
        println("  One-way outbound: ${oneWayOut.size}")
        println("  One-way return: ${oneWayBack.size}")
        println()

        // Find cheapest valid round-trip
        val validRoundTrips =
            roundTrips.filter { flight ->
                val outbound = Output.parseSegment(flight.outboundJson)
                val returnSeg = flight.returnJson?.let { Output.parseSegment(it) }
                if (outbound == null || returnSeg == null) return@filter false

                val departDate = java.time.LocalDate.parse(outbound.departTime.take(10))
                val returnDate = java.time.LocalDate.parse(returnSeg.departTime.take(10))
                val tripDays =
                    java.time.temporal.ChronoUnit.DAYS
                        .between(departDate, returnDate)
                        .toInt()

                tripDays in minDays..maxDays &&
                    !departDate.isAfter(mustInclude) &&
                    !returnDate.isBefore(mustInclude)
            }

        if (validRoundTrips.isNotEmpty()) {
            val cheapest = validRoundTrips.minByOrNull { it.priceAmount }!!
            println("  Cheapest valid round-trip: ${Output.formatPrice(cheapest.priceAmount, cheapest.priceCurrency)}")
            println("    Flight: ${cheapest.id.take(8)}")
        }

        // Find cheapest valid two-one-way combination
        if (oneWayOut.isNotEmpty() && oneWayBack.isNotEmpty()) {
            var cheapestCombo: Pair<FlightWithPrice, FlightWithPrice>? = null
            var cheapestTotal = Double.MAX_VALUE

            for (out in oneWayOut) {
                val outSeg = Output.parseSegment(out.outboundJson) ?: continue
                val outDate = java.time.LocalDate.parse(outSeg.departTime.take(10))
                if (outDate.isAfter(mustInclude)) continue

                for (back in oneWayBack) {
                    val backSeg = Output.parseSegment(back.outboundJson) ?: continue
                    val backDate = java.time.LocalDate.parse(backSeg.departTime.take(10))
                    if (backDate.isBefore(mustInclude)) continue

                    val tripDays =
                        java.time.temporal.ChronoUnit.DAYS
                            .between(outDate, backDate)
                            .toInt()
                    if (tripDays !in minDays..maxDays) continue

                    val total = out.priceAmount + back.priceAmount
                    if (total < cheapestTotal) {
                        cheapestTotal = total
                        cheapestCombo = out to back
                    }
                }
            }

            if (cheapestCombo != null) {
                println("  Cheapest valid two-one-way: ${Output.formatPrice(cheapestTotal, "GBP")}")
                println(
                    "    Out: ${cheapestCombo.first.id.take(
                        8,
                    )} (${Output.formatPrice(cheapestCombo.first.priceAmount, cheapestCombo.first.priceCurrency)})",
                )
                println(
                    "    Back: ${cheapestCombo.second.id.take(
                        8,
                    )} (${Output.formatPrice(cheapestCombo.second.priceAmount, cheapestCombo.second.priceCurrency)})",
                )
            }
        }
    }
}

/**
 * Validate flights in an investigation.
 */
@Command(
    name = "validate",
    description = ["Validate flights in an investigation"],
)
class FlightValidateCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["--fix"], description = ["Attempt to fix issues"])
    var fix: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        val investigation = InvestigationQueries.getBySlug(slug)
        if (investigation == null) {
            Output.error("Investigation '$slug' not found")
            return 1
        }

        val flights = FlightQueries.listByInvestigation(slug)
        if (flights.isEmpty()) {
            println("No flights to validate")
            return 0
        }

        var errors = 0
        var warnings = 0

        flights.forEach { f ->
            // Check required fields
            if (f.shareLink.isNullOrBlank()) {
                Output.error("[${f.id}] Missing share link")
                errors++
            }

            // Check segment JSON is valid
            try {
                Json.decodeFromString<FlightSegment>(f.outboundJson)
            } catch (e: Exception) {
                Output.error("[${f.id}] Invalid outbound JSON: ${e.message}")
                errors++
            }

            if (f.returnJson != null) {
                try {
                    Json.decodeFromString<FlightSegment>(f.returnJson!!)
                } catch (e: Exception) {
                    Output.error("[${f.id}] Invalid return JSON: ${e.message}")
                    errors++
                }
            }

            // Validate against rules
            val outbound =
                try {
                    Json.decodeFromString<FlightSegment>(f.outboundJson)
                } catch (e: Exception) {
                    null
                }
            val returnSeg =
                f.returnJson?.let {
                    try {
                        Json.decodeFromString<FlightSegment>(it)
                    } catch (e: Exception) {
                        null
                    }
                }

            if (outbound != null) {
                // Full constraint check (airline, layover, trip duration)
                val violations = FlightValidator.checkConstraints(outbound, returnSeg, investigation)
                if (violations.isNotEmpty()) {
                    violations.forEach { v ->
                        Output.warn("[${f.id}] ${v.code}: ${v.message}")
                        warnings++
                    }
                }
            }

            // Check round trip has return
            if (f.tripType == "round_trip" && f.returnJson == null) {
                Output.error("[${f.id}] Round trip missing return segment")
                errors++
            }
        }

        println()
        if (errors == 0 && warnings == 0) {
            Output.success("All ${flights.size} flights valid")
        } else {
            println("Checked ${flights.size} flights: $errors errors, $warnings warnings")
        }

        return if (errors > 0) 1 else 0
    }
}

/**
 * Import/upsert flights from JSON.
 * Reads JSON flight data from stdin and matches against DB to update prices.
 */
@Command(
    name = "import",
    description = ["Import flights from JSON (updates prices for existing, optionally adds new)"],
)
class FlightImportCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Investigation slug"], defaultValue = "")
    var slug: String = ""

    @Option(names = ["--dry-run"], description = ["Show what would be updated without making changes"])
    var dryRun: Boolean = false

    @Option(names = ["--add-new"], description = ["Add new flights not in DB"])
    var addNew: Boolean = false

    @Option(names = ["--schema"], description = ["Print expected JSON schema and exit"])
    var showSchema: Boolean = false

    override fun call(): Int {
        if (showSchema) {
            println(
                """
                |FLIGHT IMPORT - JSON Format
                |===========================
                |
                |Expected input: JSON array piped to stdin
                |
                |[
                |  {
                |    "origin": "LHR",              // REQUIRED - 3-letter origin airport
                |    "destination": "NRT",         // REQUIRED - 3-letter destination airport  
                |    "departDate": "2026-05-10",   // REQUIRED - departure date YYYY-MM-DD
                |    "airline": "Asiana Airlines", // REQUIRED - airline name for matching
                |    "price": 920.00,              // REQUIRED - price amount (> 0)
                |    "currency": "GBP",            // optional - 3-letter code, defaults to GBP
                |    "airlineCode": "OZ",          // optional - 2-letter IATA code (helps matching)
                |    "returnDate": "2026-06-01",   // optional - creates round_trip if present
                |    "departTime": "16:35",        // optional - HH:MM for stricter matching
                |    "link": "https://..."         // optional - specific flight booking URL
                |  }
                |]
                |
                |MATCHING BEHAVIOR:
                |  - Matches existing flights by: origin + destination + departDate + airline
                |  - If matched: updates price history
                |  - If not matched and --add-new: creates new flight
                |  - If not matched without --add-new: reports "not matched"
                |
                |LINK REQUIREMENTS (when using --add-new):
                |  - Must be a SPECIFIC FLIGHT URL, not a search results page
                |  - Click through to the flight details/booking page first
                |  - Use the Share button if available, or copy the URL from there
                |  - Links must be unique (used for deduplication)
                |
                |  BAD (search pages - will be rejected):
                |    https://www.kayak.co.uk/flights/LHR-NRT/2026-05-14/2026-05-30?...
                |    https://www.americanexpress.com/en-gb/travel/flights/
                |
                |  GOOD (specific flight selected):
                |    https://www.google.com/travel/flights/booking?...
                |    https://www.kayak.co.uk/book/flight?...
                |
                |EXAMPLES:
                |  # Update prices for existing flights:
                |  cat prices.json | clawfare flight import tokyo-may-2026
                |
                |  # Add new flights:
                |  cat new-flights.json | clawfare flight import --add-new tokyo-may-2026
                |
                |  # Preview what would happen:
                |  cat data.json | clawfare flight import --dry-run --add-new tokyo-may-2026
                """.trimMargin(),
            )
            return 0
        }

        if (slug.isBlank()) {
            Output.error("Investigation slug required (or use --schema)")
            return 1
        }

        parent.parent.ensureDb()

        val inv = InvestigationQueries.getBySlug(slug)
        if (inv == null) {
            Output.error("Investigation '$slug' not found")
            return 1
        }

        // Read JSON from stdin
        val input = generateSequence(::readLine).joinToString("\n")
        if (input.isBlank()) {
            Output.error("No input provided. Pipe JSON flight data to stdin.")
            return 1
        }

        // Parse flights
        val importedFlights =
            try {
                Json.decodeFromString<List<ImportFlight>>(input)
            } catch (e: Exception) {
                Output.error("Failed to parse input JSON: ${e.message}")
                return 1
            }

        if (importedFlights.isEmpty()) {
            Output.warn("No flights in input")
            return 0
        }

        println("Processing ${importedFlights.size} flights...")

        val dbFlightsWithPrices = FlightQueries.listWithPrices(slug)
        var updated = 0
        var unchanged = 0
        var notFound = 0
        var added = 0

        importedFlights.forEach { imported ->
            // Find matching flight in DB
            val match = findMatchingFlight(dbFlightsWithPrices, imported)

            if (match != null) {
                // Check if price changed
                if (match.priceAmount != imported.price || match.priceCurrency != imported.currency) {
                    val oldPrice = Output.formatPrice(match.priceAmount, match.priceCurrency)
                    val newPrice = Output.formatPrice(imported.price, imported.currency)
                    val change = imported.price - match.priceAmount
                    val changeStr =
                        when {
                            change > 0 -> "↑ +${Output.formatPrice(change, imported.currency)}"
                            change < 0 -> "↓ -${Output.formatPrice(-change, imported.currency)}"
                            else -> "="
                        }

                    if (dryRun) {
                        println("[DRY RUN] ${match.id.take(8)}: $oldPrice → $newPrice ($changeStr)")
                    } else {
                        // Record new price in history (canonical way to update prices)
                        PriceHistoryQueries.create(
                            PriceHistoryDto(
                                flightId = match.id,
                                amount = imported.price,
                                currency = imported.currency,
                                checkedAt =
                                    java.time.Instant
                                        .now()
                                        .toString(),
                                priceMarket = match.priceMarket,
                            ),
                        )
                        println("${match.id.take(8)}: $oldPrice → $newPrice ($changeStr)")
                    }
                    updated++
                } else {
                    // Same price - record a check (shows we verified the price)
                    if (!dryRun) {
                        PriceHistoryQueries.create(
                            PriceHistoryDto(
                                flightId = match.id,
                                amount = imported.price,
                                currency = imported.currency,
                                checkedAt =
                                    java.time.Instant
                                        .now()
                                        .toString(),
                                priceMarket = match.priceMarket,
                            ),
                        )
                    }
                    unchanged++
                }
            } else {
                // Flight not in DB
                if (addNew) {
                    if (dryRun) {
                        println(
                            "[DRY RUN] Would add: ${imported.airline} ${imported.origin}→${imported.destination} ${Output.formatPrice(
                                imported.price,
                                imported.currency,
                            )}",
                        )
                        added++
                    } else {
                        val result = addNewFlight(inv, imported)
                        if (result) {
                            added++
                        } else {
                            notFound++
                        }
                    }
                } else {
                    notFound++
                }
            }
        }

        println()
        println("Results: $updated updated, $unchanged unchanged, $notFound not matched" + if (addNew) ", $added added" else "")

        return 0
    }

    /**
     * Find a matching flight in the database.
     * Matches on: origin, destination, departure date, airline
     */
    private fun findMatchingFlight(
        dbFlights: List<FlightWithPrice>,
        imported: ImportFlight,
    ): FlightWithPrice? {
        return dbFlights.find { db ->
            val outbound = Output.parseSegment(db.outboundJson)
            if (outbound == null) return@find false

            val firstLeg = outbound.legs.firstOrNull() ?: return@find false

            // Match on key attributes
            db.origin == imported.origin &&
                db.destination == imported.destination &&
                firstLeg.departTime.startsWith(imported.departDate) &&
                (
                    imported.airlineCode.isNotBlank() &&
                        firstLeg.airlineCode == imported.airlineCode ||
                        firstLeg.airline.equals(imported.airline, ignoreCase = true)
                )
        }
    }

    /**
     * Add a new flight from import data.
     * Creates minimal segment JSON from available data.
     */
    private fun addNewFlight(
        inv: InvestigationDto,
        imported: ImportFlight,
    ): Boolean {
        // Validate link if provided
        if (imported.link != null) {
            val (isValid, errorMsg) = FlightValidator.validateShareLink(imported.link)
            if (!isValid) {
                println("  Rejected: ${imported.airline} ${imported.origin}→${imported.destination}")
                println("    $errorMsg")
                return false
            }
        }

        // Generate a unique ID - use link if available, otherwise construct from data
        val idSource =
            imported.link ?: "${imported.origin}-${imported.destination}-${imported.departDate}-${imported.airline}-${imported.price}"
        val id = FlightValidator.generateId(idSource)

        // Check if this link already exists
        if (imported.link != null && FlightQueries.getByShareLink(imported.link) != null) {
            println("  Skipped (link exists): ${imported.airline} ${imported.origin}→${imported.destination}")
            return false
        }

        val now =
            java.time.Instant
                .now()
                .toString()

        // Build minimal outbound segment
        val departTime = "${imported.departDate}T${imported.departTime ?: "00:00"}:00"
        val outboundSegment =
            FlightSegment(
                departAirport = imported.origin,
                arriveAirport = imported.destination,
                departTime = departTime,
                arriveTime = departTime, // Unknown
                durationMinutes = 0, // Unknown
                stops = 0,
                legs =
                    listOf(
                        FlightLeg(
                            airline = imported.airline,
                            airlineCode = imported.airlineCode.ifBlank { "" },
                            flightNumber = "",
                            departAirport = imported.origin,
                            arriveAirport = imported.destination,
                            departTime = departTime,
                            arriveTime = departTime,
                            durationMinutes = 0,
                        ),
                    ),
            )

        // Build return segment if return date provided
        val returnSegment =
            imported.returnDate?.let { returnDate ->
                val returnTime = "${returnDate}T00:00:00"
                FlightSegment(
                    departAirport = imported.destination,
                    arriveAirport = imported.origin,
                    departTime = returnTime,
                    arriveTime = returnTime,
                    durationMinutes = 0,
                    stops = 0,
                    legs =
                        listOf(
                            FlightLeg(
                                airline = imported.airline,
                                airlineCode = imported.airlineCode.ifBlank { "" },
                                flightNumber = "",
                                departAirport = imported.destination,
                                arriveAirport = imported.origin,
                                departTime = returnTime,
                                arriveTime = returnTime,
                                durationMinutes = 0,
                            ),
                        ),
                )
            }

        // Check constraints before adding
        val violations = FlightValidator.checkConstraints(outboundSegment, returnSegment, inv)
        if (violations.isNotEmpty()) {
            println("  Rejected (constraint violation): ${imported.airline} ${imported.origin}→${imported.destination}")
            violations.forEach { v -> println("    ✗ [${v.code}] ${v.message}") }
            return false
        }

        val dto =
            FlightDto(
                id = id,
                investigationSlug = inv.slug,
                // Deprecated: keeping for backward compat
                shareLink = imported.link ?: idSource,
                source = "import",
                tripType = if (imported.returnDate != null) "round_trip" else "one_way",
                ticketStructure = "single",
                origin = imported.origin,
                destination = imported.destination,
                outboundJson = Json.encodeToString(FlightSegment.serializer(), outboundSegment),
                returnJson = returnSegment?.let { Json.encodeToString(FlightSegment.serializer(), it) },
                bookingClass = null,
                aircraftType = null,
                fareBrand = null,
                disqualified = null,
                notes = "Imported via CLI",
                tags = null,
                capturedAt = now,
            )

        try {
            FlightQueries.create(dto)

            // Canonical: price + link in price_history
            PriceHistoryQueries.create(
                PriceHistoryDto(
                    flightId = id,
                    amount = imported.price,
                    currency = imported.currency.uppercase(),
                    sourceUrl = imported.link ?: "",
                    checkedAt = now,
                    priceMarket = "UK",
                ),
            )

            println(
                "  Added: ${imported.airline} ${imported.origin}→${imported.destination} ${Output.formatPrice(
                    imported.price,
                    imported.currency,
                )}",
            )
            return true
        } catch (e: Exception) {
            println("  Failed to add ${imported.airline}: ${e.message}")
            return false
        }
    }
}

/**
 * Find flights matching given attributes.
 */
@Command(
    name = "match",
    description = ["Find flights matching given attributes"],
)
class FlightMatchCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["--origin", "-o"], required = true, description = ["Origin airport"])
    lateinit var origin: String

    @Option(names = ["--dest", "-d"], required = true, description = ["Destination airport"])
    lateinit var dest: String

    @Option(names = ["--depart"], required = true, description = ["Departure date (YYYY-MM-DD)"])
    lateinit var departDate: String

    @Option(names = ["--airline", "-a"], description = ["Airline code or name"])
    var airline: String? = null

    @Option(names = ["--time", "-t"], description = ["Departure time (HH:MM)"])
    var departTime: String? = null

    override fun call(): Int {
        parent.parent.ensureDb()

        val flightsWithPrices = FlightQueries.listWithPrices(slug)
        if (flightsWithPrices.isEmpty()) {
            Output.error("No flights in '$slug'")
            return 1
        }

        val matches =
            flightsWithPrices.filter { f ->
                val outbound = Output.parseSegment(f.outboundJson) ?: return@filter false
                val firstLeg = outbound.legs.firstOrNull() ?: return@filter false

                // Required matches
                if (f.origin != origin.uppercase()) return@filter false
                if (f.destination != dest.uppercase()) return@filter false
                if (!firstLeg.departTime.startsWith(departDate)) return@filter false

                // Optional filters
                if (airline != null) {
                    val airlineMatch =
                        firstLeg.airlineCode.equals(airline, ignoreCase = true) ||
                            firstLeg.airline.contains(airline!!, ignoreCase = true)
                    if (!airlineMatch) return@filter false
                }

                if (departTime != null) {
                    val timeMatch = firstLeg.departTime.contains("T$departTime")
                    if (!timeMatch) return@filter false
                }

                true
            }

        if (matches.isEmpty()) {
            println("No matches found")
            return 1
        }

        println("Found ${matches.size} match(es):")
        println()
        matches.forEach { f ->
            val outbound = Output.parseSegment(f.outboundJson)!!
            val firstLeg = outbound.legs.first()
            println("${f.id}")
            println("  ${Output.formatPrice(f.priceAmount, f.priceCurrency)} - ${firstLeg.airline}")
            println("  ${f.origin}→${f.destination} on ${firstLeg.departTime.take(16)}")
            println("  Link: ${f.shareLink}")
            println()
        }

        return 0
    }
}

/**
 * Deduplicate flights by routing signature.
 * Keeps the cheapest flight for each unique routing.
 */
@Command(
    name = "dedupe",
    description = ["Remove duplicate flights, keeping cheapest per routing"],
)
class FlightDedupeCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["--dry-run"], description = ["Show what would be deleted without making changes"])
    var dryRun: Boolean = false

    @Option(names = ["--keep-valid"], description = ["Keep flights with valid times even if more expensive"])
    var keepValid: Boolean = true

    override fun call(): Int {
        parent.parent.ensureDb()

        val flightsWithPrices = FlightQueries.listWithPrices(slug)
        if (flightsWithPrices.isEmpty()) {
            println("No flights to dedupe")
            return 0
        }

        println("Analyzing ${flightsWithPrices.size} flights...")

        // Group by signature
        val grouped = flightsWithPrices.groupBy { computeSignature(it) }

        var toDelete = mutableListOf<FlightWithPrice>()
        var toKeep = mutableListOf<FlightWithPrice>()

        grouped.forEach { (signature, group) ->
            if (group.size == 1) {
                toKeep.add(group.first())
                return@forEach
            }

            // Multiple flights with same signature - pick the best
            val best = pickBest(group, keepValid)
            toKeep.add(best)
            toDelete.addAll(group.filter { it.id != best.id })
        }

        // Also mark invalid flights (0 duration) for deletion
        val invalid = flightsWithPrices.filter { isInvalid(it) }
        val invalidNotAlreadyMarked = invalid.filter { inv -> toDelete.none { it.id == inv.id } }
        toDelete.addAll(invalidNotAlreadyMarked)
        toKeep.removeAll { k -> invalid.any { it.id == k.id } }

        println()
        println("Summary:")
        println("  Unique routings: ${grouped.size}")
        println("  Flights to keep: ${toKeep.size}")
        println("  Duplicates to remove: ${toDelete.size - invalidNotAlreadyMarked.size}")
        println("  Invalid to remove: ${invalidNotAlreadyMarked.size}")
        println()

        if (toDelete.isEmpty()) {
            println("Nothing to clean up!")
            return 0
        }

        if (dryRun) {
            println("Would delete:")
            toDelete.take(20).forEach { f ->
                val outbound = Output.parseSegment(f.outboundJson)
                val airline = outbound?.legs?.firstOrNull()?.airline ?: "?"
                val reason = if (isInvalid(f)) "[INVALID]" else "[DUPE]"
                println("  $reason ${f.id.take(8)} ${Output.formatPrice(f.priceAmount, f.priceCurrency)} $airline")
            }
            if (toDelete.size > 20) {
                println("  ... and ${toDelete.size - 20} more")
            }
        } else {
            println("Deleting ${toDelete.size} flights...")
            var deleted = 0
            toDelete.forEach { f ->
                if (FlightQueries.delete(f.id)) {
                    deleted++
                }
            }
            Output.success("Deleted $deleted flights")
        }

        return 0
    }

    /**
     * Compute a signature for grouping duplicate flights.
     * Format: {date}|{airline}|{origin}-{via...}-{dest}[|{return_date}|{airline}|{routing}]
     */
    private fun computeSignature(flight: FlightWithPrice): String {
        val outbound = Output.parseSegment(flight.outboundJson)
        val returnSeg = flight.returnJson?.let { Output.parseSegment(it) }

        val outSig = segmentSignature(outbound, flight.origin, flight.destination)
        val retSig = returnSeg?.let { segmentSignature(it, flight.destination, flight.origin) }

        return if (retSig != null) "$outSig|$retSig" else outSig
    }

    private fun segmentSignature(
        segment: FlightSegment?,
        fallbackOrigin: String,
        fallbackDest: String,
    ): String {
        if (segment == null || segment.legs.isEmpty()) {
            return "?|?|$fallbackOrigin-$fallbackDest"
        }

        val date = segment.departTime.substring(0, 10)
        val airline = segment.legs.firstOrNull()?.airlineCode ?: "?"

        // Build routing
        val airports = mutableListOf(segment.legs.first().departAirport)
        segment.legs.forEach { airports.add(it.arriveAirport) }
        val routing = airports.joinToString("-")

        return "$date|$airline|$routing"
    }

    /**
     * Pick the best flight from a group of duplicates.
     */
    private fun pickBest(
        group: List<FlightWithPrice>,
        preferValid: Boolean,
    ): FlightWithPrice {
        if (preferValid) {
            // Prefer valid flights (non-zero duration)
            val valid = group.filter { !isInvalid(it) }
            if (valid.isNotEmpty()) {
                // Among valid, pick cheapest
                return valid.minByOrNull { it.priceAmount }!!
            }
        }
        // Fall back to cheapest overall
        return group.minByOrNull { it.priceAmount }!!
    }

    /**
     * Check if a flight has invalid/placeholder data.
     */
    private fun isInvalid(flight: FlightWithPrice): Boolean {
        val outbound = Output.parseSegment(flight.outboundJson) ?: return true

        // Check for zero duration (placeholder data)
        if (outbound.durationMinutes == 0) return true

        // Check for all-zero leg times
        val firstLeg = outbound.legs.firstOrNull() ?: return true
        if (firstLeg.durationMinutes == 0) return true

        return false
    }
}

// ============================================================================
// Price Check Task Exchange
// ============================================================================

/**
 * Task file format for price checking.
 */
@kotlinx.serialization.Serializable
data class PriceCheckTask(
    val taskId: String,
    val createdAt: String,
    val investigation: String,
    val flights: List<PriceCheckFlight>,
)

@kotlinx.serialization.Serializable
data class PriceCheckFlight(
    val id: String,
    val shareLink: String,
    val origin: String,
    val destination: String,
    val departDate: String,
    val returnDate: String? = null,
    val airline: String,
    val lastPrice: Double,
    val currency: String,
    val lastChecked: String? = null,
)

/**
 * Result file format from price checking.
 */
@kotlinx.serialization.Serializable
data class PriceCheckResults(
    val taskId: String,
    val completedAt: String,
    val results: List<PriceCheckResult>,
)

@kotlinx.serialization.Serializable
data class PriceCheckResult(
    val id: String,
    val status: String, // "ok", "price_changed", "unavailable", "error"
    val price: Double? = null,
    val currency: String? = null,
    val checkedAt: String? = null,
    val reason: String? = null,
)

/**
 * Export flights as a task file for external price checking.
 */
@Command(
    name = "export-tasks",
    description = ["Export flights as task file for external price checking"],
)
class FlightExportTasksCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Investigation slug"])
    lateinit var slug: String

    @Option(names = ["-o", "--output"], description = ["Output file path"])
    var output: String? = null

    @Option(names = ["--stale-hours"], description = ["Only include flights not checked in N hours"], defaultValue = "24")
    var staleHours: Int = 24

    @Option(names = ["--limit"], description = ["Maximum number of flights to export"])
    var limit: Int? = null

    @Option(names = ["--all"], description = ["Export all flights, not just stale ones"])
    var all: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        val flightsWithPrices = FlightQueries.listWithPrices(slug)
        if (flightsWithPrices.isEmpty()) {
            println("No flights found")
            return 1
        }

        // Filter to flights that need checking
        val cutoff = if (all) null else Instant.now().minusSeconds(staleHours.toLong() * 3600)
        val toCheck =
            flightsWithPrices
                .filter { f ->
                    if (f.shareLink.isBlank()) return@filter false
                    if (cutoff == null) return@filter true

                    val lastChecked =
                        try {
                            Instant.parse(f.priceCheckedAt)
                        } catch (e: Exception) {
                            null
                        }
                    lastChecked == null || lastChecked.isBefore(cutoff)
                }.let { list ->
                    limit?.let { list.take(it) } ?: list
                }

        if (toCheck.isEmpty()) {
            println("No flights need price checking (all checked within ${staleHours}h)")
            return 0
        }

        val taskId = "price-check-$slug-${Instant.now().toString().take(10)}"

        val taskFlights =
            toCheck.map { f ->
                val outbound = Output.parseSegment(f.outboundJson)
                val firstLeg = outbound?.legs?.firstOrNull()

                PriceCheckFlight(
                    id = f.id,
                    shareLink = f.shareLink,
                    origin = f.origin,
                    destination = f.destination,
                    departDate = firstLeg?.departTime?.take(10) ?: "",
                    returnDate =
                        f.returnJson
                            ?.let { Output.parseSegment(it) }
                            ?.legs
                            ?.firstOrNull()
                            ?.departTime
                            ?.take(10),
                    airline = firstLeg?.airline ?: "",
                    lastPrice = f.priceAmount,
                    currency = f.priceCurrency,
                    lastChecked = f.priceCheckedAt,
                )
            }

        val task =
            PriceCheckTask(
                taskId = taskId,
                createdAt = Instant.now().toString(),
                investigation = slug,
                flights = taskFlights,
            )

        val json = Json { prettyPrint = true }
        val content = json.encodeToString(PriceCheckTask.serializer(), task)

        val outFile = output ?: "price-check-$slug.json"
        java.io.File(outFile).writeText(content)

        Output.success("Exported ${toCheck.size} flights to $outFile")
        println("Task ID: $taskId")

        return 0
    }
}

/**
 * Import price check results from external agent.
 */
@Command(
    name = "import-results",
    description = ["Import price check results from external agent"],
)
class FlightImportResultsCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Results file path"])
    lateinit var resultsFile: String

    @Option(names = ["--dry-run"], description = ["Show what would be updated without making changes"])
    var dryRun: Boolean = false

    override fun call(): Int {
        parent.parent.ensureDb()

        val file = java.io.File(resultsFile)
        if (!file.exists()) {
            Output.error("File not found: $resultsFile")
            return 1
        }

        val json = Json { ignoreUnknownKeys = true }
        val results =
            try {
                json.decodeFromString(PriceCheckResults.serializer(), file.readText())
            } catch (e: Exception) {
                Output.error("Failed to parse results: ${e.message}")
                return 1
            }

        println("Processing ${results.results.size} results from task ${results.taskId}")
        println()

        var updated = 0
        var unchanged = 0
        var unavailable = 0
        var errors = 0

        results.results.forEach { result ->
            val flightWithPrice = FlightQueries.getWithPrice(result.id)
            if (flightWithPrice == null) {
                println("  ⚠ Flight ${result.id.take(8)} not found in database (or no price history)")
                errors++
                return@forEach
            }

            when (result.status) {
                "ok", "price_changed" -> {
                    val newPrice = result.price ?: return@forEach
                    val currency = result.currency ?: flightWithPrice.priceCurrency
                    val priceChanged = newPrice != flightWithPrice.priceAmount

                    if (priceChanged) {
                        val diff = newPrice - flightWithPrice.priceAmount
                        val diffStr = if (diff > 0) "+${Output.formatPrice(diff, currency)}" else Output.formatPrice(diff, currency)
                        println(
                            "  ${if (diff > 0) "📈" else "📉"} ${flightWithPrice.id.take(
                                8,
                            )}: ${Output.formatPrice(
                                flightWithPrice.priceAmount,
                                currency,
                            )} → ${Output.formatPrice(newPrice, currency)} ($diffStr)",
                        )

                        if (!dryRun) {
                            // Record price history (canonical way to update prices)
                            PriceHistoryQueries.create(
                                PriceHistoryDto(
                                    flightId = flightWithPrice.flight.id,
                                    amount = newPrice,
                                    currency = currency,
                                    checkedAt = result.checkedAt ?: Instant.now().toString(),
                                    priceMarket = flightWithPrice.priceMarket,
                                ),
                            )
                        }
                        updated++
                    } else {
                        if (!dryRun) {
                            // Record check even if price unchanged (shows we verified)
                            PriceHistoryQueries.create(
                                PriceHistoryDto(
                                    flightId = flightWithPrice.flight.id,
                                    amount = newPrice,
                                    currency = currency,
                                    checkedAt = result.checkedAt ?: Instant.now().toString(),
                                    priceMarket = flightWithPrice.priceMarket,
                                ),
                            )
                        }
                        unchanged++
                    }
                }
                "unavailable" -> {
                    println("  ❌ ${flightWithPrice.id.take(8)}: No longer available${result.reason?.let { " ($it)" } ?: ""}")
                    unavailable++
                    // Could optionally mark as unavailable or delete
                }
                "error" -> {
                    println("  ⚠ ${flightWithPrice.id.take(8)}: Error - ${result.reason ?: "unknown"}")
                    errors++
                }
            }
        }

        println()
        println("Summary:")
        println("  Price changes: $updated")
        println("  Unchanged: $unchanged")
        println("  Unavailable: $unavailable")
        println("  Errors: $errors")

        if (dryRun && updated > 0) {
            println()
            println("Run without --dry-run to apply changes")
        }

        return 0
    }
}

/**
 * Import data from another clawfare database file.
 * Merges investigations, flights, and price history.
 */
@Command(
    name = "import-db",
    description = ["Import/merge data from another clawfare database"],
)
class FlightImportDbCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: FlightCommand

    @Parameters(index = "0", description = ["Source database path"], defaultValue = "")
    var sourcePath: String = ""

    @Option(names = ["--dry-run"], description = ["Show what would be imported without making changes"])
    var dryRun: Boolean = false

    @Option(names = ["--investigation", "-i"], description = ["Only import specific investigation slug"])
    var investigation: String? = null

    @Option(names = ["--skip-prices"], description = ["Don't import price history"])
    var skipPrices: Boolean = false

    override fun call(): Int {
        if (sourcePath.isBlank()) {
            Output.error("Source database path required")
            return 1
        }

        parent.parent.ensureDb()

        val sourceFile = java.io.File(sourcePath)
        if (!sourceFile.exists()) {
            Output.error("Source database not found: $sourcePath")
            return 1
        }

        // Connect to source DB via raw JDBC (separate from main connection)
        val sourceUrl = "jdbc:sqlite:${sourceFile.absolutePath}"
        val sourceConn = java.sql.DriverManager.getConnection(sourceUrl)

        try {
            println("Importing from: $sourcePath")
            println()

            var invCreated = 0
            var invSkipped = 0
            var flightCreated = 0
            var flightSkipped = 0
            var pricesImported = 0

            // 1. Import investigations
            val invStmt =
                sourceConn.prepareStatement(
                    if (investigation != null) {
                        "SELECT * FROM investigations WHERE slug = ?"
                    } else {
                        "SELECT * FROM investigations"
                    },
                )
            if (investigation != null) {
                invStmt.setString(1, investigation)
            }
            val invRs = invStmt.executeQuery()

            val importedSlugs = mutableListOf<String>()

            while (invRs.next()) {
                val slug = invRs.getString("slug")
                importedSlugs.add(slug)

                // Check if investigation exists
                val existing = InvestigationQueries.getBySlug(slug)
                if (existing != null) {
                    println("  Investigation '$slug': exists (skipping)")
                    invSkipped++
                } else {
                    if (dryRun) {
                        println("  Investigation '$slug': would create")
                    } else {
                        InvestigationQueries.create(
                            InvestigationDto(
                                slug = slug,
                                origin = invRs.getString("origin"),
                                destination = invRs.getString("destination"),
                                departStart = invRs.getString("depart_start"),
                                departEnd = invRs.getString("depart_end"),
                                returnStart = invRs.getString("return_start"),
                                returnEnd = invRs.getString("return_end"),
                                cabinClass = invRs.getString("cabin_class") ?: "economy",
                                maxStops = invRs.getInt("max_stops"),
                                maxPrice = invRs.getDouble("max_price").takeIf { !invRs.wasNull() },
                                departAfter = invRs.getString("depart_after"),
                                departBefore = invRs.getString("depart_before"),
                                minTripDays = invRs.getInt("min_trip_days").takeIf { !invRs.wasNull() },
                                maxTripDays = invRs.getInt("max_trip_days").takeIf { !invRs.wasNull() },
                                mustIncludeDate = invRs.getString("must_include_date"),
                                maxLayoverMinutes = invRs.getInt("max_layover_minutes").takeIf { !invRs.wasNull() },
                                createdAt = invRs.getString("created_at") ?: Instant.now().toString(),
                                updatedAt = invRs.getString("updated_at") ?: Instant.now().toString(),
                            ),
                        )
                        println("  Investigation '$slug': created")
                    }
                    invCreated++
                }
            }
            invRs.close()
            invStmt.close()

            if (importedSlugs.isEmpty()) {
                println("No investigations to import")
                return 0
            }

            println()

            // 2. Import flights
            val flightStmt =
                sourceConn.prepareStatement(
                    "SELECT * FROM flights WHERE investigation_slug = ?",
                )

            importedSlugs.forEach { slug ->
                flightStmt.setString(1, slug)
                val flightRs = flightStmt.executeQuery()

                while (flightRs.next()) {
                    val flightId = flightRs.getString("id")
                    val shareLink = flightRs.getString("share_link")

                    // Check for duplicate by ID or share link
                    val existingById = FlightQueries.getById(flightId)
                    val existingByLink = FlightQueries.getByShareLink(shareLink)

                    if (existingById != null || existingByLink != null) {
                        flightSkipped++
                        // Import price history for existing flights if we have new prices
                        if (!skipPrices && existingById != null) {
                            pricesImported += importPriceHistory(sourceConn, flightId, flightId, dryRun, shareLink)
                        }
                    } else {
                        if (dryRun) {
                            println("  Flight ${flightId.take(8)}: would create")
                        } else {
                            // Handle different schema versions (flight_source vs source)
                            val flightSource =
                                getColumnSafe(flightRs, "flight_source")
                                    ?: getColumnSafe(flightRs, "source")
                                    ?: "imported"

                            FlightQueries.create(
                                FlightDto(
                                    id = flightId,
                                    investigationSlug = slug,
                                    shareLink = shareLink,
                                    source = flightSource,
                                    tripType = getColumnSafe(flightRs, "trip_type") ?: "round_trip",
                                    ticketStructure = getColumnSafe(flightRs, "ticket_structure") ?: "single",
                                    priceAmount = flightRs.getDouble("price_amount"),
                                    priceCurrency = flightRs.getString("price_currency"),
                                    priceMarket = getColumnSafe(flightRs, "price_market") ?: "UK",
                                    priceCheckedAt = getColumnSafe(flightRs, "price_checked_at") ?: Instant.now().toString(),
                                    origin = flightRs.getString("origin"),
                                    destination = flightRs.getString("destination"),
                                    outboundJson = flightRs.getString("outbound_json"),
                                    returnJson = getColumnSafe(flightRs, "return_json"),
                                    bookingClass = getColumnSafe(flightRs, "booking_class"),
                                    cabinMixed = getIntSafe(flightRs, "cabin_mixed") == 1,
                                    stale = getIntSafe(flightRs, "stale") == 1,
                                    aircraftType = getColumnSafe(flightRs, "aircraft_type"),
                                    fareBrand = getColumnSafe(flightRs, "fare_brand"),
                                    disqualified = getColumnSafe(flightRs, "disqualified"),
                                    notes = getColumnSafe(flightRs, "notes"),
                                    tags = getColumnSafe(flightRs, "tags"),
                                    capturedAt = getColumnSafe(flightRs, "captured_at") ?: Instant.now().toString(),
                                ),
                            )
                            println("  Flight ${flightId.take(8)}: created")
                        }
                        flightCreated++

                        // Import price history for new flights
                        if (!skipPrices) {
                            val imported = importPriceHistory(sourceConn, flightId, flightId, dryRun, shareLink)
                            pricesImported += imported

                            // If no price history was imported, create one from flight's price fields
                            if (imported == 0 && !dryRun) {
                                val priceAmount =
                                    try {
                                        flightRs.getDouble("price_amount")
                                    } catch (_: Exception) {
                                        0.0
                                    }
                                val priceCurrency = getColumnSafe(flightRs, "price_currency") ?: "GBP"
                                if (priceAmount > 0) {
                                    PriceHistoryQueries.create(
                                        PriceHistoryDto(
                                            flightId = flightId,
                                            amount = priceAmount,
                                            currency = priceCurrency,
                                            sourceUrl = shareLink ?: "",
                                            checkedAt = getColumnSafe(flightRs, "price_checked_at") ?: Instant.now().toString(),
                                            priceMarket = getColumnSafe(flightRs, "price_market") ?: "UK",
                                        ),
                                    )
                                    pricesImported++
                                }
                            }
                        }
                    }
                }
                flightRs.close()
            }
            flightStmt.close()

            println()
            println("Summary${if (dryRun) " (dry run)" else ""}:")
            println("  Investigations: $invCreated created, $invSkipped skipped")
            println("  Flights: $flightCreated created, $flightSkipped skipped")
            if (!skipPrices) {
                println("  Price observations: $pricesImported imported")
            }

            return 0
        } finally {
            sourceConn.close()
        }
    }

    /**
     * Import price history from source DB for a flight.
     * Returns count of prices imported.
     */
    private fun importPriceHistory(
        sourceConn: java.sql.Connection,
        sourceFlightId: String,
        targetFlightId: String,
        dryRun: Boolean,
        fallbackUrl: String? = null,
    ): Int {
        val priceStmt =
            sourceConn.prepareStatement(
                "SELECT * FROM price_history WHERE flight_id = ? ORDER BY checked_at",
            )
        priceStmt.setString(1, sourceFlightId)
        val priceRs = priceStmt.executeQuery()

        // Get existing price timestamps to avoid duplicates
        val existingPrices = PriceHistoryQueries.getByFlightId(targetFlightId)
        val existingTimestamps = existingPrices.map { it.checkedAt }.toSet()

        var count = 0
        while (priceRs.next()) {
            val checkedAt = priceRs.getString("checked_at")

            // Skip if we already have a price at this exact timestamp
            if (checkedAt in existingTimestamps) {
                continue
            }

            if (!dryRun) {
                PriceHistoryQueries.create(
                    PriceHistoryDto(
                        flightId = targetFlightId,
                        amount = priceRs.getDouble("amount"),
                        currency = priceRs.getString("currency"),
                        sourceUrl = getColumnSafe(priceRs, "source_url")?.takeIf { it.isNotBlank() } ?: fallbackUrl ?: "",
                        checkedAt = checkedAt,
                        source = getColumnSafe(priceRs, "price_source") ?: getColumnSafe(priceRs, "source") ?: "imported",
                        priceMarket = getColumnSafe(priceRs, "price_market") ?: "UK",
                    ),
                )
            }
            count++
        }
        priceRs.close()
        priceStmt.close()

        return count
    }

    /**
     * Safely get a string column, returning null if it doesn't exist.
     */
    private fun getColumnSafe(
        rs: java.sql.ResultSet,
        column: String,
    ): String? =
        try {
            rs.getString(column)
        } catch (e: java.sql.SQLException) {
            null
        }

    /**
     * Safely get an int column, returning 0 if it doesn't exist.
     */
    private fun getIntSafe(
        rs: java.sql.ResultSet,
        column: String,
    ): Int =
        try {
            rs.getInt(column)
        } catch (e: java.sql.SQLException) {
            0
        }
}

// ============================================================================
// Import Data Structure
// ============================================================================

/**
 * Flight data structure for import/upsert operations.
 */
@kotlinx.serialization.Serializable
data class ImportFlight(
    val origin: String,
    val destination: String,
    val departDate: String,
    val returnDate: String? = null,
    val airline: String,
    val airlineCode: String = "",
    val price: Double,
    val currency: String = "GBP",
    val departTime: String? = null,
    val link: String? = null,
)

// ============================================================================
// Root Command
// ============================================================================

/**
 * Root command for clawfare CLI.
 */
@Command(
    name = "clawfare",
    version = ["clawfare 0.1.0"],
    description = ["Flight price investigation tracker"],
    mixinStandardHelpOptions = true,
    subcommands = [
        InvCommand::class,
        FlightCommand::class,
        FareCommand::class,
        ServeCommand::class,
        ReportCommand::class,
    ],
)
class ClawfareCommand : Callable<Int> {
    @Option(names = ["--db"], description = ["Database path"], defaultValue = "")
    var dbPath: String = ""

    private var dbInitialized = false

    /**
     * Ensure database is connected.
     * Uses existing connection if already initialized.
     */
    fun ensureDb() {
        if (!dbInitialized) {
            // Check if database is already connected (e.g., in tests)
            if (ClawfareDatabase.isInitialized()) {
                dbInitialized = true
                return
            }
            val path = if (dbPath.isBlank()) ClawfareDatabase.defaultPath else dbPath
            ClawfareDatabase.connect(path)
            dbInitialized = true
        }
    }

    override fun call(): Int {
        // No subcommand provided - show help
        CommandLine(this).usage(System.out)
        return 0
    }
}
