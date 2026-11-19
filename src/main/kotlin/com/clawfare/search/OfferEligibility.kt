package com.clawfare.search

/**
 * JVM adapter retains its versioned, optionally user-supplied OurAirports
 * country reference, while the carrier/transit/strict-leg rules themselves
 * run through the same pure fare-core path as Kotlin/JS and Kotlin/Wasm.
 */
object OfferEligibility {
    fun rejectionReason(
        itinerary: Itinerary,
        query: SearchQuery,
    ): String? {
        if (query.excludedAirlines.isNotEmpty() &&
            !Filters.matches(itinerary, FilterSpec(airlineBlock = query.excludedAirlines, hideMock = false))
        ) {
            return "excluded_carrier"
        }
        val transitRejection =
            TransitRules.rejectionReason(
                itinerary,
                query.excludedTransitAirports,
                query.excludedTransitCountries,
            )
        if (transitRejection != null) return transitRejection
        return sharedStrictLegRejectionReason(
            itinerary.outbound,
            itinerary.returning,
            itinerary.returnMissing,
            query,
        )
    }

    fun isShortHaul(leg: Leg): Boolean = sharedIsShortHaul(leg)
}
