package com.clawfare.cli

import com.clawfare.db.FlightDto
import com.clawfare.db.FlightWithPrice
import com.clawfare.db.InvestigationDto
import com.clawfare.db.PriceHistoryDto
import com.clawfare.model.FlightSegment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Output formatting utilities for CLI display.
 */
object Output {
    @PublishedApi
    internal val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    @PublishedApi
    internal val compactJson =
        Json {
            prettyPrint = false
            encodeDefaults = false
        }

    /**
     * Format an investigation for display.
     */
    fun formatInvestigation(
        inv: InvestigationDto,
        detailed: Boolean = false,
    ): String =
        buildString {
            appendLine("Investigation: ${inv.slug}")
            appendLine("  Route:    ${inv.origin} → ${inv.destination}")
            appendLine("  Depart:   ${inv.departStart} to ${inv.departEnd}")
            if (inv.returnStart != null) {
                appendLine("  Return:   ${inv.returnStart} to ${inv.returnEnd}")
            }
            appendLine("  Cabin:    ${inv.cabinClass}")
            appendLine("  Max stops: ${inv.maxStops}")
            if (detailed) {
                appendLine("  Created:  ${inv.createdAt}")
                appendLine("  Updated:  ${inv.updatedAt}")
            }
        }

    /**
     * Format a list of investigations as a table.
     */
    fun formatInvestigationTable(investigations: List<InvestigationDto>): String {
        if (investigations.isEmpty()) {
            return "No investigations found."
        }

        val headers = listOf("Slug", "Route", "Depart Range", "Cabin", "Stops")
        val rows =
            investigations.map { inv ->
                listOf(
                    inv.slug,
                    "${inv.origin} → ${inv.destination}",
                    "${inv.departStart} to ${inv.departEnd}",
                    inv.cabinClass,
                    inv.maxStops.toString(),
                )
            }

        return formatTable(headers, rows)
    }

    /**
     * Format a flight for display.
     * Note: This version is for raw FlightDto without price info.
     */
    fun formatFlight(
        flight: FlightDto,
        segment: FlightSegment?,
        returnSeg: FlightSegment?,
    ): String = formatFlightDetail(flight, segment, returnSeg, null)

    /**
     * Format a flight for detailed display, including price info.
     */
    fun formatFlightDetail(
        flight: FlightDto,
        segment: FlightSegment?,
        returnSeg: FlightSegment?,
        latestPrice: PriceHistoryDto?,
    ): String =
        buildString {
            appendLine("Flight: ${flight.id}")
            appendLine("  Investigation: ${flight.investigationSlug}")
            appendLine("  Route:     ${flight.origin} → ${flight.destination}")
            appendLine("  Type:      ${flight.tripType} / ${flight.ticketStructure}")
            appendLine("  Source:    ${flight.source}")
            appendLine("  Status:    ${if (flight.stale) "⚠ STALE (needs price check)" else "✓ Fresh"}")
            flight.aircraftType?.let { appendLine("  Aircraft:  $it") }
            flight.fareBrand?.let { appendLine("  Fare:      $it") }
            flight.disqualified?.let { appendLine("  ❌ Disqualified: $it") }

            if (segment != null) {
                appendLine()
                appendLine("  Outbound:")
                appendLine("    ${segment.departAirport} → ${segment.arriveAirport}")
                appendLine("    Depart:  ${segment.departTime}")
                appendLine("    Arrive:  ${segment.arriveTime}")
                appendLine("    Duration: ${formatDuration(segment.durationMinutes)}")
                appendLine("    Stops:   ${segment.stops}")
                appendSegmentLegs(this, segment)
            }

            if (returnSeg != null) {
                appendLine()
                appendLine("  Return:")
                appendLine("    ${returnSeg.departAirport} → ${returnSeg.arriveAirport}")
                appendLine("    Depart:  ${returnSeg.departTime}")
                appendLine("    Arrive:  ${returnSeg.arriveTime}")
                appendLine("    Duration: ${formatDuration(returnSeg.durationMinutes)}")
                appendLine("    Stops:   ${returnSeg.stops}")
                appendSegmentLegs(this, returnSeg)
            }

            if (!flight.tags.isNullOrBlank()) {
                appendLine()
                appendLine("  Tags:      ${flight.tags}")
            }
            if (!flight.notes.isNullOrBlank()) {
                appendLine("  Notes:     ${flight.notes}")
            }

            // Price section
            appendLine()
            if (latestPrice != null) {
                appendLine("  Latest Price:")
                appendLine("    Amount:  ${formatPrice(latestPrice.amount, latestPrice.currency)}")
                appendLine("    Link:    ${latestPrice.sourceUrl}")
                appendLine("    Checked: ${latestPrice.checkedAt}")
                appendLine("    Market:  ${latestPrice.priceMarket}")
            } else {
                appendLine("  Price:     No price history")
            }

            // Legacy link (if different from price link)
            if (!flight.shareLink.isNullOrBlank() && flight.shareLink != latestPrice?.sourceUrl) {
                appendLine()
                appendLine("  Legacy Link: ${flight.shareLink}")
            }

            appendLine()
            appendLine("  Captured:  ${flight.capturedAt}")
        }

