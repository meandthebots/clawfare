package com.clawfare.search

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A typed, token-free public result for the existing browser ResultSet DTO.
 * Provider I/O and identity are server responsibilities. No booking options
 * or verified refund rights are invented from a Google Flights base search.
 *
 * One-way and verified round-trip drafts share an identical output model.
 * Unresolved return lookups are counted as incomplete; a priced outbound-only
 * fare is never represented as a complete round-trip fare.
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
    val returnFrom: String? = null,
    val nights: Int? = null,
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
    val validUntil: String? = null,
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
    /** Outbound selections with no verified corresponding return offer. */
    val unresolvedReturns: Int = 0,
)

private val INVESTIGATION_ID = Regex("[a-z0-9][a-z0-9_-]{0,99}")
private val UTC_CAPTURE = Regex("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(?:\\.\\d{1,3})?Z")

/**
 * Reuse the common eligibility and fare-direction rules. The hosting adapter
 * supplies server-owned FX reference and a trusted country lookup; neither is
 * derived from client JSON at the public HTTP boundary.
 */
fun assembleHostedOneWayResult(request: HostedFareResultRequest): HostedFareAssembly =
    assembleHostedResult(request, "one_way")

/**
 * Round-trip fares require a return-selection result covering the matching
 * airport pair and exact planned return date. An outbound-only offer, even
 * when Google quotes an outbound party price, is not a round-trip quote.
 */
fun assembleHostedRoundTripResult(request: HostedFareResultRequest): HostedFareAssembly =
    assembleHostedResult(request, "round_trip")

