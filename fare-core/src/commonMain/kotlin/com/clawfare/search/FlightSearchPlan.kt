package com.clawfare.search

import kotlinx.serialization.Serializable

@Serializable
data class FlightDatePair(
    val outbound: String,
    val returning: String? = null,
)

/**
 * Pure, provider-independent search plan. A successful plan is not consent to
 * spend provider quota: the hosted adapter must estimate, reserve and require
 * explicit user confirmation before any network call.
 */
@Serializable
data class FlightSearchPlan(
    val query: SearchQuery,
    val pairs: List<FlightDatePair> = emptyList(),
    val error: String? = null,
)

fun planFlightSearch(input: SearchQuery): FlightSearchPlan {
    val query = normalizeQuery(input)
    val error = validateQuery(query)
    if (error != null) return FlightSearchPlan(query = query, error = error)
    return FlightSearchPlan(
        query = query,
        pairs = datePairs(query).map { FlightDatePair(it.first, it.second) },
    )
}