    /**
     * Format a flight with price for display.
     */
    fun formatFlightWithPrice(
        flight: FlightWithPrice,
        segment: FlightSegment?,
        returnSeg: FlightSegment?,
    ): String =
        buildString {
            appendLine("Flight: ${flight.id}")
            appendLine("  Investigation: ${flight.investigationSlug}")
            appendLine(
                "  Price:     ${formatPrice(flight.priceAmount, flight.priceCurrency)} " +
                    "(${flight.priceMarket})",
            )
            appendLine("  Route:     ${flight.origin} → ${flight.destination}")
            appendLine("  Type:      ${flight.tripType} / ${flight.ticketStructure}")
            appendLine("  Source:    ${flight.source}")
            flight.aircraftType?.let { appendLine("  Aircraft:  $it") }
            flight.fareBrand?.let { appendLine("  Fare:      $it") }
            flight.disqualified?.let { appendLine("  ❌ Disqualified: $it") }

            if (segment != null) {
                appendLine()
                appendLine("  Outbound:")
                appendLine("    ${segment.departAirport} → ${segment.arriveAirport}")
                appendLine("    Depart:  ${segment.departTime}")
                appendLine("    Arrive:  ${segment.arriveTime}")
                appendLine("    Duration: ${formatDuration(segment.durationMinutes)}")
                appendLine("    Stops:   ${segment.stops}")
                appendSegmentLegs(this, segment)
            }

            if (returnSeg != null) {
                appendLine()
                appendLine("  Return:")
                appendLine("    ${returnSeg.departAirport} → ${returnSeg.arriveAirport}")
                appendLine("    Depart:  ${returnSeg.departTime}")
                appendLine("    Arrive:  ${returnSeg.arriveTime}")
                appendLine("    Duration: ${formatDuration(returnSeg.durationMinutes)}")
                appendLine("    Stops:   ${returnSeg.stops}")
                appendSegmentLegs(this, returnSeg)
            }

            if (!flight.tags.isNullOrBlank()) {
                appendLine()
                appendLine("  Tags:      ${flight.tags}")
            }
            if (!flight.notes.isNullOrBlank()) {
                appendLine("  Notes:     ${flight.notes}")
            }
            appendLine()
            appendLine("  Link:      ${flight.shareLink}")
            appendLine("  Captured:  ${flight.capturedAt}")
            appendLine("  Last check: ${flight.priceCheckedAt}")
        }

    /**
     * Format a list of flights as a compact table (without prices - use formatFlightWithPriceTable for that).
     */
    fun formatFlightTable(flights: List<FlightDto>): String {
        if (flights.isEmpty()) {
            return "No flights found."
        }

        val headers = listOf("ID", "Cabin", "Airline", "Route", "Layover", "Depart", "Type")
        val rows =
            flights.map { flight ->
                val outbound = parseSegment(flight.outboundJson)
                val returnSeg = flight.returnJson?.let { parseSegment(it) }
                val airline = outbound?.legs?.firstOrNull()?.airline ?: "?"
                val layover = formatLayovers(outbound, returnSeg)
                val departDate = outbound?.departTime?.substring(0, 10) ?: "?"
                val typeFlag = if (flight.tripType == "round_trip") "RT" else "OW"
                listOf(
                    flight.id.take(8),
                    flight.bookingClass ?: "?",
                    airline,
                    "${flight.origin}→${flight.destination}",
                    layover,
                    departDate,
                    typeFlag,
                )
            }

        return formatTable(headers, rows)
    }

