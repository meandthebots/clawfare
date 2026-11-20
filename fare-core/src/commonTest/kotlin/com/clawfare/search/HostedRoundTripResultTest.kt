package com.clawfare.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostedRoundTripResultTest {
    private val stamp = "2026-11-20T12:00:00Z"
    private val query =
        SearchQuery(
            origins = listOf("LHR"),
            destinations = listOf("MCO"),
            outboundFrom = "2027-04-12",
            outboundTo = "2027-04-12",
            nights = 8,
            tripType = "round_trip",
            adults = 2,
            cabin = "premium_economy",
            providers = listOf("serpapi"),
            currency = "GBP",
            fetchReturns = true,
            excludedAirlines = emptyList(),
        )

    private fun flight(
        number: String,
        from: String,
        to: String,
        departDate: String,
        arriveDate: String,
        airlineCode: String = "BA",
    ): Leg =
        Leg(
            flightNumber = number,
            airline = if (airlineCode == "BA") "British Airways" else "LOT",
            airlineCode = airlineCode,
            operatingCarrier = if (airlineCode == "BA") "British Airways" else "LOT",
            from = from,
            to = to,
            departDate = departDate,
            arriveDate = arriveDate,
            departTime = "13:30",
            arriveTime = "16:30",
            durationMinutes = 500,
            cabin = "Premium economy",
            operatingCarrierVerified = true,
        )

    private fun offer(
        leg: Leg,
        partyPrice: Double,
    ): ParsedOffer =
        ParsedOffer(
            price = partyPrice,
            legs = listOf(leg),
            layovers = emptyList(),
            durationMinutes = 500,
            departureToken = "private-departure-token",
            bookingToken = "private-booking-token",
        )

    private val outbound = offer(flight("BA 203", "LHR", "MCO", "2027-04-12", "2027-04-12"), 1800.0)
    private val returning = offer(flight("BA 204", "MCO", "LHR", "2027-04-20", "2027-04-21"), 2600.0)

    private fun draft(
        inbound: ParsedOffer? = returning,
        id: String = "serpapi-return-family",
        hint: String = "2027-04-20",
    ): GoogleFareDraft =
        buildGoogleFareDraft(
            id = id,
            provider = "serpapi",
            query = query,
            outbound = outbound,
            inbound = inbound,
            fetchedAt = stamp,
            servedFromCache = false,
            returnDateHint = hint,
        )

    private fun request(
        drafts: List<GoogleFareDraft> = listOf(draft()),
        q: SearchQuery = query,
    ): HostedFareResultRequest =
        HostedFareResultRequest(
            investigationId = "lhr-orlando-spring",
            query = q,
            fetchedAt = stamp,
            drafts = drafts,
            fx = HostedFareFx("2026-11-20", mapOf("GBP" to 1.0)),
        )

    @Test
    fun verifiedReturnUsesSelectedWholeTripPriceNotOutboundPriceOrDoubleCountedPassengers() {
        val assembly = assembleHostedRoundTripResult(request())
        assertEquals(1, assembly.returned)
        assertEquals(1, assembly.eligible)
        assertEquals(0, assembly.unresolvedReturns)
        val result = assembly.result
        assertEquals(1, result.itineraries.size)
        val itinerary = result.itineraries.single()
        assertEquals(1300.0, itinerary.pricePerPerson)
        assertEquals(2, itinerary.passengers)
        assertEquals("LHR", itinerary.origin)
        assertEquals("MCO", itinerary.destination)
        assertEquals("MCO", itinerary.returnFrom)
        assertEquals("2027-04-12", itinerary.outboundDate)
        assertEquals("2027-04-20", itinerary.returnDate)
        assertEquals(8, itinerary.nights)
        assertEquals(0, itinerary.stopsOut)
        assertEquals(0, itinerary.stopsRet)
        assertEquals(1000, itinerary.travelMinutes)
        assertEquals(2, itinerary.outbound.legs.size + itinerary.returning!!.legs.size)
        assertFalse(itinerary.returnMissing)
        assertFalse(itinerary.cashRefundProofVerified)
        assertTrue(itinerary.links.isEmpty())
        val serialized = AppJson.encodeToString(HostedFareAssembly.serializer(), assembly)
        for (secret in listOf("private-departure-token", "private-booking-token",
                "departureToken", "bookingToken", "api_key")) {
            assertFalse(serialized.contains(secret))
        }
        assertTrue(serialized.contains("\"returnDate\":\"2027-04-20\""))
    }

    @Test
    fun absentReturnCannotBeSoldAsAnOutboundPricedRoundTrip() {
        val incomplete = draft(inbound = null)
        assertTrue(incomplete.returnMissing)
        assertEquals(900.0, incomplete.originalPerPersonPrice)
        val assembly = assembleHostedRoundTripResult(request(listOf(incomplete)))
        assertEquals(1, assembly.returned)
        assertEquals(0, assembly.eligible)
        assertEquals(1, assembly.unresolvedReturns)
        assertTrue(assembly.result.itineraries.isEmpty())
        assertTrue(assembly.result.warnings.any { it.contains("lack a verified return") })
        assertFalse(assembly.result.warnings.any { it.contains("verified cheapest") })
    }

    @Test
    fun duplicateReturnFamilyUsesCheapestVerifiedWholeTripVariant() {
        val cheaperReturn = returning.copy(price = 2400.0)
        val assembly = assembleHostedRoundTripResult(
            request(listOf(draft(), draft(inbound = cheaperReturn))),
        )
        assertEquals(2, assembly.returned)
        assertEquals(1, assembly.eligible)
        assertEquals(1200.0, assembly.result.itineraries.single().pricePerPerson)
        assertNull(assembly.result.itineraries.single().priceOriginal)
    }

    @Test
    fun applyingCarrierExclusionsRequiresLookingAtBothDirections() {
        val lotReturn = offer(
            flight("LO 204", "MCO", "LHR", "2027-04-20", "2027-04-21", "LO"),
            2300.0,
        )
        val rejected = draft(inbound = lotReturn)
        val assembly = assembleHostedRoundTripResult(
            request(listOf(rejected), query.copy(excludedAirlines = listOf("LO"))),
        )
        assertEquals(0, assembly.eligible)
        assertEquals(1, assembly.excludedByReason["excluded_carrier"])
    }

    @Test
    fun rejectsReturnDateOutsidePlanAndUnrelatedInboundAirports() {
        assertFailsWith<IllegalArgumentException> {
            assembleHostedRoundTripResult(request(listOf(draft(hint = "2027-04-21"))))
        }
        val wrongReturn = offer(
            flight("BA 204", "MIA", "LHR", "2027-04-20", "2027-04-21"),
            2600.0,
        )
        assertFailsWith<IllegalArgumentException> {
            assembleHostedRoundTripResult(request(listOf(draft(inbound = wrongReturn))))
        }
    }

    @Test
    fun rejectsOneWayInRoundTripAssemblerAndReturnlessFullQuote() {
        assertFailsWith<IllegalArgumentException> {
            assembleHostedRoundTripResult(request(q = query.copy(tripType = "one_way")))
        }
        assertFailsWith<IllegalArgumentException> {
            assembleHostedRoundTripResult(request(q = query.copy(fetchReturns = false)))
        }
        assertFailsWith<IllegalArgumentException> {
            assembleHostedOneWayResult(request())
        }
    }
}
