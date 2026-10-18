package com.clawfare

import com.clawfare.search.DiskCache
import com.clawfare.search.Export
import com.clawfare.search.FilterSpec
import com.clawfare.search.Filters
import com.clawfare.search.GoogleFlightsLink
import com.clawfare.search.Leg
import com.clawfare.search.SampleData
import com.clawfare.search.VendorMath
import com.clawfare.search.parseGoogleFlightsOffers
import com.clawfare.search.redact
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoogleFlightsLinkTest {
    @Test
    fun exactRoundTripPremium() {
        val out = leg("BA 211", "BA", "LHR", "TPA", "2026-12-19", "13:00", "17:45")
        val back = leg("BA 210", "BA", "TPA", "LHR", "2026-12-27", "20:10", "09:30", arriveDate = "2026-12-28")
        val url = GoogleFlightsLink.roundTrip("2026-12-19", "2026-12-27", "LHR", "TPA", "premium_economy", listOf(out), listOf(back))
        assertEquals(
            "https://www.google.com/travel/flights?hl=en&gl=uk&curr=GBP&tfs=CBwQAho_EgoyMDI2LTEyLTE5Ih8KA0xIUhIKMjAyNi0xMi0xORoDVFBBKgJCQTIDMjExagcIARIDTEhScgcIARIDVFBBGj8SCjIwMjYtMTItMjciHwoDVFBBEgoyMDI2LTEyLTI3GgNMSFIqAkJBMgMyMTBqBwgBEgNUUEFyBwgBEgNMSFJAAUgCcAGYAQE&tfu=EgIIAQ",
            url,
        )
    }

    @Test
    fun aeromexicoRoundTripAndOneWay() {
        val out = leg("AM 1741", "AM", "TPA", "MEX", "2026-12-18", "09:23", "11:50")
        val back = leg("AM 1740", "AM", "MEX", "TPA", "2026-12-21", "15:40", "19:49")
        assertEquals(
            "https://www.google.com/travel/flights?hl=en&gl=uk&curr=GBP&tfs=CBwQAhpAEgoyMDI2LTEyLTE4IiAKA1RQQRIKMjAyNi0xMi0xOBoDTUVYKgJBTTIEMTc0MWoHCAESA1RQQXIHCAESA01FWBpAEgoyMDI2LTEyLTIxIiAKA01FWBIKMjAyNi0xMi0yMRoDVFBBKgJBTTIEMTc0MGoHCAESA01FWHIHCAESA1RQQUABSAFwAZgBAQ&tfu=EgIIAQ",
            GoogleFlightsLink.roundTrip("2026-12-18", "2026-12-21", "TPA", "MEX", "economy", listOf(out), listOf(back)),
        )
        val christmas = leg("AM 1741", "AM", "TPA", "MEX", "2026-12-25", "09:23", "11:50")
        assertEquals(
            "https://www.google.com/travel/flights?hl=en&gl=uk&curr=GBP&tfs=CBwQAhpAEgoyMDI2LTEyLTI1IiAKA1RQQRIKMjAyNi0xMi0yNRoDTUVYKgJBTTIEMTc0MWoHCAESA1RQQXIHCAESA01FWEABSAFwAZgBAg&tfu=EgIIAQ",
            GoogleFlightsLink.oneWay("2026-12-25", "TPA", "MEX", "economy", listOf(christmas)),
        )
    }

    @Test
    fun searchOnlyLinkHasNoFlightNumbers() {
        assertEquals(
            "https://www.google.com/travel/flights?hl=en&gl=uk&curr=GBP&tfs=CBwQAhoeEgoyMDI2LTEyLTE5agcIARIDTEhScgcIARIDVFBBGh4SCjIwMjYtMTItMjdqBwgBEgNUUEFyBwgBEgNMSFJAAUgCcAGYAQE&tfu=EgIIAQ",
            GoogleFlightsLink.roundTrip("2026-12-19", "2026-12-27", "LHR", "TPA", "premium_economy"),
        )
    }
}

class FilterTest {
    private val data = SampleData.florida()

    private fun id(n: Int) = data.itineraries.first { it.id == n.toString() }

    @Test
    fun publishedCuts() {
        val stops = FilterSpec(maxStops = 1)
        assertTrue(stops.let { Filters.matches(id(9), it) })
        assertFalse(Filters.matches(id(7), stops))
        assertFalse(Filters.matches(id(8), stops))

        val home = FilterSpec(excludeReturnDates = listOf("2026-12-24", "2026-12-25"))
        for (n in 1..6) assertFalse(Filters.matches(id(n), home), "id $n flies home on the 24th or 25th")
        assertTrue(Filters.matches(id(9), home))

        val layover = FilterSpec(maxLayoverMinutes = 300)
        assertFalse(Filters.matches(id(15), layover))
        assertFalse(Filters.matches(id(19), layover))
        assertTrue(Filters.matches(id(11), layover))
    }