    /**
     * Format a list of flights with prices as a compact table.
     */
    fun formatFlightWithPriceTable(flights: List<FlightWithPrice>): String {
        if (flights.isEmpty()) {
            return "No flights found."
        }

        val headers = listOf("ID", "Price", "S", "Cabin", "Airline", "Route", "Layover", "Depart", "Days", "Type")
        val rows =
            flights.map { flight ->
                val outbound = parseSegment(flight.outboundJson)
                val returnSeg = flight.returnJson?.let { parseSegment(it) }
                val airline = outbound?.legs?.firstOrNull()?.airline ?: "?"
                val layover = formatLayovers(outbound, returnSeg)
                val departDate = outbound?.departTime?.substring(0, 10) ?: "?"
                val typeFlag = if (flight.tripType == "round_trip") "RT" else "OW"
                val staleFlag = if (flight.flight.stale) "!" else "✓"
                val days =
                    if (outbound != null && returnSeg != null) {
                        try {
                            val d1 = java.time.ZonedDateTime.parse(outbound.departTime)
                            val d2 = java.time.ZonedDateTime.parse(returnSeg.departTime)
                            java.time.Duration
                                .between(d1, d2)
                                .toDays()
                                .toString()
                        } catch (_: Exception) {
                            "?"
                        }
                    } else {
                        "—"
                    }
                listOf(
                    flight.id.take(8),
                    formatPrice(flight.priceAmount, flight.priceCurrency),
                    staleFlag,
                    flight.bookingClass ?: "?",
                    airline,
                    "${flight.origin}→${flight.destination}",
                    layover,
                    departDate,
                    days,
                    typeFlag,
                )
            }

        return formatTable(headers, rows)
    }

    /**
     * Format layover times for display.
     * For round trips: "1h / 2h30m" (out / return)
     * For one ways: "1h" or "—" if nonstop
     */
    fun formatLayovers(
        outbound: FlightSegment?,
        returnSeg: FlightSegment?,
    ): String {
        val outLayover = outbound?.let { calcLayoverMinutes(it) } ?: 0
        val retLayover = returnSeg?.let { calcLayoverMinutes(it) } ?: 0

        return if (returnSeg != null) {
            // Round trip - show both
            "${formatLayover(outLayover)} / ${formatLayover(retLayover)}"
        } else {
            // One way
            formatLayover(outLayover)
        }
    }

    /**
     * Format a single layover value.
     */
    fun formatLayover(minutes: Int): String = if (minutes == 0) "—" else formatDuration(minutes)

    /**
     * Format layovers for a single segment with airport codes.
     * Returns e.g. "2h 10m at CDG, 1h 30m at NRT" or "" if nonstop.
     */
    fun formatLayovers(segment: FlightSegment?): String {
        if (segment == null) return ""
        val legs = segment.legs
        if (legs.size <= 1) return ""
        val parts = mutableListOf<String>()
        for (i in 0 until legs.size - 1) {
            try {
                val arrive = java.time.ZonedDateTime.parse(legs[i].arriveTime)
                val depart = java.time.ZonedDateTime.parse(legs[i + 1].departTime)
                val minutes =
                    java.time.Duration
                        .between(arrive, depart)
                        .toMinutes()
                        .toInt()
                parts.add("${formatDuration(minutes)} at ${legs[i].arriveAirport}")
            } catch (_: Exception) {
                parts.add("? at ${legs[i].arriveAirport}")
            }
        }
        return parts.joinToString(", ")
    }

    /**
     * Calculate total layover time in minutes from a segment.
     */
    fun calcLayoverMinutes(segment: FlightSegment): Int {
        if (segment.legs.size <= 1) return 0

        var totalLayover = 0
        for (i in 0 until segment.legs.size - 1) {
            val arriveTime = java.time.OffsetDateTime.parse(segment.legs[i].arriveTime)
            val departTime = java.time.OffsetDateTime.parse(segment.legs[i + 1].departTime)
            val layoverMinutes =
                java.time.Duration
                    .between(arriveTime, departTime)
                    .toMinutes()
                    .toInt()
            totalLayover += layoverMinutes
        }

        return totalLayover
    }

    /**
     * Append segment legs with layover times between them.
     */
    private fun appendSegmentLegs(
        sb: StringBuilder,
        segment: FlightSegment,
    ) {
        segment.legs.forEachIndexed { i, leg ->
            sb.appendLine(
                "    Leg ${i + 1}: ${leg.airlineCode}${leg.flightNumber} " +
                    "${leg.departAirport}→${leg.arriveAirport} (${leg.airline})",
            )
            sb.appendLine(
                "            ${formatTime(leg.departTime)} → ${formatTime(leg.arriveTime)} " +
                    "(${formatDuration(leg.durationMinutes)})",
            )

            // Show layover if there's a next leg
            if (i < segment.legs.size - 1) {
                val nextLeg = segment.legs[i + 1]
                val arriveTime = java.time.OffsetDateTime.parse(leg.arriveTime)
                val departTime = java.time.OffsetDateTime.parse(nextLeg.departTime)
                val layoverMinutes =
                    java.time.Duration
                        .between(arriveTime, departTime)
                        .toMinutes()
                        .toInt()
                sb.appendLine("            ⏱ ${formatDuration(layoverMinutes)} layover in ${leg.arriveAirport}")
            }
        }
    }

