package com.clawfare.cli

import com.clawfare.db.FlightDto
import com.clawfare.db.FlightQueries
import com.clawfare.db.InvestigationDto
import com.clawfare.db.InvestigationQueries
import com.clawfare.db.PriceHistoryDto
import com.clawfare.db.PriceHistoryQueries
import org.jetbrains.exposed.sql.transactions.transaction
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.time.Instant
import java.util.concurrent.Callable

/** Atomic, offline import/merge of investigations, flights, and dated quotes. */
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
        // A single destination transaction encloses investigation, flight and
        // price-history writes. Individual query helpers use the same Exposed
        // transaction; any failure rolls back the complete import rather than
        // persisting only the early records.
        return try {
            java.sql.DriverManager.getConnection(sourceUrl).use { sourceConn ->
                transaction {
                    importFrom(sourceConn)
                }
            }
        } catch (e: Exception) {
            Output.error("Database import failed; all destination changes rolled back: ${e.message ?: "unknown error"}")
            1
        }
    }

    private fun importFrom(sourceConn: java.sql.Connection): Int {
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
