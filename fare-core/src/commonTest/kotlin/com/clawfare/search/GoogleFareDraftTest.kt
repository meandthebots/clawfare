package com.clawfare.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleFareDraftTest {
    private fun leg(number: String, from: String, to: String, date: String) =
        Leg(
            flightNumber = number, airline = "British Airways", airlineCode = "BA",
            operatingCarrier = "British Airways", from = from, to = to,
            departTime = "13:30", arriveTime = "10:15", departDate = date,
            arriveDate = date, durationMinutes = 780, cabin = "Business",
            operatingCarrierVerified = true,
        )

    private val outbound = leg("BA 7", "LHR", "NRT", "2027-04-12")
    private val returning = leg("BA 8", "NRT", "LHR", "2027-04-20")
    private fun offer(price: Double?, leg: Leg) = ParsedOffer(
        price = price, legs = listOf(leg), layovers = emptyList(),
        durationMinutes = 850, departureToken = "private-departure",
        bookingToken = "private-booking", fareBrand = "Business Flex",
    )
    private fun query(adults: Int, tripType: String = "one_way") = SearchQuery(
        origins = listOf("LHR"), destinations = listOf("NRT"),
        outboundFrom = "2027-04-12", tripType = tripType,
        nights = if (tripType == "one_way") null else 8,
        adults = adults, currency = "GBP", providers = listOf("serpapi"),
    )
    private fun build(
        query: SearchQuery,
        returningOffer: ParsedOffer? = null,
        fromCache: Boolean = false,
        price: Double? = 2350.0,
    ) = buildGoogleFareDraft(
        id = "serpapi-test", provider = "serpapi", query = query,
        outbound = offer(price, outbound), inbound = returningOffer,
        fetchedAt = "2026-11-18T19:00:00Z", servedFromCache = fromCache,
        returnDateHint = if (query.tripType == "one_way") null else "2027-04-20",
    )

    @Test
    fun quotedPartyPriceDividesOnceAndNeverLeaksOpaqueTokens() {
        val draft = build(query(2))
        assertEquals(2350.0, draft.originalPartyPrice)
        assertEquals(1175.0, draft.originalPerPersonPrice)
        assertEquals(2, draft.passengers)
        assertEquals("GBP", draft.priceCurrency)
        assertTrue(draft.livePrice)
        assertFalse(draft.returnMissing)
        assertNull(draft.returning)
        val serialized = AppJson.encodeToString(GoogleFareDraft.serializer(), draft)
        assertFalse(serialized.contains("private-departure"))
        assertFalse(serialized.contains("private-booking"))
        assertFalse(serialized.contains("departureToken"))
        assertFalse(serialized.contains("bookingToken"))
        assertEquals(draft, AppJson.decodeFromString(GoogleFareDraft.serializer(), serialized))
    }

    @Test
    fun missingReturnAndCachedPricesAreNotFullOrLiveFares() {
        val missing = build(query(1, "round_trip"), fromCache = true, price = null)
        assertTrue(missing.returnMissing)
        assertNull(missing.returning)
        assertNull(missing.originalPerPersonPrice)
        assertEquals("cached", missing.sourceLabel)
        assertFalse(missing.livePrice)
        assertEquals("2027-04-20", missing.returnDateHint)
        val full = build(query(1, "round_trip"), offer(1750.0, returning))
        assertFalse(full.returnMissing)
        assertEquals(1750.0, full.originalPartyPrice)
        assertEquals("NRT", full.returning?.legs?.single()?.from)
    }

    @Test
    fun flightFamilyDoesNotDependOnPriceOrFareBrand() {
        val family = googleFlightFamilyKey(listOf(outbound), listOf(returning))
        assertEquals("BA 7@2027-04-12|BA 8@2027-04-20", family)
        assertTrue(family != googleFlightFamilyKey(listOf(outbound), null))
        assertTrue(family != googleFlightFamilyKey(listOf(outbound.copy(flightNumber = "BA 9")), listOf(returning)))
    }
}
