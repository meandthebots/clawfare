package com.clawfare.export

import com.clawfare.db.FlightQueries
import com.clawfare.db.FlightWithPrice
import com.clawfare.db.PriceHistoryQueries
import com.clawfare.model.FlightSegment
import kotlinx.serialization.json.Json
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Generate markdown rankings of top-N flights sorted by price.
 */
object TopNGenerator {
    private val json = Json { ignoreUnknownKeys = true }
    private val dateFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Generate a top-N markdown report for an investigation.
     *
     * @param investigationSlug The investigation to generate report for
     * @param limit Maximum number of flights to include (default: 10)
     * @param title Optional title for the report
     * @return Markdown string with flight rankings
     */
    fun generateByInvestigation(
        investigationSlug: String,
        limit: Int = 10,
        title: String? = null,
    ): String {
        val flightsWithPrices = FlightQueries.listWithPrices(investigationSlug)
        return generate(flightsWithPrices, limit, title ?: "Top $limit Flights: $investigationSlug")
    }

    /**
     * Generate a top-N markdown report from all flights.
     *
     * @param limit Maximum number of flights to include
     * @param title Optional title for the report
     * @return Markdown string with flight rankings
     */
    fun generateAll(
        limit: Int = 10,
        title: String? = null,
    ): String {
        // Get all flights with their prices
        val flights = FlightQueries.listAll()
        val flightsWithPrices =
            flights.mapNotNull { flight ->
                val latestPrice = PriceHistoryQueries.getLatest(flight.id)
                if (latestPrice != null) {
                    FlightWithPrice(
                        flight = flight,
                        priceAmount = latestPrice.amount,
                        priceCurrency = latestPrice.currency,
                        priceMarket = latestPrice.priceMarket,
                        priceCheckedAt = latestPrice.checkedAt,
                        priceSource = latestPrice.source,
                        sourceUrl = latestPrice.sourceUrl,
                    )
                } else {
                    null
                }
            }
        return generate(flightsWithPrices, limit, title ?: "Top $limit Flights")
    }

    /**
     * Generate a top-N markdown report from a list of flights.
     *
     * @param flights List of flights to rank
     * @param limit Maximum number to include
     * @param title Report title
     * @return Markdown string with flight rankings
     */
    fun generate(
        flights: List<FlightWithPrice>,
        limit: Int = 10,
        title: String = "Top Flights",
    ): String {
        // Sort by price (ascending) and take top N
        val sortedFlights =
            flights
                .sortedBy { it.priceAmount }
                .take(limit)

        if (sortedFlights.isEmpty()) {
            return buildString {
                appendLine("# $title")
                appendLine()
                appendLine("_No flights found._")
            }
        }

        return buildString {
            appendLine("# $title")
            appendLine()
            appendLine("Generated: ${ZonedDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)}")
            appendLine()

            sortedFlights.forEachIndexed { index, flight ->
                appendLine(formatFlightEntry(index + 1, flight))
                appendLine()
            }

            // Summary statistics
            appendLine("---")
            appendLine()
            appendLine("## Summary")
            appendLine()
            appendLine("- **Total flights ranked:** ${sortedFlights.size}")
            if (sortedFlights.isNotEmpty()) {
                val minPrice = sortedFlights.first()
                val maxPrice = sortedFlights.last()
                appendLine(
                    "- **Price range:** ${formatPrice(minPrice.priceAmount, minPrice.priceCurrency)} " +
                        "- ${formatPrice(maxPrice.priceAmount, maxPrice.priceCurrency)}",
                )
            }
        }
    }

    /**
     * Format a single flight entry for the markdown report.
     */
    private fun formatFlightEntry(
        rank: Int,
        flight: FlightWithPrice,
    ): String {
        val outbound = json.decodeFromString<FlightSegment>(flight.outboundJson)
        val returnSegment = flight.returnJson?.let { json.decodeFromString<FlightSegment>(it) }

        return buildString {
            // Rank and price header
            appendLine(
                "## #$rank: ${formatPrice(flight.priceAmount, flight.priceCurrency)} " +
                    "(${flight.priceMarket})",
            )
            appendLine()

            // Route summary
            appendLine("**${flight.origin} → ${flight.destination}** | ${flight.tripType.replace('_', ' ')}")
            appendLine()

            // Outbound
            appendLine("### Outbound")
            appendLine(formatSegment(outbound))

            // Return (if round trip)
            returnSegment?.let {
                appendLine()
                appendLine("### Return")
                appendLine(formatSegment(it))
            }

            // Metadata
            appendLine()
            appendLine("**Details:**")
            flight.bookingClass?.let { appendLine("- Cabin: $it") }
            appendLine("- Source: ${flight.source}")
            appendLine("- ID: `${flight.id}`")
            flight.notes?.let {
                appendLine()
                appendLine("**Notes:** $it")
            }

            // Tags
            flight.tags?.let { tagsJson ->
                try {
                    val tags = json.decodeFromString<List<String>>(tagsJson)
                    if (tags.isNotEmpty()) {
                        appendLine()
                        appendLine("**Tags:** ${tags.joinToString(", ") { "`$it`" }}")
                    }
                } catch (_: Exception) {
                    // Ignore tag parsing errors
                }
            }
        }
    }

    /**
     * Format a flight segment with leg details.
     */
    private fun formatSegment(segment: FlightSegment): String =
        buildString {
            // Departure and arrival times
            val departTime = parseDateTime(segment.departTime)
            val arriveTime = parseDateTime(segment.arriveTime)

            appendLine(
                "- **${segment.departAirport}** ${formatTime(departTime)} → " +
                    "**${segment.arriveAirport}** ${formatTime(arriveTime)}",
            )
            appendLine("- Duration: ${formatDuration(segment.durationMinutes)} | Stops: ${segment.stops}")

            // Individual legs
            if (segment.legs.size > 1) {
                appendLine()
                appendLine("**Legs:**")
                segment.legs.forEachIndexed { index, leg ->
                    val legDepart = parseDateTime(leg.departTime)
                    val legArrive = parseDateTime(leg.arriveTime)
                    append("${index + 1}. ${leg.flightNumber} (${leg.airline}) ")
                    append("${leg.departAirport} ${formatTime(legDepart)} → ")
                    appendLine("${leg.arriveAirport} ${formatTime(legArrive)}")
                    leg.aircraft?.let { appendLine("   Aircraft: $it") }
                }
            } else if (segment.legs.isNotEmpty()) {
                val leg = segment.legs.first()
                appendLine("- Flight: ${leg.flightNumber} (${leg.airline})")
                leg.aircraft?.let { appendLine("- Aircraft: $it") }
            }
        }

    /**
     * Format price with currency symbol.
     */
    private fun formatPrice(
        amount: Double,
        currency: String,
    ): String {
        val symbol =
            when (currency) {
                "GBP" -> "£"
                "USD" -> "$"
                "EUR" -> "€"
                "JPY" -> "¥"
                else -> currency
            }

        // Format without decimals for JPY
        return if (currency == "JPY") {
            "$symbol${amount.toLong()}"
        } else {
            "$symbol%.2f".format(amount)
        }
    }

    /**
     * Format duration in hours and minutes.
     */
    private fun formatDuration(minutes: Int): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    }

    /**
     * Parse ISO 8601 datetime string.
     */
    private fun parseDateTime(datetime: String): ZonedDateTime? =
        try {
            ZonedDateTime.parse(datetime)
        } catch (_: Exception) {
            null
        }

    /**
     * Format time from ZonedDateTime.
     */
    private fun formatTime(dateTime: ZonedDateTime?): String = dateTime?.format(timeFormatter) ?: "??:??"
}