private fun assembleHostedResult(
    request: HostedFareResultRequest,
    expectedTrip: String,
): HostedFareAssembly {
    require(INVESTIGATION_ID.matches(request.investigationId)) { "Invalid investigation ID" }
    require(UTC_CAPTURE.matches(request.fetchedAt)) { "Invalid UTC capture timestamp" }
    require(request.query.tripType == expectedTrip) { "Incorrect trip type for hosted assembler" }
    if (expectedTrip == "round_trip") {
        require(request.query.fetchReturns) { "Round-trip fares require verified return selection" }
    }
    val provider = request.query.providers.singleOrNull()
    require(provider in setOf("serpapi", "searchapi", "duffel")) { "Unsupported hosted itinerary provider" }
    require(request.query.adults in 1..9) { "Invalid passenger count" }
    require(request.query.origins.isNotEmpty() && request.query.destinations.isNotEmpty()) {
        "Missing route"
    }
    require(request.drafts.size <= 200) { "More fares than the hosted result limit" }
    require(request.fx.asOf <= request.fetchedAt.take(10)) { "Future FX date is invalid" }

    val exclusions = mutableMapOf<String, Int>()
    val candidates = mutableListOf<GoogleFareDraft>()
    val pairs = if (expectedTrip == "round_trip") datePairs(request.query).toSet() else emptySet()
    var unresolvedReturns = 0
    for (draft in request.drafts) {
        require(draft.source == provider && draft.passengers == request.query.adults &&
            (provider == "duffel" || draft.priceCurrency == request.query.currency)
        ) { "Draft contradicts canonical hosted search" }
        if (expectedTrip == "one_way") {
            require(draft.returning == null && !draft.returnMissing &&
                draft.returnDateHint == null
            ) { "One-way draft unexpectedly has a return" }
        }
        val legs = draft.outbound.legs
        require(legs.isNotEmpty() &&
            legs.first().from in request.query.origins &&
            legs.last().to in request.query.destinations &&
            legs.first().departDate >= request.query.outboundFrom &&
            legs.first().departDate <= request.query.outboundTo &&
            draft.observedAt == request.fetchedAt
        ) { "Draft is outside the requested route, dates or capture" }
        val returning = draft.returning
        if (expectedTrip == "round_trip") {
            require((legs.first().departDate to draft.returnDateHint) in pairs) {
                "Draft return date was not in the planned date combinations"
            }
            if (draft.returnMissing || returning == null) {
                require(draft.returnMissing && returning == null) {
                    "Missing return must not have verified inbound legs"
                }
                unresolvedReturns++
                // An outbound quote is not a total return fare and must not
                // appear among priced round-trip itineraries.
                continue
            }
            require(returning.legs.isNotEmpty() &&
                returning.legs.first().from == legs.last().to &&
                returning.legs.last().to == legs.first().from &&
                returning.legs.first().departDate == draft.returnDateHint &&
                legs.last().arriveDate <= returning.legs.first().departDate
            ) { "Verified return does not match the selected outbound itinerary" }
        }
        val decision = evaluatePortableEligibility(
            EligibilityEvaluationRequest(
                query = request.query,
                evidence = EligibilityEvidence(
                    outbound = draft.outbound,
                    returning = returning,
                    returnMissing = draft.returnMissing,
                ),
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
        .sortedWith(compareBy<GoogleFareDraft> { draft ->
            draft.originalPerPersonPrice?.let { FareMath.toGbpOrNull(it, request.fx.perGbp[draft.priceCurrency]) }
                ?: Double.POSITIVE_INFINITY
        }.thenBy { it.priceCurrency }
            .thenBy { it.originalPerPersonPrice ?: Double.POSITIVE_INFINITY }
            .thenBy { it.id })
        .distinctBy { it.id }
    val missingFx = ranked
        .filter { request.fx.perGbp[it.priceCurrency] == null && it.originalPerPersonPrice != null }
        .map { it.priceCurrency }.distinct().sorted()
    val fares = ranked.map { draft ->
        val currency = draft.priceCurrency
        val rate = request.fx.perGbp[currency]
        val outbound = draft.outbound
        val returning = draft.returning
        val legs = outbound.legs + returning?.legs.orEmpty()
        val layovers = outbound.layovers.map { it.minutes } +
            returning?.layovers.orEmpty().map { it.minutes }
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
            destination = outbound.legs.last().to,
            outboundDate = outbound.legs.first().departDate,
            returnDate = returning?.legs?.firstOrNull()?.departDate,
            returnFrom = returning?.legs?.firstOrNull()?.from,
            nights = nightsBetween(outbound.legs.first().departDate,
                returning?.legs?.firstOrNull()?.departDate),
            airlineSummary = names.joinToString(" / "),
            outbound = outbound,
            returning = returning,
            stopsOut = outbound.stops,
            stopsRet = returning?.stops,
            layoverMinutes = layovers,
            maxLayoverMinutes = layovers.maxOrNull(),
            travelMinutes =
                if (returning == null) outbound.durationMinutes
                else if (outbound.durationMinutes != null && returning.durationMinutes != null) {
                    outbound.durationMinutes + returning.durationMinutes
                } else null,
            outboundDepart = outbound.legs.first().departTime,
            returnDepart = returning?.legs?.firstOrNull()?.departTime,
            airlineCodes = codes,
            source = draft.source,
            fareBrand = draft.fareBrand,
            sourceLabel = draft.sourceLabel,
            livePrice = draft.livePrice,
            fetchedAt = draft.observedAt,
            validUntil = draft.validUntil,
        )
    }
    val route = request.query.origins.joinToString("/") + " → " +
        request.query.destinations.joinToString("/")
    val warnings = listOfNotNull(
        if (provider == "duffel") {
            if (ranked.any { it.sourceLabel == "mock" }) "Duffel test offers are simulated." else
                "Duffel offer availability and fare terms can change before booking."
        } else if (expectedTrip == "one_way") {
            "Only observed Google Flights one-way base fares are shown; this is not exhaustive availability."
        } else {
            "Only Google Flights fares with verified outbound and return selections are priced; no booking is guaranteed."
        },
        if (unresolvedReturns > 0) {
            "$unresolvedReturns outbound selections lack a verified return; they were not priced as round trips."
        } else null,
        if (missingFx.isNotEmpty()) {
            "No dated GBP conversion for ${missingFx.joinToString(", ")}; original prices are retained."
        } else null,
        if (fares.isEmpty()) "No eligible complete fares in the received observations; this is not proof no flights exist." else null,
    )
    return HostedFareAssembly(
        result = HostedFareResultSet(
            id = request.investigationId,
            title = route + " · " + request.query.outboundFrom,
            subtitle = if (expectedTrip == "one_way") {
                "One-way fare observations, not verified booking offers"
            } else {
                "Verified return selections, not confirmed bookings"
            },
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
        unresolvedReturns = unresolvedReturns,
    )
}