    @Test
    fun departureWindowCabinAndAirlines() {
        val after = FilterSpec(outboundDepartAfter = "14:00")
        assertTrue(Filters.matches(id(10), after))
        assertFalse(Filters.matches(id(11), after))

        assertTrue(id(11).euPremium)
        assertTrue(id(3).euPremium)
        assertFalse(id(9).euPremium)
        assertFalse(id(14).euPremium)
        assertFalse(Filters.matches(id(9), FilterSpec(europeanPremiumOnly = true)))

        assertTrue(Filters.matches(id(11), FilterSpec(nonstopOnly = true)))
        assertFalse(Filters.matches(id(10), FilterSpec(nonstopOnly = true)))

        val ba = FilterSpec(airlineAllow = listOf("BA"))
        assertTrue(Filters.matches(id(11), ba))
        assertFalse(Filters.matches(id(3), ba))
    }
}

class FeasibilityTest {
    @Test
    fun cheapestFeasibleCouplePlusMexicoIs3341() {
        val data = SampleData.florida()
        val filters = data.suggestedFilters
        val visible =
            data.itineraries
                .filter { Filters.matches(it, filters) }
                .map { it.id }
                .toSet()
        assertFalse("7" in visible)
        assertFalse("1" in visible)
        assertTrue("23" in visible)
        val priced = data.cells.filter { it.addonId != null && it.primaryId in visible && it.status != "no" }
        assertEquals(3341.0, priced.minOf { it.total })
        val cell = data.cells.first { it.primaryId == "23" && it.addonId == "H" }
        assertEquals("ok", cell.status)
        assertEquals(3341.0, cell.total)
    }
}

class VendorMathTest {
    @Test
    fun bagFeeAndSampleRows() {
        assertEquals(45.0, VendorMath.bagFee("uk", "1 free carry-on", 2, 1))
        assertEquals(882.0, 837 + VendorMath.bagFee("uk", "1 free carry-on", 2, 1))
        assertEquals(40.0, VendorMath.bagFee("us", "1st checked bag: 40", 2, 1))
        assertEquals(0.0, VendorMath.bagFee("uk", "Premier includes a free checked bag", 2, 1))
        val rows = SampleData.florida().vendors
        assertEquals(165, rows.size)
        assertEquals(745.0, rows.first().allInGbp)
        assertEquals("risky", rows.first().trust)
        assertTrue(rows.first().mix)
        assertTrue(rows.first().vendor.contains("BudgetAir"))
    }
}

class SampleDataTest {
    @Test
    fun floridaShapeAndLinks() {
        val data = SampleData.florida()
        assertEquals(26, data.itineraries.size)
        assertEquals(8, data.addons.size)
        val ba = data.itineraries.first { it.id == "11" }
        assertEquals(
            "https://www.google.com/travel/flights?hl=en&gl=uk&curr=GBP&tfs=CBwQAho_EgoyMDI2LTEyLTE5Ih8KA0xIUhIKMjAyNi0xMi0xORoDVFBBKgJCQTIDMjExagcIARIDTEhScgcIARIDVFBBGj8SCjIwMjYtMTItMjciHwoDVFBBEgoyMDI2LTEyLTI3GgNMSFIqAkJBMgMyMTBqBwgBEgNUUEFyBwgBEgNMSFJAAUgCcAGYAQE&tfu=EgIIAQ",
            ba.links.first { it.kind == "exact" }.url,
        )
        val mexico = data.addons.first { it.id == "A" }
        assertEquals(
            "https://www.google.com/travel/flights?hl=en&gl=uk&curr=GBP&tfs=CBwQAhpAEgoyMDI2LTEyLTE4IiAKA1RQQRIKMjAyNi0xMi0xOBoDTUVYKgJBTTIEMTc0MWoHCAESA1RQQXIHCAESA01FWBpAEgoyMDI2LTEyLTIxIiAKA01FWBIKMjAyNi0xMi0yMRoDVFBBKgJBTTIEMTc0MGoHCAESA01FWHIHCAESA1RQQUABSAFwAZgBAQ&tfu=EgIIAQ",
            mexico.links.first { it.kind == "exact" }.url,
        )
        val selected = SampleData.selectedItineraries()
        assertTrue(selected.isNotEmpty())
        assertTrue(selected.first().outboundDate.startsWith("2026-12-"))
        assertEquals(
            "KL 1000",
            selected
                .first()
                .outbound.legs
                .first()
                .flightNumber,
        )
    }
}

