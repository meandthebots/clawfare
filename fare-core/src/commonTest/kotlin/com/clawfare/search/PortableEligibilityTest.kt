package com.clawfare.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortableEligibilityTest {
    private val londonParis =
        Leg(
            flightNumber = "BA 302",
            airline = "British Airways",
            airlineCode = "BA",
            operatingCarrier = "British Airways",
            from = "LHR",
            to = "CDG",
            departTime = "09:00",
            arriveTime = "11:10",
            departDate = "2027-04-12",
            arriveDate = "2027-04-12",
            durationMinutes = 70,
            cabin = "Economy",
            operatingCarrierVerified = true,
        )
    private val parisTokyo =
        Leg(
            flightNumber = "AF 274",
            airline = "Air France",
            airlineCode = "AF",
            operatingCarrier = "Air France",
            from = "CDG",
            to = "HND",
            departTime = "14:00",
            arriveTime = "10:00",
            departDate = "2027-04-12",
            arriveDate = "2027-04-13",
            durationMinutes = 720,
            cabin = "Business",
            operatingCarrierVerified = true,
        )
    private val direction =
        Direction(
            legs = listOf(londonParis, parisTokyo),
            layovers = listOf(Layover("CDG", 170)),
            durationMinutes = 960,
            stops = 1,
        )
    private val business =
        SearchQuery(
            origins = listOf("LHR"),
            destinations = listOf("HND"),
            outboundFrom = "2027-04-12",
            tripType = "one_way",
            cabin = "business",
            cabinShortHaul = "economy",
            strictPerLegCabin = true,
            strictOperatingCarrier = true,
            excludedAirlines = emptyList(),
            excludedTransitCountries = emptyList(),
        )

    private fun evaluate(
        search: SearchQuery = business,
        outbound: Direction = direction,
        returning: Direction? = null,
        returnMissing: Boolean = false,
        countries: Map<String, String> = mapOf("CDG" to "FR"),
    ) = evaluatePortableEligibility(
        EligibilityEvaluationRequest(
            query = search,
            evidence = EligibilityEvidence(outbound, returning, returnMissing),
            airportCountries = countries,
        ),
    )

    @Test
    fun strictCabinAllowsActualShortHaulEconomyButRequiresLongHaulBusiness() {
        val correct = evaluate()
        assertTrue(correct.eligible)
        assertNull(correct.rejectionReason)
        assertTrue(sharedIsShortHaul(londonParis))
        assertFalse(sharedIsShortHaul(parisTokyo))
        val downgraded = direction.copy(
            legs = listOf(londonParis, parisTokyo.copy(cabin = "Economy")),
        )
        assertEquals("insufficient_cabin", evaluate(outbound = downgraded).rejectionReason)
        assertEquals(
            "unknown_cabin",
            evaluate(outbound = direction.copy(
                legs = listOf(londonParis, parisTokyo.copy(cabin = null)),
            )).rejectionReason,
        )
    }

    @Test
    fun unknownOperatorAndIncompleteReturnAreNeverVerifiedByMarketingData() {
        val unknownOperator = direction.copy(
            legs = listOf(londonParis, parisTokyo.copy(
                operatingCarrier = "Air France", operatingCarrierVerified = false,
            )),
        )
        assertEquals("unknown_operator", evaluate(outbound = unknownOperator).rejectionReason)
        assertEquals(
            "unverified_return_legs",
            evaluate(search = business.copy(tripType = "round_trip"), returnMissing = true).rejectionReason,
        )
        assertEquals(
            "missing_legs",
            evaluate(outbound = Direction()).rejectionReason,
        )
    }

    @Test
    fun airlineTransitAndDisconnectedLegsUseStableDenialPrecedence() {
        val banned = business.copy(excludedAirlines = listOf("BA"))
        assertEquals("excluded_carrier", evaluate(search = banned).rejectionReason)
        assertEquals(
            "excluded_transit_airport",
            evaluate(search = business.copy(excludedTransitAirports = listOf("CDG"))).rejectionReason,
        )
        assertEquals(
            "excluded_transit_country",
            evaluate(search = business.copy(excludedTransitCountries = listOf("FR"))).rejectionReason,
        )
        assertEquals(
            "unknown_transit_country",
            evaluate(
                search = business.copy(excludedTransitCountries = listOf("CN")),
                countries = emptyMap(),
            ).rejectionReason,
        )
        assertEquals(
            "disconnected_legs",
            evaluate(outbound = direction.copy(
                legs = listOf(londonParis, parisTokyo.copy(from = "FRA")),
            )).rejectionReason,
        )
    }

    @Test
    fun nonstopOnlyIsVerifiedAgainstBothDirectionsNotJustProviderSearchParameters() {
        val direct =
            directionOf(listOf(londonParis.copy(
                to = "HND", cabin = "Business", durationMinutes = 720,
            )), apiDuration = 720)
        val search = business.copy(nonstopOnly = true)
        assertEquals("nonstop_required", evaluate(search = search).rejectionReason)
        assertTrue(evaluate(search = search, outbound = direct).eligible)
        assertEquals(
            "nonstop_required",
            evaluate(search = search, outbound = direct, returning = direction).rejectionReason,
        )
        assertNull(sharedNonstopRejectionReason(direct, null, nonstopOnly = true))
        assertNull(sharedNonstopRejectionReason(direction, direction, nonstopOnly = false))
    }

    @Test
    fun carrierMatchingIsExactAndRecognizesConfiguredAliases() {
        assertTrue(isKnownCarrierMatch(londonParis, "BA"))
        assertTrue(isKnownCarrierMatch(londonParis, "british airways"))
        assertFalse(isKnownCarrierMatch(londonParis, "Air"))
        assertFalse(isKnownCarrierMatch(londonParis, "B"))
        val lot = londonParis.copy(
            airlineCode = "LO", airline = "LOT Polish Airlines",
        )
        assertTrue(isKnownCarrierMatch(lot, "LOT"))
        assertTrue(isKnownCarrierMatch(lot, "LO"))
        assertFalse(isKnownCarrierMatch(lot, "AI"))
    }

    @Test
    fun eligibilityReportSerializationPreservesReasonAndNeverReturnsTokens() {
        val input = EligibilityEvaluationRequest(
            query = business.copy(excludedTransitCountries = listOf("FR")),
            evidence = EligibilityEvidence(direction),
            airportCountries = mapOf("CDG" to "FR"),
        )
        val json = AppJson.encodeToString(EligibilityEvaluationRequest.serializer(), input)
        val roundTrip = AppJson.decodeFromString(EligibilityEvaluationRequest.serializer(), json)
        assertEquals(input, roundTrip)
        val result = evaluatePortableEligibility(roundTrip)
        val serialized = AppJson.encodeToString(EligibilityEvaluation.serializer(), result)
        assertEquals("excluded_transit_country", result.rejectionReason)
        assertFalse(result.eligible)
        assertFalse(serialized.contains("private"))
    }
}
