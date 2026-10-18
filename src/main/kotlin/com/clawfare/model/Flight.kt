package com.clawfare.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Individual flight leg - a single flight segment within a connection.
 */
@Serializable
data class FlightLeg(
    @SerialName("flight_number")
    val flightNumber: String,
    val airline: String,
    @SerialName("airline_code")
    val airlineCode: String,
    @SerialName("cabin_class")
    val cabinClass: String? = null,
    @SerialName("depart_airport")
    val departAirport: String,
    @SerialName("depart_time")
    val departTime: String,
    @SerialName("arrive_airport")
    val arriveAirport: String,
    @SerialName("arrive_time")
    val arriveTime: String,
    @SerialName("duration_minutes")
    val durationMinutes: Int,
    val aircraft: String? = null,
    /** Carrier that flies the aircraft when it differs from the marketing airline. */
    @SerialName("operating_carrier")
    val operatingCarrier: String? = null,
    @SerialName("operating_carrier_code")
    val operatingCarrierCode: String? = null,
)

/**
 * Flight segment - either outbound or return portion of a trip.
 */
@Serializable
data class FlightSegment(
    @SerialName("depart_airport")
    val departAirport: String,
    @SerialName("depart_time")
    val departTime: String,
    @SerialName("arrive_airport")
    val arriveAirport: String,
    @SerialName("arrive_time")
    val arriveTime: String,
    @SerialName("duration_minutes")
    val durationMinutes: Int,
    val stops: Int,
    val legs: List<FlightLeg>,
    /** Provider-reported layovers, used when clock times are not enough to derive them. */
    val layovers: List<StoredLayover>? = null,
)

/**
 * A connection wait that could not be derived from the leg times.
 */
@Serializable
data class StoredLayover(
    val airport: String,
    val minutes: Int,
)

/**
 * Price history point for tracking price changes over time.
 */
@Serializable
data class PricePoint(
    val amount: Double,
    val currency: String,
    @SerialName("checked_at")
    val checkedAt: String,
)

/**
 * Trip type enumeration.
 */
@Serializable
enum class TripType {
    @SerialName("round_trip")
    ROUND_TRIP,

    @SerialName("one_way")
    ONE_WAY,
}

/**
 * Ticket structure - single booking or two separate one-ways.
 */
@Serializable
enum class TicketStructure {
    @SerialName("single")
    SINGLE,

    @SerialName("two_one_ways")
    TWO_ONE_WAYS,
}

/**
 * Complete flight entry - the main entity for the database.
 */
@Serializable
data class FlightEntry(
    val id: String,
    @SerialName("share_link")
    val shareLink: String,
    val source: String,
    @SerialName("trip_type")
    val tripType: TripType,
    @SerialName("ticket_structure")
    val ticketStructure: TicketStructure,
    @SerialName("price_currency")
    val priceCurrency: String,
    @SerialName("price_amount")
    val priceAmount: Double,
    @SerialName("price_market")
    val priceMarket: String,
    @SerialName("captured_at")
    val capturedAt: String,
    @SerialName("price_checked_at")
    val priceCheckedAt: String,
    val origin: String,
    val destination: String,
    val outbound: FlightSegment,
    @SerialName("return")
    val returnSegment: FlightSegment? = null,
    val notes: String? = null,
    val tags: List<String>? = null,
    @SerialName("booking_class")
    val bookingClass: String? = null,
    @SerialName("cabin_mixed")
    val cabinMixed: Boolean? = null,
    @SerialName("screenshot_path")
    val screenshotPath: String? = null,
    @SerialName("outbound_link")
    val outboundLink: String? = null,
    @SerialName("return_link")
    val returnLink: String? = null,
    @SerialName("price_history")
    val priceHistory: List<PricePoint>? = null,
)
