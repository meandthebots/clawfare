package com.clawfare.search

import kotlinx.serialization.Serializable

/**
 * Token-free intermediate fare representation shared by JVM and hosted JS.
 * Does not assert GBP conversion, refundability, booking availability or
 * eligibility that the provider has not proven. UI ResultSet assembly is a
 * separate step; never expose raw ParsedOffer with opaque tokens.
 */
@Serializable
data class GoogleFareDraft(
    val id: String,
    val source: String,
    val sourceLabel: String,
    val livePrice: Boolean,
    val observedAt: String,
    /** Google Flights reports a requested-party total, not a per-seat quote. */
    val originalPartyPrice: Double?,
    val originalPerPersonPrice: Double?,
    val priceCurrency: String,
    val passengers: Int,
    val cabinRaw: String,
    val fareBrand: String? = null,
    val outbound: Direction,
    val returning: Direction? = null,
    val returnMissing: Boolean = false,
    val returnDateHint: String? = null,
) {
    init {
        require(id.isNotBlank() && id.length <= 128) { "Invalid itinerary family key" }
        require(source == "serpapi" || source == "searchapi") { "Unsupported provider" }
        require(sourceLabel in setOf("live", "cached")) { "Invalid provenance" }
        require(livePrice == (sourceLabel == "live")) { "Cached prices cannot be live" }
        require(passengers in 1..9) { "Invalid party size" }
        require(priceCurrency.matches(Regex("[A-Z]{3}"))) { "Invalid original currency" }
        require(outbound.legs.isNotEmpty()) { "At least one outbound leg is required" }
        require(originalPartyPrice == null && originalPerPersonPrice == null ||
            originalPartyPrice != null && originalPerPersonPrice != null &&
            originalPartyPrice.isFinite() && originalPartyPrice > 0.0 &&
            originalPerPersonPrice.isFinite() && originalPerPersonPrice > 0.0
        ) { "A priced fare must have positive total and per-person amounts" }
        require(!returnMissing || returning == null) { "Missing return cannot have verified return legs" }
    }
}

/** Exact flight-family identity input already used by the JVM SHA-256 ID. */
fun googleFlightFamilyKey(
    outbound: List<Leg>,
    returning: List<Leg>?,
): String = (outbound + (returning ?: emptyList())).joinToString("|") {
    "${it.flightNumber}@${it.departDate}"
}

/**
 * Build a canonical, token-free fare draft after parsing provider payloads.
 * The caller supplies its stable hashed ID and audited observation timestamp.
 * A fare product's brand affects identity, but its changing price never does.
 */
fun buildGoogleFareDraft(
    id: String,
    provider: String,
    query: SearchQuery,
    outbound: ParsedOffer,
    inbound: ParsedOffer?,
    fetchedAt: String,
    servedFromCache: Boolean,
    returnDateHint: String? = null,
): GoogleFareDraft {
    require(query.adults in 1..9) { "Invalid passenger count" }
    val price = inbound?.price ?: outbound.price
    val valid = price?.takeIf { it.isFinite() && it > 0.0 }
    val returnLegs = inbound?.legs?.takeIf { it.isNotEmpty() }
    val missing = query.tripType != "one_way" && returnLegs.isNullOrEmpty()
    return GoogleFareDraft(
        id = id,
        source = provider,
        sourceLabel = if (servedFromCache) "cached" else "live",
        livePrice = !servedFromCache,
        observedAt = fetchedAt,
        originalPartyPrice = valid,
        originalPerPersonPrice = valid?.div(query.adults),
        priceCurrency = query.currency.uppercase(),
        passengers = query.adults,
        cabinRaw = query.cabin,
        fareBrand = inbound?.fareBrand ?: outbound.fareBrand,
        outbound = directionOf(outbound.legs, outbound.layovers, outbound.durationMinutes),
        returning = if (inbound != null && returnLegs != null) {
            directionOf(returnLegs, inbound.layovers, inbound.durationMinutes)
        } else {
            null
        },
        returnMissing = missing,
        returnDateHint = returnDateHint,
    )
}

/** Internal Kotlin/JS bridge input, never a public or persisted browser DTO. */
@Serializable
data class GoogleFareDraftRequest(
    val id: String,
    val provider: String,
    val query: SearchQuery,
    val outbound: ParsedOffer,
    val inbound: ParsedOffer? = null,
    val fetchedAt: String,
    val servedFromCache: Boolean,
    val returnDateHint: String? = null,
)
