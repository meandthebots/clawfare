package com.clawfare

import com.clawfare.search.BatchConfig
import com.clawfare.search.BatchScenario
import com.clawfare.search.BatchSweep
import com.clawfare.search.Direction
import com.clawfare.search.FareSink
import com.clawfare.search.FilterSpec
import com.clawfare.search.Filters
import com.clawfare.search.Itinerary
import com.clawfare.search.Leg
import com.clawfare.search.OfferEligibility
import com.clawfare.search.ResultSet
import com.clawfare.search.SearchQuery
import com.clawfare.search.StoredResult
import com.clawfare.search.TransitRules
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrictFlightEligibilityTest {
    private val policy =
        SearchQuery(
            origins = listOf("LHR"),
            destinations = listOf("HND"),
            outboundFrom = "2028-06-01",
            outboundTo = "2028-06-01",
            tripType = "one_way",
            cabin = "business",
            cabinShortHaul = "economy",
            strictPerLegCabin = true,
            strictOperatingCarrier = true,
            excludedAirlines = listOf("LO", "AI", "VN"),
            excludedTransitCountries = listOf("CN", "HK", "MO"),
            providers = emptyList(),
        )

    private val hop =
        Leg(
            flightNumber = "AY 1",
            airline = "Finnair",
            airlineCode = "AY",
            operatingCarrier = "Finnair",
            from = "LHR",
            to = "HEL",
            departTime = "07:00",
            arriveTime = "10:00",
            departDate = "2028-06-01",
            arriveDate = "2028-06-01",
            cabin = "Economy",
        )
    private val long = hop.copy(flightNumber = "AY 2", from = "HEL", to = "HND", cabin = "Business")

    private fun itinerary(vararg segments: Leg): Itinerary =
        Itinerary(
            id = "synthetic",
            source = "serpapi",
            sourceLabel = "live",
            livePrice = true,
            pricePerPerson = 1500.0,
            passengers = 2,
            outboundDate = "2028-06-01",
            outbound = Direction(legs = segments.toList()),
        )

    @Test
    fun unknownAndMixedCabinNeverPretendToMeetBusinessThreshold() {
        assertNull(OfferEligibility.rejectionReason(itinerary(hop, long), policy))
        assertEquals("unknown_cabin", OfferEligibility.rejectionReason(itinerary(hop, long.copy(cabin = "Unspecified")), policy))
        assertEquals("unknown_cabin", OfferEligibility.rejectionReason(itinerary(hop, long.copy(cabin = null)), policy))
        assertEquals("insufficient_cabin", OfferEligibility.rejectionReason(itinerary(hop, long.copy(cabin = "Economy")), policy))
        assertEquals(
            "insufficient_cabin",
            OfferEligibility.rejectionReason(itinerary(hop, long), policy.copy(cabinShortHaul = "business")),
        )
        assertEquals(
            "unverified_return_legs",
            OfferEligibility.rejectionReason(itinerary(hop, long).copy(returnMissing = true), policy.copy(tripType = "round_trip")),
        )
    }

    @Test
    fun exactCarrierAliasesAndCodesharesAvoidSubstringFalsePositives() {
        val sample = itinerary(hop.copy(airline = "Air France", airlineCode = "AF"), long)
        assertTrue(Filters.matches(sample, FilterSpec(airlineBlock = listOf("Air"), hideMock = false)))
        assertTrue(Filters.matches(sample, FilterSpec(airlineBlock = listOf("AI"), hideMock = false)))
        val lot = itinerary(hop.copy(operatingCarrier = "LOT Polish Airlines"), long)
        assertFalse(Filters.matches(lot, FilterSpec(airlineBlock = listOf("LO"), hideMock = false)))
        val india = itinerary(hop.copy(operatingCarrier = "Air India"), long)
        assertFalse(Filters.matches(india, FilterSpec(airlineBlock = listOf("AI"), hideMock = false)))
        assertEquals("unknown_operator", OfferEligibility.rejectionReason(itinerary(hop, long.copy(operatingCarrier = "")), policy))
    }

    @Test
    fun connectionAirportOnEveryDirectionIsCheckedEvenWithoutLayoverArrays() {
        assertNull(TransitRules.rejectionReason(itinerary(hop, long), emptyList(), policy.excludedTransitCountries))
        for (blocked in listOf("HKG", "MFM", "PVG")) {
            val via = itinerary(hop.copy(to = blocked), long.copy(from = blocked))
            assertEquals(
                "excluded_transit_country",
                TransitRules.rejectionReason(via, emptyList(), policy.excludedTransitCountries),
            )
        }
        val disconnected = itinerary(hop.copy(to = "FRA"), long.copy(from = "HEL"))
        assertEquals("disconnected_legs", TransitRules.rejectionReason(disconnected, emptyList(), emptyList()))
        val unknown = itinerary(hop.copy(to = "ZZZ"), long.copy(from = "ZZZ"))
        assertEquals(
            "unknown_transit_country",
            TransitRules.rejectionReason(unknown, emptyList(), policy.excludedTransitCountries),
        )
        val returning =
            Direction(legs = listOf(long.copy(from = "HND", to = "HKG"), hop.copy(from = "HKG", to = "LHR")))
        val roundTrip = itinerary(hop, long).copy(returning = returning)
        assertEquals(
            "excluded_transit_country",
            TransitRules.rejectionReason(roundTrip, emptyList(), policy.excludedTransitCountries),
        )
    }

    @Test
    fun batchReportRechecksSavedOffersBeforeRankingAndAlerts() {
        val config = BatchConfig("strict-report", listOf(BatchScenario("flight", policy)), maxLiveCalls = 0)
        val key = BatchSweep.expand(config).single().signature
        val good = itinerary(hop, long)
        val invalid = itinerary(hop, long.copy(cabin = "Economy")).copy(id = "cheap-invalid", pricePerPerson = 10.0)
        val sink =
            object : FareSink {
                override fun save(stored: StoredResult): ResultSet = stored.result

                override fun load(id: String): StoredResult? =
                    if (id == key) StoredResult(ResultSet(id, "fixture", itineraries = listOf(invalid, good))) else null

                override fun list(): List<ResultSet> = emptyList()
            }
        val rows = BatchSweep.compare(config, sink)
        assertEquals(1, rows.size)
        assertEquals(3000.0, rows.single().partyFareGbp)
    }
}
