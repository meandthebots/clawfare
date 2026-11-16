package com.clawfare

import com.clawfare.search.Direction
import com.clawfare.search.Itinerary
import com.clawfare.search.Leg
import com.clawfare.search.OfferEligibility
import com.clawfare.search.SearchQuery
import com.clawfare.search.TransitRules
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OfferEligibilityTest {
    private val q =
        SearchQuery(
            cabin = "business",
            cabinShortHaul = "economy",
            strictPerLegCabin = true,
            strictOperatingCarrier = true,
            excludedAirlines = listOf("AI", "LO"),
            excludedTransitCountries = listOf("CN", "HK", "MO"),
        )

    private val short =
        Leg(
            flightNumber = "AY 100",
            airline = "Finnair",
            airlineCode = "AY",
            operatingCarrier = "Finnair",
            from = "LHR",
            to = "HEL",
            departTime = "09:00",
            arriveTime = "12:00",
            departDate = "2027-05-01",
            arriveDate = "2027-05-01",
            durationMinutes = 180,
            cabin = "Economy",
        )
    private val long =
        short.copy(
            flightNumber = "AY 900",
            from = "HEL",
            to = "HND",
            cabin = "Business",
            durationMinutes = 720,
        )
    private val eligible = Itinerary("offer", outbound = Direction(legs = listOf(short, long)))

    @Test
    fun businessLongHaulAndEconomyEuropeHopPassOnlyUnderExplicitPolicy() {
        assertNull(OfferEligibility.rejectionReason(eligible, q))
        assertEquals(
            "insufficient_cabin",
            OfferEligibility.rejectionReason(eligible, q.copy(cabinShortHaul = "business")),
        )
        assertEquals(
            "insufficient_cabin",
            OfferEligibility.rejectionReason(
                eligible.copy(outbound = Direction(legs = listOf(short, long.copy(cabin = "Economy")))),
                q,
            ),
        )
        assertEquals(
            "unknown_cabin",
            OfferEligibility.rejectionReason(
                eligible.copy(outbound = Direction(legs = listOf(short, long.copy(cabin = null)))),
                q,
            ),
        )
    }

    @Test
    fun operatorAndCodeshareExclusionsApplyOnEveryLeg() {
        val codeshare = short.copy(operatingCarrier = "Air India")
        assertEquals(
            "excluded_carrier",
            OfferEligibility.rejectionReason(eligible.copy(outbound = Direction(legs = listOf(codeshare, long))), q),
        )
        val missing = long.copy(operatingCarrier = "")
        assertEquals(
            "unknown_operator",
            OfferEligibility.rejectionReason(eligible.copy(outbound = Direction(legs = listOf(short, missing))), q),
        )
    }

    @Test
    fun excludedAndUnknownIntermediateAirportsFailClosedOnEitherDirection() {
        val viaHongKong = short.copy(to = "HKG")
        val continuation = long.copy(from = "HKG")
        val blocked = eligible.copy(outbound = Direction(legs = listOf(viaHongKong, continuation)))
        assertEquals("excluded_transit_country", OfferEligibility.rejectionReason(blocked, q))
        val unknown =
            blocked.copy(
                outbound = Direction(legs = listOf(viaHongKong.copy(to = "ZZZ"), continuation.copy(from = "ZZZ"))),
            )
        assertEquals("unknown_transit_country", OfferEligibility.rejectionReason(unknown, q))
        assertEquals(
            "excluded_transit_country",
            OfferEligibility.rejectionReason(eligible.copy(returning = blocked.outbound), q),
        )
        assertNull(TransitRules.rejectionReason(eligible, emptyList(), q.excludedTransitCountries))
    }

    @Test
    fun ourAirportsRejectsConflictingIataAssignments() {
        val csv = File.createTempFile("clawfare-airports", ".csv")
        try {
            csv.writeText("iata_code,iso_country\\nHEL,FI\\nHEL,CN\\n".replace("\\n", "\n"))
            assertFailsWith<IllegalArgumentException> { TransitRules.parseOurAirports(csv) }
        } finally {
            csv.delete()
        }
    }
}
