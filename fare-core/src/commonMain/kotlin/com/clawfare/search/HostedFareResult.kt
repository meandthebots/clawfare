package com.clawfare.search

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A typed, token-free public result for the existing browser ResultSet DTO.
 * Provider I/O and identity are server responsibilities. No booking options
 * or verified refund rights are invented from a Google Flights base search.
 *
 * This initial assembler supports one-way search results ONLY. Its caller
 * must not present an outbound quote as a complete return itinerary.
 */
@Serializable
data class HostedFareFx(
    val asOf: String,
    /** Original-currency units for one GBP; supplied with dated evidence. */
    val perGbp: Map<String, Double>,
) {
    init {
        require(runCatching { LocalDate.parse(asOf) }.isSuccess) { "FX date must be a calendar date" }
        require(perGbp.size <= 32) { "Too many FX rates" }
        require(perGbp.all { (currency, rate) ->
            currency.matches(Regex("[A-Z]{3}")) && rate.isFinite() && rate > 0.0
        }) { "FX rates must be positive, finite and currency-coded" }
        require(perGbp["GBP"] == 1.0) { "GBP reference rate must equal one" }
    }
}

@Serializable
data class HostedFareResultRequest(
    val investigationId: String,
    val query: SearchQuery,
    val fetchedAt: String,
    val drafts: List<GoogleFareDraft>,
    val fx: HostedFareFx,
    val airportCountries: Map<String, String> = emptyMap(),
)

@Serializable
data class HostedFareItinerary(
    val id: String,
    /** Per passenger GBP; null when a dated conversion is unavailable. */
    val pricePerPerson: Double? = null,
    /** Per passenger original currency amount when not GBP. */
    val priceOriginal: Double? = null,
    val priceCurrency: String,
    val passengers: Int,
    val cabin: String,
    val cabinKey: String,
    val euPremium: Boolean,
    val origin: String,
    val destination: String,
    val outboundDate: String,
    val returnDate: String? = null,
    val airlineSummary: String,
    val outbound: Direction,
    val returning: Direction? = null,
    val returnMissing: Boolean = false,
    val stopsOut: Int,
    val stopsRet: Int? = null,
    val layoverMinutes: List<Int> = emptyList(),
    val maxLayoverMinutes: Int? = null,
    val travelMinutes: Int? = null,
    val outboundDepart: String? = null,
    val returnDepart: String? = null,
    val airlineCodes: List<String> = emptyList(),
    val source: String,
    val fareBrand: String? = null,
    val cashRefundProofVerified: Boolean = false,
    val sourceLabel: String,
    val livePrice: Boolean,
    val fetchedAt: String,
    val links: List<BookLink> = emptyList(),
    val notes: String = "Supplier and cash-refund terms have not been verified.",
    val assumedReturn: Boolean = false,
)

@Serializable
data class HostedFareNote(val title: String, val body: String, val warn: Boolean = false)

@Serializable
data class HostedFareResultSet(
    val id: String,
    val title: String,
    val subtitle: String,
    val fetchedAt: String,
    val currency: String = "GBP",
    val checkedBags: Int,
    val passengers: Int,
    val itineraries: List<HostedFareItinerary>,
    val addons: List<HostedFareItinerary> = emptyList(),
    val cells: List<JsonObject> = emptyList(),
    val vendors: List<JsonObject> = emptyList(),
    val sellerOptions: List<JsonObject> = emptyList(),
    val vendorIntro: List<HostedFareNote> = emptyList(),
    val headerNotes: List<HostedFareNote> = emptyList(),
    val suggestedFilters: FilterSpec = FilterSpec(),
    val warnings: List<String> = emptyList(),
    val fx: HostedFareFx,
    val query: SearchQuery,
)

@Serializable
data class HostedFareAssembly(
    val result: HostedFareResultSet,
    val returned: Int,
    val eligible: Int,
    val excludedByReason: Map<String, Int>,
)

private val INVESTIGATION_ID = Regex("[a-z0-9][a-z0-9_-]{0,99}")
private val UTC_CAPTURE = Regex("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(?:\\.\\d{1,3})?Z")

/**
 * Reuse the common eligibility and fare-direction rules. The hosting adapter
 * supplies server-owned FX reference and a trusted country lookup; neither is
 * derived from client JSON at the public HTTP boundary.
 */
