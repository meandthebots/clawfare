package com.clawfare.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlightDatesTest {
    @Test
    fun leapDayIsIncludedInSearchAndNightOffset() {
        val q =
            normalizeQuery(
                SearchQuery(
                    origins = listOf("LHR"),
                    destinations = listOf("TPA"),
                    outboundFrom = "2028-02-28",
                    outboundTo = "2028-03-01",
                    nights = 2,
                ),
            )
        assertEquals(
            listOf(
                "2028-02-28" to "2028-03-01",
                "2028-02-29" to "2028-03-02",
                "2028-03-01" to "2028-03-03",
            ),
            datePairs(q),
        )
        assertEquals(2, nightsBetween("2028-02-28", "2028-03-01"))
    }

    @Test
    fun oneWayIgnoresReturnRange() {
        val plan =
            planFlightSearch(
                SearchQuery(
                    origins = listOf("lhr"),
                    destinations = listOf("tpa"),
                    outboundFrom = "2027-03-10",
                    outboundTo = "2027-03-11",
                    tripType = "one_way",
                    nights = 8,
                    returnFrom = "2027-03-01",
                ),
            )
        assertNull(plan.error)
        assertEquals(
            listOf(
                FlightDatePair("2027-03-10"),
                FlightDatePair("2027-03-11"),
            ),
            plan.pairs,
        )
        assertNull(plan.query.nights)
    }

    @Test
    fun returnRangeExcludesSameDayAndEarlier() {
        val q =
            normalizeQuery(
                SearchQuery(
                    origins = listOf("LHR"),
                    destinations = listOf("TPA"),
                    outboundFrom = "2027-04-01",
                    outboundTo = "2027-04-02",
                    returnFrom = "2027-04-02",
                    returnTo = "2027-04-03",
                ),
            )
        assertEquals(
            listOf(
                "2027-04-01" to "2027-04-02",
                "2027-04-01" to "2027-04-03",
                "2027-04-02" to "2027-04-03",
            ),
            datePairs(q),
        )
    }

    @Test
    fun overnightLayoverUsesCalendarDaysNotUtc() {
        assertEquals(105, minutesBetween("2027-04-01", "23:30", "2027-04-02", "01:15"))
        assertEquals(-105, minutesBetween("2027-04-02", "01:15", "2027-04-01", "23:30"))
        assertEquals("09:05", normalizeTime("9:05"))
    }

    @Test
    fun dstTransitionDoesNotApplyHiddenTimezone() {
        // These are local airport timestamps; without the airport's actual time
        // zone we must not infer a DST shift or a real flight duration.
        assertEquals(120, minutesBetween("2027-03-28", "01:30", "2027-03-28", "03:30"))
        assertEquals(90, minutesBetween("2027-10-31", "01:00", "2027-10-31", "02:30"))
    }

    @Test
    fun invalidDatesAndTimesFailClosed() {
        assertNull(nightsBetween("2027-02-29", "2027-03-01"))
        assertNull(minutesBetween("2027-04-01", "25:00", "2027-04-02", "01:00"))
        assertNull(minutesBetween("not a date", "09:00", "2027-04-02", "10:00"))
        val plan =
            planFlightSearch(
                SearchQuery(
                    origins = listOf("LHR"),
                    destinations = listOf("TPA"),
                    outboundFrom = "2027-02-29",
                    nights = 8,
                ),
            )
        assertNotNull(plan.error)
        assertTrue(plan.pairs.isEmpty())
    }

    @Test
    fun searchLimitAndMissingReturnAreValidatedWithoutNetwork() {
        val q = SearchQuery(origins = listOf("LHR"), destinations = listOf("TPA"), outboundFrom = "2027-04-01")
        assertEquals("Set a return date range or a trip length in nights.", planFlightSearch(q).error)
        val tooMany = planFlightSearch(q.copy(outboundTo = "2029-04-01", tripType = "one_way"))
        assertNotNull(tooMany.error)
        assertTrue(tooMany.error.contains("route searches"))
        assertTrue(tooMany.pairs.isEmpty())
    }
}