class GoogleFlightsParseTest {
    @Test
    fun offersLayoversAndBookingOptions() {
        val body =
            """
            {
              "best_flights": [{
                "price": 1296,
                "total_duration": 466,
                "departure_token": "dep-1",
                "booking_token": "book-1",
                "flights": [
                  {
                    "departure_airport": {"id": "LHR", "time": "2026-12-23 06:30"},
                    "arrival_airport": {"id": "AMS", "time": "2026-12-23 09:00"},
                    "duration": 90,
                    "airline": "KLM",
                    "flight_number": "KL 1000",
                    "travel_class": "Premium economy"
                  },
                  {
                    "departure_airport": {"id": "AMS", "time": "2026-12-23 09:55"},
                    "arrival_airport": {"id": "TPA", "time": "2026-12-23 14:16"},
                    "duration": 321,
                    "airline": "Delta",
                    "flight_number": "DL 237"
                  }
                ],
                "layovers": [{"id": "AMS", "duration": 55}]
              }],
              "other_flights": []
            }
            """.trimIndent()
        val offers = parseGoogleFlightsOffers(body)
        assertEquals(1, offers.size)
        assertEquals(1296.0, offers[0].price)
        assertEquals(2, offers[0].legs.size)
        assertEquals(55, offers[0].layovers.single().minutes)
        assertEquals("dep-1", offers[0].departureToken)
        assertEquals("book-1", offers[0].bookingToken)
        assertEquals("2026-12-23", offers[0].legs[0].departDate)
        assertEquals("06:30", offers[0].legs[0].departTime)
    }
}

class DiskCacheTest {
    @Test
    fun roundTripIgnoresSecretsInTheKeyAndBody() {
        val dir =
            File.createTempFile("clawfare-cache", "").apply {
                delete()
                mkdirs()
            }
        val cache = DiskCache(dir)
        val params = mapOf("engine" to "google_flights", "departure_id" to "LHR", "api_key" to "serp-secret-KEY123")
        cache.put("serpapi", params, """{"error":"used serp-secret-KEY123"}""", listOf("serp-secret-KEY123"))
        val again = mapOf("departure_id" to "LHR", "engine" to "google_flights", "token" to "other-secret")
        val hit = cache.get("serpapi", again)
        assertNotNull(hit)
        assertFalse(hit.body.contains("serp-secret-KEY123"))
        assertTrue(hit.body.contains("REDACTED"))
        val text = dir.walk().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse(text.contains("serp-secret-KEY123"))
        assertFalse(text.contains("other-secret"))
    }
}

class RedactTest {
    @Test
    fun stripsKeysBearerAndDuffelTokens() {
        val raw = "call https://x.test/search?api_key=abc123def&hl=en Authorization: Bearer live.token.123 duffel_test_abcDEF123"
        val clean = redact(raw)
        assertFalse(clean.contains("abc123def"))
        assertFalse(clean.contains("live.token.123"))
        assertFalse(clean.contains("duffel_test_abcDEF123"))
        assertTrue(clean.contains("api_key=REDACTED"))
        assertTrue(clean.contains("Bearer REDACTED"))
    }
}

class ExportTest {
    @Test
    fun htmlAndCsvFromTheSample() {
        val data = SampleData.florida()
        val page = "<html><script>window.__SNAPSHOT__ = null;</script></html>"
        val html = Export.html(page, data)
        assertTrue(html.contains("BA 211"))
        assertTrue(html.contains("3341"))
        assertTrue(html.contains("BudgetAir"))
        assertFalse(html.contains("api_key"))
        assertFalse(html.contains("window.__SNAPSHOT__ = null"))

        val csv = Export.itineraryCsv(data, FilterSpec())
        assertTrue(csv.lineSequence().first().startsWith("id,origin,destination"))
        assertTrue(csv.contains("KL 1000"))
        val filtered = Export.itineraryCsv(data, data.suggestedFilters)
        assertFalse(filtered.lines().any { it.startsWith("7,") || it.startsWith("8,") })
        assertTrue(filtered.contains("KL 1000"))

        val injected = data.copy(itineraries = data.itineraries.map { if (it.id == "11") it.copy(airlineSummary = "=SUM(A1)") else it })
        val safe = Export.itineraryCsv(injected, FilterSpec(airlineAllow = listOf("BA"), nonstopOnly = true))
        assertTrue(safe.contains("'=SUM(A1)"))
    }
}

private fun leg(
    number: String,
    code: String,
    from: String,
    to: String,
    date: String,
    depart: String,
    arrive: String,
    arriveDate: String = date,
) = Leg(
    flightNumber = number,
    airline = code,
    airlineCode = code,
    operatingCarrier = code,
    from = from,
    to = to,
    departTime = depart,
    arriveTime = arrive,
    departDate = date,
    arriveDate = arriveDate,
)
