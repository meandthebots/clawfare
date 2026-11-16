package com.clawfare.search

import kotlinx.serialization.Serializable

/** What the browser submits. Secrets are never part of this object. */
@Serializable
data class SearchQuery(
    val origins: List<String> = emptyList(),
    val destinations: List<String> = emptyList(),
    val outboundFrom: String = "",
    val outboundTo: String = "",
    val returnFrom: String? = null,
    val returnTo: String? = null,
    /** When set, each outbound date is paired with outbound + this many nights. */
    val nights: Int? = null,
    val tripType: String = "round_trip",
    val adults: Int = 1,
    /** Cabin requested from the provider (the long-haul cabin). */
    val cabin: String = "premium_economy",
    /** Domestic / short-haul legs may be this cabin or higher. */
    val cabinShortHaul: String = "economy",
    /** Unknown segment cabin must fail eligibility, not inherit a search-level cabin. */
    val strictPerLegCabin: Boolean = false,
    /** Reject missing operating carrier on individual segments. */
    val strictOperatingCarrier: Boolean = false,
    val checkedBags: Int = 0,
    /** Default traveller preference. Set to an empty list to include LOT. */
    val excludedAirlines: List<String> = listOf("LO"),
    val currency: String = "GBP",
    /** Google Flights country market, e.g. uk, fr, de, nl, dk. */
    val market: String = "uk",
    /** Connection airports to exclude even when their country is unknown. */
    val excludedTransitAirports: List<String> = emptyList(),
    /** ISO country/region codes; unknown connection airports fail closed. */
    val excludedTransitCountries: List<String> = emptyList(),
    /** Hard cap including return-lookup upper bound; null is unrestricted. */
    val maxLiveCalls: Int? = null,
    val providers: List<String> = listOf("serpapi", "searchapi", "travelpayouts", "duffel"),
    val nonstopOnly: Boolean = false,
    /** How many cheapest outbounds per date-pair to expand with a return lookup. */
    val returnChoices: Int = 3,
    val fetchReturns: Boolean = true,
    val cacheOnly: Boolean = false,
    val confirmed: Boolean = false,
    /** Investigation slug to write into. Generated when omitted. */
    val investigation: String? = null,
)

@Serializable
data class FilterSpec(
    val maxStops: Int? = null,
    val maxLayoverMinutes: Int? = null,
    val excludeOutboundDates: List<String> = emptyList(),
    val excludeReturnDates: List<String> = emptyList(),
    val europeanPremiumOnly: Boolean = false,
    /** Every marketing/operating code on the itinerary must be in this list when it is non-empty. */
    val airlineAllow: List<String> = emptyList(),
    val airlineBlock: List<String> = emptyList(),
    val maxTravelMinutes: Int? = null,
    /** HH:mm, compared against the first outbound departure. */
    val outboundDepartAfter: String? = null,
    val outboundDepartBefore: String? = null,
    val returnDepartAfter: String? = null,
    val nonstopOnly: Boolean = false,
    val hideMock: Boolean = true,
    val verifiedCashRefundOnly: Boolean = false,
    val cabin: String? = null,
    val nights: Int? = null,
    val origin: String? = null,
)

@Serializable
data class Leg(
    val flightNumber: String,
    val airline: String,
    val airlineCode: String,
    val operatingCarrier: String,
    val from: String,
    val to: String,
    val departTime: String,
    val arriveTime: String,
    val departDate: String,
    val arriveDate: String,
    val durationMinutes: Int? = null,
    val cabin: String? = null,
    val aircraft: String? = null,
    /** False if the operating carrier was inferred from marketing data instead of explicitly reported. */
    val operatingCarrierVerified: Boolean = true,
)

@Serializable
data class Layover(
    val airport: String,
    val minutes: Int,
)

@Serializable
data class Direction(
    val legs: List<Leg> = emptyList(),
    val layovers: List<Layover> = emptyList(),
    val durationMinutes: Int? = null,
    val stops: Int = 0,
)

@Serializable
data class BookLink(
    val kind: String,
    val label: String,
    val url: String,
)
