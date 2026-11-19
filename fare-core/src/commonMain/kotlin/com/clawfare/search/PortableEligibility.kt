package com.clawfare.search

import kotlinx.serialization.Serializable

/**
 * Exact, portable eligibility checks shared by JVM, Kotlin/JS and Wasm.
 * Pure functions operate only on observed segments and a caller-supplied,
 * trusted airport-country reference. Never infer an unknown operator/cabin.
 */
@Serializable
data class EligibilityEvidence(
    val outbound: Direction,
    val returning: Direction? = null,
    val returnMissing: Boolean = false,
)

@Serializable
data class EligibilityEvaluationRequest(
    val query: SearchQuery,
    val evidence: EligibilityEvidence,
    /**
     * The hosting adapter supplies a verified versioned reference. Empty
     * country data fails closed for an excluded-country transit search.
     */
    val airportCountries: Map<String, String> = emptyMap(),
)

@Serializable
data class EligibilityEvaluation(
    val eligible: Boolean,
    val rejectionReason: String? = null,
)

fun isKnownCarrierMatch(
    leg: Leg,
    requested: String,
): Boolean {
    val requestedCode = requested.trim()
    if (requestedCode.isEmpty()) return false
    val aliases =
        when (requestedCode.uppercase()) {
            "AI", "AIR INDIA" -> setOf("AI", "Air India")
            "VN", "VIETNAM AIRLINES" -> setOf("VN", "Vietnam Airlines")
            "LO", "LOT", "LOT POLISH AIRLINES" -> setOf("LO", "LOT", "LOT Polish Airlines")
            else ->
                AIRLINE_NAMES[requestedCode.uppercase()]?.let { setOf(requestedCode, it) }
                    ?: setOf(requestedCode)
        }
    val observed = listOf(leg.airlineCode, leg.airline, leg.operatingCarrier)
    return observed.any { actual ->
        aliases.any { alias -> actual.trim().equals(alias, ignoreCase = true) }
    }
}

fun sharedCarrierExclusionReason(
    outbound: Direction,
    returning: Direction?,
    excludedAirlines: List<String>,
): String? {
    if (excludedAirlines.isEmpty()) return null
    val legs = outbound.legs + returning?.legs.orEmpty()
    if (legs.any { leg -> excludedAirlines.any { isKnownCarrierMatch(leg, it) } }) {
        return "excluded_carrier"
    }
    return null
}

/**
 * Refuses disconnected through-tickets, forbidden connections and unknown
 * transit countries when country exclusions are requested. Endpoints are not
 * connections; every reported layover and intermediate segment endpoint is.
 */
fun sharedTransitRejectionReason(
    outbound: Direction,
    returning: Direction?,
    excludedAirports: List<String>,
    excludedCountries: List<String>,
    countries: Map<String, String>,
): String? {
    val explicit = excludedAirports.map { it.uppercase() }.toSet()
    val forbidden = excludedCountries.map { it.uppercase() }.toSet()
    val directions = listOf(outbound) + listOfNotNull(returning)
    if (directions.any { direction ->
            direction.legs.zipWithNext().any { (before, after) ->
                before.to.isBlank() || after.from.isBlank() ||
                    !before.to.equals(after.from, ignoreCase = true)
            }
        }
    ) {
        return "disconnected_legs"
    }
    val connections =
        directions.flatMap { direction ->
            direction.legs.dropLast(1).map { it.to.uppercase() } +
                direction.layovers.map { it.airport.uppercase() }
        }
    for (code in connections) {
        if (code in explicit) return "excluded_transit_airport"
        if (forbidden.isNotEmpty()) {
            val country = countries[code] ?: return "unknown_transit_country"
            if (country.uppercase() in forbidden) return "excluded_transit_country"
        }
    }
    return null
}

/** Pure strict-leg checks, used by both the real JVM provider and hosted JS. */
fun sharedStrictLegRejectionReason(
    outbound: Direction,
    returning: Direction?,
    returnMissing: Boolean,
    query: SearchQuery,
): String? {
    val directions = listOf(outbound) + listOfNotNull(returning)
    val legs = directions.flatMap { it.legs }
    if (legs.isEmpty() || directions.any { it.legs.isEmpty() }) return "missing_legs"
    if (query.tripType != "one_way" && returnMissing &&
        (query.strictPerLegCabin || query.strictOperatingCarrier)
    ) {
        return "unverified_return_legs"
    }
    if (query.strictOperatingCarrier && legs.any {
            it.operatingCarrier.isBlank() || !it.operatingCarrierVerified
        }
    ) {
        return "unknown_operator"
    }
    if (query.strictPerLegCabin) {
        for (leg in legs) {
            val minimum = if (sharedIsShortHaul(leg)) query.cabinShortHaul else query.cabin
            if (recognizedCabinKeyOf(leg.cabin) == null) return "unknown_cabin"
            if (!cabinAtLeast(leg.cabin, minimum)) return "insufficient_cabin"
        }
    }
    return null
}

fun sharedIsShortHaul(leg: Leg): Boolean {
    val fromEurope = isEuropeanAirport(leg.from)
    val toEurope = isEuropeanAirport(leg.to)
    if (fromEurope != toEurope) return false
    if (fromEurope && toEurope) return true
    return leg.durationMinutes != null && leg.durationMinutes in 1..240
}

/**
 * Stable priority of exclusion reasons matches JVM OfferEligibility:
 * carrier → transit/disconnected → incomplete return → operator → cabin.
 */
fun evaluatePortableEligibility(request: EligibilityEvaluationRequest): EligibilityEvaluation {
    val evidence = request.evidence
    val reason =
        sharedCarrierExclusionReason(
            evidence.outbound, evidence.returning, request.query.excludedAirlines,
        ) ?: sharedTransitRejectionReason(
            evidence.outbound,
            evidence.returning,
            request.query.excludedTransitAirports,
            request.query.excludedTransitCountries,
            request.airportCountries,
        ) ?: sharedStrictLegRejectionReason(
            evidence.outbound, evidence.returning, evidence.returnMissing, request.query,
        )
    return EligibilityEvaluation(eligible = reason == null, rejectionReason = reason)
}