fun assembleHostedOneWayResult(request: HostedFareResultRequest): HostedFareAssembly {
    require(INVESTIGATION_ID.matches(request.investigationId)) { "Invalid investigation ID" }
    require(UTC_CAPTURE.matches(request.fetchedAt)) { "Invalid UTC capture timestamp" }
    require(request.query.tripType == "one_way") { "Round-trip assembly is not yet supported" }
    require(request.query.providers == listOf("serpapi")) { "Only SerpApi base searches are supported" }
    require(request.query.adults in 1..9) { "Invalid passenger count" }
    require(request.query.origins.isNotEmpty() && request.query.destinations.isNotEmpty()) {
        "Missing route"
    }
    require(request.drafts.size <= 200) { "More fares than the hosted result limit" }
    require(request.fx.asOf <= request.fetchedAt.take(10)) { "Future FX date is invalid" }

    val exclusions = mutableMapOf<String, Int>()
    val candidates = mutableListOf<GoogleFareDraft>()
    for (draft in request.drafts) {
        require(draft.source == "serpapi" && draft.passengers == request.query.adults &&
            draft.priceCurrency == request.query.currency &&
            draft.returning == null && !draft.returnMissing
        ) { "Draft contradicts canonical one-way SerpApi search" }
        val legs = draft.outbound.legs
        require(legs.isNotEmpty() &&
            legs.first().from in request.query.origins &&
            legs.last().to in request.query.destinations &&
            legs.first().departDate >= request.query.outboundFrom &&
            legs.first().departDate <= request.query.outboundTo &&
            draft.observedAt == request.fetchedAt
        ) { "Draft is outside the requested route, dates or capture" }
        val decision = evaluatePortableEligibility(
            EligibilityEvaluationRequest(
                query = request.query,
                evidence = EligibilityEvidence(outbound = draft.outbound),
                airportCountries = request.airportCountries,
            ),
        )
        if (!decision.eligible) {
            val reason = requireNotNull(decision.rejectionReason)
            exclusions[reason] = (exclusions[reason] ?: 0) + 1
        } else {
            candidates += draft
        }
    }

    // One flight family may be returned several times at different prices.
    // Never sum variant prices or multiply the Google party total twice.
    val ranked = candidates
        .sortedWith(compareBy<GoogleFareDraft> { it.originalPerPersonPrice ?: Double.POSITIVE_INFINITY }
            .thenBy { it.id })
        .distinctBy { it.id }
    val currency = request.query.currency
    val rate = request.fx.perGbp[currency]
    val missingFx = currency != "GBP" && rate == null && ranked.any { it.originalPerPersonPrice != null }
    val fares = ranked.map { draft ->
        val legs = draft.outbound.legs
        val layovers = draft.outbound.layovers.map { it.minutes }
        val codes = legs.map { it.airlineCode.uppercase() }.filter { it.isNotBlank() }.distinct()
        val names = legs.map { it.airline.ifBlank { it.airlineCode } }
            .filter { it.isNotBlank() }.distinct()
        HostedFareItinerary(
            id = draft.id,
            pricePerPerson = draft.originalPerPersonPrice?.let {
                FareMath.toGbpOrNull(it, rate)
            },
            priceOriginal = if (currency == "GBP") null else draft.originalPerPersonPrice,
            priceCurrency = currency,
            passengers = draft.passengers,
            cabin = cabinLabelOf(cabinKeyOf(draft.cabinRaw)),
            cabinKey = cabinKeyOf(draft.cabinRaw),
            euPremium = euPremium(cabinKeyOf(draft.cabinRaw), legs),
            origin = legs.first().from,
            destination = legs.last().to,
            outboundDate = legs.first().departDate,
            airlineSummary = names.joinToString(" / "),
            outbound = draft.outbound,
            stopsOut = draft.outbound.stops,
            layoverMinutes = layovers,
            maxLayoverMinutes = layovers.maxOrNull(),
            travelMinutes = draft.outbound.durationMinutes,
            outboundDepart = legs.first().departTime,
            airlineCodes = codes,
            source = draft.source,
            fareBrand = draft.fareBrand,
            sourceLabel = draft.sourceLabel,
            livePrice = draft.livePrice,
            fetchedAt = draft.observedAt,
        )
    }
    val route = request.query.origins.joinToString("/") + " → " +
        request.query.destinations.joinToString("/")
    val warnings = listOfNotNull(
        "Only observed SerpApi one-way base fares are shown; this is not exhaustive availability.",
        if (missingFx) "No dated GBP conversion for " + currency + "; original prices are retained." else null,
        if (fares.isEmpty()) "No eligible fares in the received observations; this is not proof no flights exist." else null,
    )
    return HostedFareAssembly(
        result = HostedFareResultSet(
            id = request.investigationId,
            title = route + " · " + request.query.outboundFrom,
            subtitle = "One-way fare observations, not verified booking offers",
            fetchedAt = request.fetchedAt,
            checkedBags = request.query.checkedBags,
            passengers = request.query.adults,
            itineraries = fares,
            warnings = warnings,
            fx = request.fx,
            query = request.query,
        ),
        returned = request.drafts.size,
        eligible = fares.size,
        excludedByReason = exclusions.toMap(),
    )
}
