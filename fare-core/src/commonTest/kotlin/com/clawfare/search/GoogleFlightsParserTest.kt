package com.clawfare.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleFlightsParserTest {
    // The same redacted raw payload is used in JVM and Kotlin/JS tests.
    private val googleFixture = """
        {
          "best_flights": [{
            "price": 1234.5,
            "total_duration": 850,
            "fare_brand": "Premium",
            "departure_token": "private-departure",
            "booking_token": "private-booking",
            "flights": [{
              "flight_number": "BA 123",
              "airline": "British Airways",
              "departure_airport": {"id": "LHR", "time": "2027-04-12 11:30"},
              "arrival_airport": {"id": "NRT", "time": "2027-04-13 08:15"},
              "duration": 750,
              "travel_class": "Premium Economy",
              "airplane": "Boeing 787",
              "plane_and_crew_by": "British Airways"
            }],
            "layovers": []
          }],
          "other_flights": [{
            "flights": [{
              "flight_number": "B6789",
              "airline": "JetBlue",
              "departure_airport": {"id": "JFK", "time": "2027-04-12 07:05"},
              "arrival_airport": {"id": "MCO", "time": "2027-04-12 09:40"}
            }],
            "layovers": [{"id": "JFK", "duration": 110}]
          }]
        }
    """.trimIndent()

    @Test
    fun parsesGoogleFlightOffersAndPreservesUnknownEvidence() {
        val results = parseGoogleFlightsOffers(googleFixture)
        assertEquals(2, results.size)
        val flight = results[0]
        assertEquals(1234.5, flight.price)
        assertEquals("Premium", flight.fareBrand)
        assertEquals("private-departure", flight.departureToken)
        assertEquals("private-booking", flight.bookingToken)
        assertEquals(850, flight.durationMinutes)
        val leg = flight.legs.single()
        assertEquals("BA", leg.airlineCode)
        assertEquals("LHR", leg.from)
        assertEquals("NRT", leg.to)
        assertEquals("2027-04-12", leg.departDate)
        assertEquals("11:30", leg.departTime)
        assertEquals("2027-04-13", leg.arriveDate)
        assertEquals("08:15", leg.arriveTime)
        assertTrue(leg.operatingCarrierVerified)
        val missing = results[1]
        assertNull(missing.price, "unpriced flights are never priced at zero")
        assertNull(missing.departureToken)
        assertFalse(missing.legs.single().operatingCarrierVerified)
        assertEquals("B6", missing.legs.single().airlineCode)
        assertEquals(110, missing.layovers.single().minutes)
    }

    @Test
    fun ignoresMalformedOffersWithoutManufacturingFlights() {
        assertTrue(parseGoogleFlightsOffers("{}").isEmpty())
        assertTrue(parseGoogleFlightsOffers("""{"best_flights": [{"price": 0}]}""").isEmpty())
        assertTrue(parseGoogleFlightsOffers("""{"best_flights": [{"flights": []}]}""").isEmpty())
        assertTrue(parseGoogleFlightsOffers("""{"best_flights": "not an array"}""").isEmpty())
    }

    @Test
    fun flightCodeExtractionIsCompatibleWithGoogleFlightsLink() {
        assertEquals("BA" to "123", splitFlightCode("BA 123", ""))
        assertEquals("B6" to "789", splitFlightCode("B6789", ""))
        assertEquals("JL" to "8", splitFlightCode("JL 8", ""))
        assertEquals("NH" to "NH8", splitFlightCode("NH8", "NH"))
    }

    @Test
    fun parserResultIsPortableAcrossKotlinSerialization() {
        val values = parseGoogleFlightsOffers(googleFixture)
        val json = AppJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ParsedOffer.serializer()),
            values,
        )
        val restored = AppJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ParsedOffer.serializer()),
            json,
        )
        assertEquals(values, restored)
    }
}
