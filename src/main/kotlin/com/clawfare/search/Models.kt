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
    val checkedBags: Int = 0,
    val currency: String = "GBP",
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

@Serializable
data class Itinerary(
    val id: String,
    /** Per person, in GBP when a rate is known. */
    val pricePerPerson: Double? = null,
    val priceOriginal: Double? = null,
    val priceCurrency: String = "GBP",
    val passengers: Int = 1,
    val cabin: String = "",
    val cabinKey: String = "economy",
    val euPremium: Boolean = false,
    val origin: String = "",
    val destination: String = "",
    val returnFrom: String? = null,
    val outboundDate: String = "",
    val returnDate: String? = null,
    val nights: Int? = null,
    val airlineSummary: String = "",
    val outbound: Direction = Direction(),
    val returning: Direction? = null,
    val returnMissing: Boolean = false,
    val stopsOut: Int = 0,
    val stopsRet: Int? = null,
    val layoverMinutes: List<Int> = emptyList(),
    val maxLayoverMinutes: Int? = null,
    /** Real block time when the provider reported it; never a local-clock guess. */
    val travelMinutes: Int? = null,
    val outboundDepart: String? = null,
    val returnDepart: String? = null,
    val airlineCodes: List<String> = emptyList(),
    val source: String = "",
    /** live, cached, mock, or sample. */
    val sourceLabel: String = "",
    /** False for Duffel test offers and Travelpayouts cached fares. */
    val livePrice: Boolean = false,
    val fetchedAt: String? = null,
    val links: List<BookLink> = emptyList(),
    val prevPrice: Double? = null,
    val notes: String = "",
    val assumedReturn: Boolean = false,
)

@Serializable
data class ComboCell(
    val primaryId: String,
    val addonId: String? = null,
    /** ok, catch, or no. */
    val status: String,
    val total: Double,
    val prevTotal: Double? = null,
    val reasonsNo: List<String> = emptyList(),
    val catches: List<String> = emptyList(),
    val oks: List<String> = emptyList(),
)

@Serializable
data class VendorRow(
    val vendor: String,
    val market: String,
    val marketLabel: String,
    val currency: String,
    val fareBrand: String = "",
    /** airline, trusted, caution, risky. */
    val trust: String = "caution",
    val trustLabel: String = "OTA · caution",
    val note: String = "",
    val roundTrip: Double? = null,
    val oneWayOut: Double? = null,
    val oneWayBack: Double? = null,
    val roundTripWithBag: Double? = null,
    val oneWaysWithBag: Double? = null,
    val allInGbp: Double? = null,
    val how: String = "",
    val bags: String = "",
    val mix: Boolean = false,
    val fresh: Boolean = true,
    val priceChangeGbp: Int? = null,
    val isNew: Boolean = false,
    val fetchedAt: String? = null,
    val links: List<BookLink> = emptyList(),
)

@Serializable
data class NoteCard(
    val title: String,
    val body: String,
    val warn: Boolean = false,
)

@Serializable
data class FxInfo(
    val perGbp: Map<String, Double> = Fx.PER_GBP,
    val asOf: String = Fx.AS_OF,
)

@Serializable
data class ResultSet(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val fetchedAt: String? = null,
    val currency: String = "GBP",
    val checkedBags: Int = 0,
    val passengers: Int = 1,
    val itineraries: List<Itinerary> = emptyList(),
    val addons: List<Itinerary> = emptyList(),
    val cells: List<ComboCell> = emptyList(),
    val vendors: List<VendorRow> = emptyList(),
    val vendorIntro: List<NoteCard> = emptyList(),
    val headerNotes: List<NoteCard> = emptyList(),
    val suggestedFilters: FilterSpec = FilterSpec(),
    val warnings: List<String> = emptyList(),
    val fx: FxInfo = FxInfo(),
    val scenarioLabel: String? = null,
    val query: SearchQuery? = null,
    /** Itinerary the seller rows belong to, when the page is comparing sellers. */
    val vendorItineraryId: String? = null,
)

/** Editorial copy stored on the investigation, separate from the fare rows. */
@Serializable
data class Presentation(
    val title: String,
    val subtitle: String = "",
    val headerNotes: List<NoteCard> = emptyList(),
    val vendorIntro: List<NoteCard> = emptyList(),
    val suggestedFilters: FilterSpec = FilterSpec(),
    val warnings: List<String> = emptyList(),
    val fetchedAt: String? = null,
)

@Serializable
data class FlightTokens(
    val provider: String,
    val departureToken: String? = null,
    val bookingToken: String? = null,
    /** Params needed to repeat the return / booking call, without any secret. */
    val params: Map<String, String> = emptyMap(),
)

@Serializable
data class StoredResult(
    val result: ResultSet,
    val tokens: Map<String, FlightTokens> = emptyMap(),
)

@Serializable
data class QuotaView(
    val provider: String,
    val configured: Boolean,
    val remaining: Int? = null,
    val limit: Int? = null,
    val used: Int? = null,
    val note: String? = null,
)

@Serializable
data class Estimate(
    val liveCalls: Int,
    val cachedCalls: Int,
    val skippedCalls: Int,
    val returnLookupsUpperBound: Int,
    val lines: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val quota: List<QuotaView> = emptyList(),
)

@Serializable
data class SearchResponse(
    val ok: Boolean,
    val needsConfirmation: Boolean = false,
    val estimate: Estimate? = null,
    val result: ResultSet? = null,
    val error: String? = null,
)

@Serializable
data class VendorRequest(
    val itineraryId: String,
    val markets: List<String> = listOf("uk"),
    val cacheOnly: Boolean = false,
    val confirmed: Boolean = false,
)

@Serializable
data class TrackRequest(
    val itineraryId: String,
    val tripSlug: String,
)

@Serializable
data class StatusResponse(
    val cacheDir: String,
    val searchesDir: String,
    val providers: List<QuotaView>,
)
