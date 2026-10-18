package com.clawfare.export

import com.clawfare.db.FlightQueries
import com.clawfare.db.FlightWithPrice
import com.clawfare.db.PriceHistoryQueries
import com.clawfare.model.FlightEntry
import com.clawfare.model.FlightSegment
import com.clawfare.model.TicketStructure
import com.clawfare.model.TripType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Export flights to JSON format matching schema.md specification.
 */
object JsonExporter {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = false
        }

    private val jsonCompact =
        Json {
            prettyPrint = false
            encodeDefaults = false
        }

    /**
     * Export all flights for an investigation to JSON.
     *
     * @param investigationSlug The investigation to export flights from
     * @param prettyPrint Whether to format the JSON output (default: true)
     * @return JSON string containing array of FlightEntry objects
     */
    fun exportByInvestigation(
        investigationSlug: String,
        prettyPrint: Boolean = true,
    ): String {
        val flightsWithPrices = FlightQueries.listWithPrices(investigationSlug)
        return exportFlightsWithPrices(flightsWithPrices, prettyPrint)
    }

    /**
     * Export all flights in the database to JSON.
     *
     * @param prettyPrint Whether to format the JSON output (default: true)
     * @return JSON string containing array of FlightEntry objects
     */
    fun exportAll(prettyPrint: Boolean = true): String {
        // Get all flights, then get their prices
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
        return exportFlightsWithPrices(flightsWithPrices, prettyPrint)
    }

    /**
     * Export a list of FlightWithPrice objects to JSON.
     *
     * @param flights List of FlightWithPrice objects to export
     * @param prettyPrint Whether to format the JSON output
     * @return JSON string containing array of FlightEntry objects
     */
    fun exportFlightsWithPrices(
        flights: List<FlightWithPrice>,
        prettyPrint: Boolean = true,
    ): String {
        val entries = flights.map { flightWithPriceToEntry(it) }
        return if (prettyPrint) {
            json.encodeToString(entries)
        } else {
            jsonCompact.encodeToString(entries)
        }
    }

    /**
     * Export a single flight to JSON.
     *
     * @param flightId The ID of the flight to export
     * @param prettyPrint Whether to format the JSON output
     * @return JSON string of FlightEntry, or null if not found
     */
    fun exportById(
        flightId: String,
        prettyPrint: Boolean = true,
    ): String? {
        val flightWithPrice = FlightQueries.getWithPrice(flightId) ?: return null
        val entry = flightWithPriceToEntry(flightWithPrice)
        return if (prettyPrint) {
            json.encodeToString(entry)
        } else {
            jsonCompact.encodeToString(entry)
        }
    }

    /**
     * Convert a FlightWithPrice to a FlightEntry.
     */
    fun flightWithPriceToEntry(fwp: FlightWithPrice): FlightEntry {
        val outbound = json.decodeFromString<FlightSegment>(fwp.outboundJson)
        val returnSegment = fwp.returnJson?.let { json.decodeFromString<FlightSegment>(it) }
        val tags = fwp.tags?.let { json.decodeFromString<List<String>>(it) }

        return FlightEntry(
            id = fwp.id,
            shareLink = fwp.shareLink,
            source = fwp.source,
            tripType =
                when (fwp.tripType) {
                    "round_trip" -> TripType.ROUND_TRIP
                    "one_way" -> TripType.ONE_WAY
                    else -> TripType.ONE_WAY
                },
            ticketStructure =
                when (fwp.ticketStructure) {
                    "single" -> TicketStructure.SINGLE
                    "two_one_ways" -> TicketStructure.TWO_ONE_WAYS
                    else -> TicketStructure.SINGLE
                },
            priceCurrency = fwp.priceCurrency,
            priceAmount = fwp.priceAmount,
            priceMarket = fwp.priceMarket,
            capturedAt = fwp.capturedAt,
            priceCheckedAt = fwp.priceCheckedAt,
            origin = fwp.origin,
            destination = fwp.destination,
            outbound = outbound,
            returnSegment = returnSegment,
            notes = fwp.notes,
            tags = tags,
            bookingClass = fwp.bookingClass,
            cabinMixed = fwp.cabinMixed,
        )
    }
}