    /**
     * Format a timestamp to just time portion.
     */
    fun formatTime(isoTime: String): String =
        try {
            val dt = java.time.OffsetDateTime.parse(isoTime)
            dt.toLocalTime().toString().substring(0, 5)
        } catch (_: Exception) {
            isoTime
        }

    /**
     * Parse a FlightSegment from JSON.
     */
    fun parseSegment(json: String): FlightSegment? =
        try {
            compactJson.decodeFromString<FlightSegment>(json)
        } catch (_: Exception) {
            null
        }

    /**
     * Format price history as a table.
     */
    fun formatPriceHistory(history: List<PriceHistoryDto>): String {
        if (history.isEmpty()) {
            return "No price history found."
        }

        val headers = listOf("#", "Date", "Price", "Change", "Source", "Source URL")
        var prevAmount: Double? = null
        val rows =
            history.map { entry ->
                val change =
                    if (prevAmount != null) {
                        val diff = entry.amount - prevAmount!!
                        when {
                            diff > 0 -> "↑ +${formatPrice(diff, entry.currency)}"
                            diff < 0 -> "↓ -${formatPrice(-diff, entry.currency)}"
                            else -> "—"
                        }
                    } else {
                        "—"
                    }
                prevAmount = entry.amount
                val urlDisplay = if (entry.sourceUrl.isNotBlank()) entry.sourceUrl else "—"
                listOf(
                    (entry.id ?: 0).toString(),
                    entry.checkedAt.substring(0, 19).replace("T", " "),
                    formatPrice(entry.amount, entry.currency),
                    change,
                    entry.source,
                    urlDisplay,
                )
            }

        return formatTable(headers, rows)
    }

    /**
     * Format a price with currency symbol.
     */
    fun formatPrice(
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
        return when (currency) {
            "JPY" -> "$symbol${amount.toLong()}"
            else -> "$symbol%.2f".format(amount)
        }
    }

    /**
     * Format duration in minutes to human readable.
     */
    fun formatDuration(minutes: Int): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return when {
            hours == 0 -> "${mins}m"
            mins == 0 -> "${hours}h"
            else -> "${hours}h ${mins}m"
        }
    }

    /**
     * Format data as JSON.
     */
    inline fun <reified T> toJson(
        data: T,
        pretty: Boolean = true,
    ): String = if (pretty) json.encodeToString(data) else compactJson.encodeToString(data)

    /**
     * Parse tags from JSON string.
     */
    fun parseTags(tagsJson: String): List<String> =
        try {
            compactJson.decodeFromString<List<String>>(tagsJson)
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * Parse stops from outbound JSON.
     */
    fun parseStops(outboundJson: String): Int =
        try {
            val segment = compactJson.decodeFromString<FlightSegment>(outboundJson)
            segment.stops
        } catch (_: Exception) {
            -1
        }

    /**
     * Encode tags to JSON string.
     */
    fun encodeTags(tags: List<String>): String = compactJson.encodeToString(tags)

    /**
     * Format a simple ASCII table.
     */
    fun formatTable(
        headers: List<String>,
        rows: List<List<String>>,
    ): String {
        if (rows.isEmpty()) {
            return headers.joinToString(" | ")
        }

        // Calculate column widths
        val widths =
            headers.indices.map { col ->
                maxOf(
                    headers[col].length,
                    rows.maxOfOrNull { it.getOrElse(col) { "" }.length } ?: 0,
                )
            }

        return buildString {
            // Header row
            val headerLine =
                headers
                    .mapIndexed { i, h -> h.padEnd(widths[i]) }
                    .joinToString(" │ ")
            appendLine(headerLine)

            // Separator
            val separator = widths.joinToString("─┼─") { "─".repeat(it) }
            appendLine(separator)

            // Data rows
            rows.forEach { row ->
                val dataLine =
                    row
                        .mapIndexed { i, cell ->
                            cell.padEnd(widths.getOrElse(i) { cell.length })
                        }.joinToString(" │ ")
                appendLine(dataLine)
            }
        }
    }

    /**
     * Print success message.
     */
    fun success(message: String) {
        println("✓ $message")
    }

    /**
     * Print error message to stderr.
     */
    fun error(message: String) {
        System.err.println("✗ $message")
    }

    /**
     * Print warning message.
     */
    fun warn(message: String) {
        println("⚠ $message")
    }
}
