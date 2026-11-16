package com.clawfare

import com.clawfare.db.ClawfareDatabase
import com.clawfare.search.AppJson
import com.clawfare.search.DiskCache
import com.clawfare.search.FareStore
import com.clawfare.search.GoogleFlightsProvider
import com.clawfare.search.GoogleFlightsStyle
import com.clawfare.search.HttpFetcher
import com.clawfare.search.HttpResult
import com.clawfare.search.Leg
import com.clawfare.search.SampleData
import com.clawfare.search.SellerOption
import com.clawfare.search.StoredResult
import com.clawfare.search.assembleItinerary
import com.clawfare.search.parseGoogleSellerOptions
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SellerOptionsTest {
    private val snapshot = "2026-11-16T02:00:00Z"
    private val nested =
        """
        {
          "booking_options": [
            {"together":{"book_with":"British Airways","option_title":"Business Basic","price":4200,
              "airline":true,"baggage_prices":["1st checked bag: free"],
              "booking_request":{"url":"https://www.google.com/travel/clk/f","post_data":"PRIVATE_BOOKING_REQUEST"}}},
            {"together":{"book_with":"British Airways","option_title":"Business Flex","price":5100,
              "airline":true,"baggage_prices":["1st checked bag: free"]}},
            {"together":{"book_with":"British Airways","option_title":"Business Flex","price":5150,
              "airline":true,"baggage_prices":["1st checked bag: free"]}},
            {"separate_tickets":true,"together":{"book_with":"Agency + Airline","price":3800}},
            {"together":{"book_with":"OTA Without Price","option_title":"Business Flex"}}
          ]
        }
        """.trimIndent()
    private val flat =
        """
        {
          "booking_options": [
            {"book_with":"British Airways","fare_type":"BUSINESS BASIC","option_title":"Business Basic",
             "price":4000,"baggage_prices":["1st checked bag: 45"]},
            {"book_with":"British Airways","fare_type":"BUSINESS FLEX","option_title":"Business Flex",
             "price":5000,"baggage_prices":["1st checked bag: free"]},
            {"book_with":"Phone Seller","price":4050,"estimated_phone_service_fee":50},
            {"is_split_booking":true,"book_with":"Multiple Agencies","price":3900,
             "departure":{"book_with":"Seller A"},"arrival":{"book_with":"Seller B"}}
          ]
        }
        """.trimIndent()

    @Test
    fun serpaNestedPreservesTwoFareFamiliesAndNonRankableAlternatives() {
        val options = parse(nested, "serpapi", "uk", "GBP")
        assertEquals(5, options.size)
        assertEquals(3, options.count { it.seller == "British Airways" })
        assertEquals(2, options.count { it.fareBrand == "Business Flex" })
        assertEquals(3, options.map { it.id }.toSet().intersect(options.filter { it.seller == "British Airways" }.map { it.id }.toSet()).size)
        assertTrue(options.first().directAirline == true)
        assertTrue(options[3].splitTickets)
        assertEquals(null, options[4].priceTotal)
        assertTrue(options.all { it.refundStatus == "unverified" })
        assertNotEquals(options[0].productKey, options[1].productKey)
        assertEquals(options[1].productKey, options[2].productKey)
        assertNotEquals(options[1].id, options[2].id)
        val serialized = AppJson.encodeToString(ListSerializer(SellerOption.serializer()), options)
        assertFalse(serialized.contains("PRIVATE_BOOKING_REQUEST"))
        assertFalse(serialized.contains("booking_request"))
        assertFalse(serialized.contains("https://www.google.com/travel/clk/f"))
        assertTrue(serialized.contains("Business Flex"))
        assertTrue(options.first().bookingActionAvailable)
    }

    @Test
    fun searchApiFlatSchemaAndMarketIdentityNeverClaimCashRefund() {
        val uk = parse(flat, "searchapi", "uk", "GBP")
        val fr = parse(flat, "searchapi", "fr", "EUR")
        assertEquals(4, uk.size)
        assertEquals(2, uk.count { it.seller == "British Airways" })
        assertEquals(50.0, uk[2].phoneServiceFee)
        assertTrue(uk[3].splitTickets)
        assertEquals("BUSINESS BASIC", uk.first().fareType)
        assertTrue(uk.all { it.refundStatus == "unverified" })
        assertNotEquals(uk.first().id, fr.first().id)
        assertNotEquals(uk.first().productKey, fr.first().productKey)
        assertEquals("EUR", fr.first().currency)
        assertTrue(parse("{}", "searchapi", "uk", "GBP").isEmpty())
        assertTrue(parse("{broken", "searchapi", "uk", "GBP").isEmpty())
    }

    @Test
    fun exactRouteSellerLookupRetainsBrandsAndDoesNotSendBookingPost() {
        val urlLog = mutableListOf<String>()
        val outbound =
            """
            {"best_flights":[{"price":4200,"booking_token":"SYNTHETIC_BOOKING_TOKEN",
              "flights":[{"departure_airport":{"id":"LHR","time":"2028-04-21 09:00"},
              "arrival_airport":{"id":"HND","time":"2028-04-22 08:00"},"airline":"British Airways",
              "flight_number":"BA 5","travel_class":"Business"}]}]}
            """.trimIndent()
        val http =
            object : HttpFetcher {
                override fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    body: String?,
                ): HttpResult {
                    assertEquals("GET", method)
                    assertEquals(null, body)
                    urlLog += url
                    return HttpResult(200, if ("booking_token=" in url) nested else outbound)
                }
            }
        val leg =
            Leg(
                flightNumber = "BA 5",
                airline = "British Airways",
                airlineCode = "BA",
                operatingCarrier = "British Airways",
                from = "LHR",
                to = "HND",
                departTime = "09:00",
                arriveTime = "08:00",
                departDate = "2028-04-21",
                arriveDate = "2028-04-22",
                cabin = "Business",
            )
        val fare =
            assembleItinerary(
                "synthetic-flight",
                2100.0,
                "GBP",
                2,
                "business",
                listOf(leg),
                null,
                false,
                "serpapi",
                "live",
                true,
                snapshot,
            )
        val dir =
            File.createTempFile("seller-options", "").apply {
                delete()
                mkdirs()
            }
        val provider = GoogleFlightsProvider("serpapi", "SerpApi", "test-secret", GoogleFlightsStyle.SERPAPI, http)
        val response = provider.vendors(fare, null, listOf("uk"), 1, DiskCache(dir), allowNetwork = true)
        assertTrue(response.options.count { it.fareBrand == "Business Basic" } >= 1)
        assertTrue(response.options.count { it.fareBrand == "Business Flex" } >= 2)
        assertTrue(response.rows.any { it.roundTrip == 4200.0 && it.fareBrand == "Business Basic" })
        assertTrue(response.rows.any { it.roundTrip == 5100.0 && it.fareBrand == "Business Flex" })
        assertTrue(response.rows.none { it.vendor.contains("Agency + Airline") })
        assertTrue(urlLog.all { it.startsWith("https://serpapi.com/search.json?") })
        assertTrue(response.options.all { it.passengers == 2 })
        assertTrue(response.warnings.any { "split/unpriced" in it })

        val bad = fare.copy(outbound = fare.outbound.copy(legs = listOf(leg.copy(flightNumber = "BA 999"))))
        val noMatch = provider.vendors(bad, null, listOf("fr"), 1, DiskCache(dir), allowNetwork = true)
        assertTrue(noMatch.options.isEmpty())
        assertTrue(noMatch.rows.none { it.roundTrip != null })
        assertTrue(noMatch.warnings.any { "no matching flight" in it })
    }

    @Test
    fun evidenceOnlyOptionsSurviveSqliteWithoutOpaqueTokens() {
        ClawfareDatabase.connectInMemory()
        val options = parse(nested, "serpapi", "uk", "GBP")
        val sample = SampleData.florida().copy(sellerOptions = options, vendorItineraryId = "H")
        val stored = FareStore.save(StoredResult(sample))
        assertEquals(options.size, stored.sellerOptions.size)
        assertEquals(options.map { it.id }, FareStore.project(sample.id)!!.sellerOptions.map { it.id })
        val publicJson = AppJson.encodeToString(SellerOption.serializer(), stored.sellerOptions.first())
        assertFalse(publicJson.contains("PRIVATE_BOOKING_REQUEST"))
    }

    private fun parse(
        body: String,
        provider: String,
        market: String,
        currency: String,
    ): List<SellerOption> =
        parseGoogleSellerOptions(
            body,
            provider,
            market,
            currency,
            "synthetic-itinerary",
            2,
            "round_trip",
            snapshot,
        )
}
