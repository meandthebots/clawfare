package com.clawfare.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlightDirectionsTest {
    private fun leg(
        from: String,
        to: String,
        departDate: String,
        depart: String,
        arriveDate: String,
        arrive: String,
        carrier: String = "British Airways",
        cabin: String? = "Premium economy",
    ): Leg =
        Leg(
            flightNumber = "BA 12",
            airline = carrier,
            airlineCode = if (carrier == "British Airways") "BA" else "AA",
            operatingCarrier = carrier,
            from = from,
            to = to,
            departTime = depart,
            arriveTime = arrive,
            departDate = departDate,
            arriveDate = arriveDate,
            cabin = cabin,
        )

    @Test
    fun directionGetsRealLocalLayoverAndNoInventedDuration() {
        val legs =
            listOf(
                leg("LHR", "AMS", "2027-04-01", "06:00", "2027-04-01", "09:00"),
                leg("AMS", "TPA", "2027-04-01", "09:55", "2027-04-01", "15:00"),
            )
        val direction = directionOf(legs)
        assertEquals(1, direction.stops)
        assertEquals(listOf(Layover("AMS", 55)), direction.layovers)
        assertNull(direction.durationMinutes)
        assertEquals(500, directionOf(legs, apiDuration = 500).durationMinutes)
    }

    @Test
    fun explicitApiLayoversOverrideLocalDerivedOnes() {
        val legs =
            listOf(
                leg("LHR", "AMS", "2027-04-01", "06:00", "2027-04-01", "09:00"),
                leg("AMS", "TPA", "2027-04-01", "09:55", "2027-04-01", "15:00"),
            )
        val direction = directionOf(legs, listOf(Layover("AMS", 70)), 550)
        assertEquals(listOf(Layover("AMS", 70)), direction.layovers)
        assertEquals(550, direction.durationMinutes)
    }

    @Test
    fun missingAndBackwardsTimestampsNeverCreateNegativeLayovers() {
        val legs =
            listOf(
                leg("LHR", "AMS", "2027-04-01", "06:00", "2027-04-02", "12:00"),
                leg("AMS", "TPA", "2027-04-01", "09:55", "2027-04-01", "15:00"),
            )
        assertTrue(computeLayovers(legs).isEmpty())
        assertEquals(0, directionOf(emptyList()).stops)
    }

    @Test
    fun europeanPremiumRequiresTransatlanticEuropeanOperator() {
        val british = leg("LHR", "TPA", "2027-04-01", "11:00", "2027-04-01", "16:00")
        val american =
            leg(
                "LHR",
                "TPA",
                "2027-04-01",
                "11:00",
                "2027-04-01",
                "16:00",
                "American Airlines",
            )
        assertTrue(euPremium("premium_economy", listOf(british)))
        assertFalse(euPremium("premium_economy", listOf(american)))
        assertFalse(euPremium("economy", listOf(british)))
        assertFalse(euPremium("premium_economy", listOf(british.copy(from = "AMS", to = "LHR"))))
        // This classifier is permissive for missing cabin metadata, but the
        // strict per-leg policy is enforced separately by OfferEligibility.
        assertTrue(euPremium("premium_economy", listOf(british.copy(cabin = null))))
    }
}
