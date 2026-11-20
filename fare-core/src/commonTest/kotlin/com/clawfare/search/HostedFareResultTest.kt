package com.clawfare.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostedFareResultTest {
    private val stamp = "2026-11-19T12:00:00Z"
    private fun query(
        currency: String = "GBP",
        excluded: List<String> = emptyList(),
        trip: String = "one_way",
    ) = SearchQuery(
        origins = listOf("LHR"), destinations = listOf("NRT"),
        outboundFrom = "2027-04-12", outboundTo = "2027-04-12",
        adults = 2, tripType = trip, currency = currency,
        providers = listOf("serpapi"), excludedAirlines = excluded,
        fetchReturns = false,
    )
    private fun leg(
        code: String = "BA",
        via: String = "NRT",
    ) = Leg(
        flightNumber = code + " 7", airline = if (code == "BA") "British Airways" else "LOT",
        airlineCode = code, operatingCarrier = if (code == "BA") "British Airways" else "LOT",
        from = "LHR", to = via, departTime = "13:30", arriveTime = "10:15",
        departDate = "2027-04-12", arriveDate = "2027-04-13",
        durationMinutes = 780, cabin = "Business", operatingCarrierVerified = true,
    )
    private fun draft(
        id: String = "serpapi-ba7",
        price: Double? = 2400.0,
        code: String = "BA",
        currency: String = "GBP",
    ) = GoogleFareDraft(
        id = id, source = "serpapi", sourceLabel = "live",
        livePrice = true, observedAt = stamp,
        originalPartyPrice = price, originalPerPersonPrice = price?.div(2),
        priceCurrency = currency, passengers = 2, cabinRaw = "business",
        outbound = directionOf(listOf(leg(code)), apiDuration = 780),
    )
    private fun request(
        query: SearchQuery = query(),
        drafts: List<GoogleFareDraft> = listOf(draft()),
        fx: HostedFareFx = HostedFareFx("2026-11-19", mapOf("GBP" to 1.0)),
    ) = HostedFareResultRequest(
        investigationId = "lhr-nrt-oneway", query = query,
        fetchedAt = stamp, drafts = drafts, fx = fx,
    )

    @Test
    fun oneWayResultIsRealBrowserShapeAndNeverContainsTokens() {
        val built = assembleHostedOneWayResult(request())
        assertEquals(1, built.returned)
        assertEquals(1, built.eligible)
        val result = built.result
        assertEquals(1, result.itineraries.size)
        val fare = result.itineraries.single()
        assertEquals(1200.0, fare.pricePerPerson)
        assertEquals(2, fare.passengers)
        assertEquals("LHR", fare.origin)
        assertEquals("NRT", fare.destination)
        assertNull(fare.returnDate)
        assertFalse(fare.returnMissing)
        assertTrue(fare.links.isEmpty())
        assertFalse(fare.cashRefundProofVerified)
        assertTrue(result.addons.isEmpty() && result.cells.isEmpty() && result.vendors.isEmpty())
        val raw = AppJson.encodeToString(HostedFareAssembly.serializer(), built)
        assertTrue(raw.contains("\"itineraries\""))
        assertTrue(raw.contains("\"sourceLabel\":\"live\""))
        assertFalse(raw.contains("bookingToken"))
        assertFalse(raw.contains("departureToken"))
        assertFalse(raw.contains("api_key"))
    }

    @Test
    fun eligibilityAndDedupeApplyBeforePublicSerialization() {
        val built = assembleHostedOneWayResult(
            request(
                query = query(excluded = listOf("LO")),
                drafts = listOf(
                    draft("ba7", 2500.0), draft("ba7", 2000.0),
                    draft("lot", 1000.0, code = "LO"),
                ),
            ),
        )
        assertEquals(3, built.returned)
        assertEquals(1, built.eligible)
        assertEquals(1, built.excludedByReason["excluded_carrier"])
        assertEquals(1000.0, built.result.itineraries.single().pricePerPerson)
    }

    @Test
    fun datedFxPreservesOriginalCurrencyAndDoesNotInventUnknownGbpRate() {
        val usd = query("USD")
        val converted = assembleHostedOneWayResult(
            request(usd, listOf(draft(currency = "USD")),
                HostedFareFx("2026-11-18", mapOf("GBP" to 1.0, "USD" to 1.25))),
        ).result.itineraries.single()
        assertEquals(960.0, converted.pricePerPerson)
        assertEquals(1200.0, converted.priceOriginal)
        assertEquals("USD", converted.priceCurrency)
        val unknown = assembleHostedOneWayResult(
            request(usd, listOf(draft(currency = "USD"))),
        )
        assertNull(unknown.result.itineraries.single().pricePerPerson)
        assertTrue(unknown.result.warnings.any { it.contains("No dated GBP conversion") })
    }

    @Test
    fun rejectsRoundTripMisrepresentationAndContradictoryObservations() {
        assertFailsWith<IllegalArgumentException> {
            assembleHostedOneWayResult(request(query = query(trip = "round_trip")))
        }
        assertFailsWith<IllegalArgumentException> {
            assembleHostedOneWayResult(request(drafts = listOf(draft(currency = "USD"))))
        }
        assertFailsWith<IllegalArgumentException> {
            assembleHostedOneWayResult(request(fx = HostedFareFx("2026-11-20", mapOf("GBP" to 1.0))))
        }
    }
}
