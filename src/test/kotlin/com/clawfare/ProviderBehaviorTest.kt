package com.clawfare

import com.clawfare.search.DiskCache
import com.clawfare.search.DuffelProvider
import com.clawfare.search.GoogleFlightsProvider
import com.clawfare.search.GoogleFlightsStyle
import com.clawfare.search.HttpFetcher
import com.clawfare.search.HttpResult
import com.clawfare.search.SearchQuery
import com.clawfare.search.SearchService
import com.clawfare.search.TravelpayoutsProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderBehaviorTest {
    @Test
    fun serpApiExpandsTheReturnThenServesTheSecondSearchFromDisk() {
        val http = ScriptedFetcher()
        http.enqueue(200, outboundBody("DEP-1"))
        http.enqueue(200, returnBody())
        val cache = tempCache()
        val provider = GoogleFlightsProvider("serpapi", "SerpApi", "serp-secret-KEY123", GoogleFlightsStyle.SERPAPI, http)
        val query = query()
        val first = provider.search(query, cache, allowNetwork = true)
        assertEquals(2, http.calls.size)
        assertTrue(http.calls.all { it.contains("api_key=serp-secret-KEY123") })
        assertTrue(http.calls[1].contains("departure_token=DEP-1"))
        assertEquals(1, first.itineraries.size)
        assertEquals("live", first.itineraries[0].sourceLabel)
        assertTrue(first.itineraries[0].livePrice)
        assertEquals(
            "BA 211",
            first.itineraries[0]
                .outbound.legs[0]
                .flightNumber,
        )
        assertEquals(
            "BA 210",
            first.itineraries[0]
                .returning
                ?.legs
                ?.get(0)
                ?.flightNumber,
        )

        val second = provider.search(query, cache, allowNetwork = true)
        assertEquals(2, http.calls.size)
        assertEquals("cached", second.itineraries[0].sourceLabel)
        assertTrue(second.itineraries[0].livePrice)
        val cachedText =
            cache.root
                .walk()
                .filter { it.isFile }
                .joinToString("\n") { it.readText() }
        assertFalse(cachedText.contains("serp-secret-KEY123"))
    }

    @Test
    fun quotaReportsRemainingAndOutOfCreditWithoutAFlightSearch() {
        val serp = ScriptedFetcher()
        serp.enqueue(200, """{"plan_searches_left":240,"searches_per_month":250,"this_month_usage":10,"plan_name":"Free"}""")
        val serpQuota =
            GoogleFlightsProvider("serpapi", "SerpApi", "serp-secret-KEY123", GoogleFlightsStyle.SERPAPI, serp).quota()
        assertEquals(240, serpQuota.remaining)
        assertEquals(250, serpQuota.limit)
        assertTrue(serp.calls.single().contains("account.json"))
        assertFalse(serpQuota.note.orEmpty().contains("serp-secret-KEY123"))

        val searchApi = ScriptedFetcher()
        searchApi.enqueue(200, """{"credits_remaining":12,"monthly_credits":100,"credits_used":88}""")
        val searchQuota =
            GoogleFlightsProvider("searchapi", "SearchAPI", "search-secret", GoogleFlightsStyle.SEARCHAPI, searchApi).quota()
        assertEquals(12, searchQuota.remaining)

        val empty = ScriptedFetcher()
        empty.enqueue(429, """{"error":"Your account has run out of searches"}""")
        val blocked = GoogleFlightsProvider("serpapi", "SerpApi", "serp-secret-KEY123", GoogleFlightsStyle.SERPAPI, empty).quota()
        assertEquals(0, blocked.remaining)
        assertTrue(blocked.note.orEmpty().contains("Out of credits"))

        val failed = ScriptedFetcher()
        failed.enqueue(500, """{"error":"nope"}""")
        val broken = GoogleFlightsProvider("serpapi", "SerpApi", "serp-secret-KEY123", GoogleFlightsStyle.SERPAPI, failed).quota()
        assertTrue(broken.note.orEmpty().contains("500"))

        val throwing = ScriptedFetcher()
        throwing.failNext(RuntimeException("boom serp-secret-KEY123"))
        val errored = GoogleFlightsProvider("serpapi", "SerpApi", "serp-secret-KEY123", GoogleFlightsStyle.SERPAPI, throwing).quota()
        assertTrue(errored.note.orEmpty().contains("failed"))
        assertFalse(errored.note.orEmpty().contains("serp-secret-KEY123"))

        val unset = GoogleFlightsProvider("serpapi", "SerpApi", null, GoogleFlightsStyle.SERPAPI, ScriptedFetcher()).quota()
        assertFalse(unset.configured)
        assertTrue(unset.note.orEmpty().contains("SERPAPI_API_KEY"))

        val duffel = DuffelProvider("duffel_test_abc", ScriptedFetcher()).quota()
        assertTrue(duffel.note.orEmpty().contains("Mock"))
        val liveLooking = DuffelProvider("duffel_live_not_really", ScriptedFetcher()).quota()
        assertTrue(liveLooking.note.orEmpty().contains("not marked"))
        val noDuffel = DuffelProvider(null, ScriptedFetcher()).quota()
        assertFalse(noDuffel.configured)
    }

    @Test
    fun outOfCreditDoesNotCacheAndDoesNotThrow() {
        val http = ScriptedFetcher()
        http.enqueue(429, """{"error":"Your account has run out of searches"}""")
        val cache = tempCache()
        val provider = GoogleFlightsProvider("serpapi", "SerpApi", "serp-secret-KEY123", GoogleFlightsStyle.SERPAPI, http)
        val outcome = provider.search(query(), cache, allowNetwork = true)
        assertTrue(outcome.itineraries.isEmpty())
        assertTrue(outcome.warnings.any { it.contains("out of credits", ignoreCase = true) })
        assertFalse(outcome.warnings.joinToString().contains("serp-secret-KEY123"))
        assertTrue(cache.root.walk().none { it.isFile })
    }

    @Test
    fun duffelIsAlwaysMockAndTravelpayoutsIsCached() {
        val duffelCache = tempCache()
        val duffelHttp = ScriptedFetcher()
        duffelHttp.enqueue(200, duffelBody())
        val duffel = DuffelProvider("duffel_test_abcDEF123", duffelHttp)
        val duffelOut = duffel.search(query().copy(adults = 2), duffelCache, allowNetwork = true)
        assertEquals(1, duffelOut.itineraries.size)
        assertEquals("mock", duffelOut.itineraries[0].sourceLabel)
        assertFalse(duffelOut.itineraries[0].livePrice)
        assertEquals(100.0, duffelOut.itineraries[0].pricePerPerson)
        assertTrue(duffelHttp.calls.single().contains("api.duffel.com"))
        assertFalse(
            duffelCache.root
                .walk()
                .filter { it.isFile }
                .joinToString { it.readText() }
                .contains("duffel_test_abcDEF123"),
        )

        val tpCache = tempCache()
        val tpHttp = ScriptedFetcher()
        tpHttp.enqueue(200, travelpayoutsBody())
        val tp = TravelpayoutsProvider("tp-token-SECRET99", tpHttp)
        val tpOut = tp.search(query(), tpCache, allowNetwork = true)
        assertEquals(1, tpOut.itineraries.size)
        assertEquals("cached", tpOut.itineraries[0].sourceLabel)
        assertFalse(tpOut.itineraries[0].livePrice)
        assertTrue(tpHttp.calls.single().contains("token=tp-token-SECRET99"))
        val stored =
            tpCache.root
                .walk()
                .filter { it.isFile }
                .joinToString { it.readText() }
        assertFalse(stored.contains("tp-token-SECRET99"))
    }

    @Test
    fun estimateCountsTheGridAndSkipsProvidersWithNoKey() {
        val dir = tempCache()
        val provider = GoogleFlightsProvider("serpapi", "SerpApi", null, GoogleFlightsStyle.SERPAPI, ScriptedFetcher())
        val service = SearchService(listOf(provider), dir)
        val wide =
            SearchQuery(
                origins = listOf("LHR", "LGW"),
                destinations = listOf("TPA", "SRQ"),
                outboundFrom = "2026-12-19",
                outboundTo = "2026-12-20",
                nights = 8,
                providers = listOf("serpapi"),
            )
        val est = service.estimate(wide)
        assertEquals(0, est.liveCalls)
        assertEquals(8, est.skippedCalls)
        assertEquals(24, est.returnLookupsUpperBound)

        val spec = provider.baseCalls(wide).first()
        dir.put(provider.id, spec.params, """{"best_flights":[]}""")
        val withCache = service.estimate(wide)
        assertEquals(1, withCache.cachedCalls)
        assertEquals(7, withCache.skippedCalls)

        val cacheOnly = service.estimate(wide.copy(cacheOnly = true))
        assertEquals(0, cacheOnly.returnLookupsUpperBound)
        assertEquals(0, cacheOnly.liveCalls)

        val blocked = service.search(wide.copy(confirmed = false))
        assertFalse(blocked.ok)
        assertTrue(blocked.needsConfirmation || blocked.result == null)

        val huge =
            service.estimate(
                SearchQuery(
                    origins = listOf("LHR"),
                    destinations = listOf("TPA"),
                    outboundFrom = "2026-12-01",
                    outboundTo = "2026-12-30",
                    returnFrom = "2027-01-01",
                    returnTo = "2027-01-20",
                    providers = listOf("serpapi"),
                ),
            )
        assertTrue(huge.warnings.any { it.contains("500") || it.contains("Narrow") })
    }
}

private fun query() =
    SearchQuery(
        origins = listOf("LHR"),
        destinations = listOf("TPA"),
        outboundFrom = "2026-12-19",
        outboundTo = "2026-12-19",
        nights = 8,
        adults = 1,
        cabin = "premium_economy",
        providers = listOf("serpapi"),
        returnChoices = 3,
    )

private fun tempCache(): DiskCache {
    val dir =
        File.createTempFile("clawfare-cache", "").apply {
            delete()
            mkdirs()
        }
    return DiskCache(dir)
}

private class ScriptedFetcher : HttpFetcher {
    val calls = mutableListOf<String>()
    private val queue = ArrayDeque<HttpResult>()
    private var failure: RuntimeException? = null

    fun enqueue(
        status: Int,
        body: String,
    ) {
        queue.add(HttpResult(status, body))
    }

    fun failNext(error: RuntimeException) {
        failure = error
    }

    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResult {
        calls += url
        failure?.let {
            failure = null
            throw it
        }
        return queue.removeFirstOrNull() ?: HttpResult(500, """{"error":"no scripted response"}""")
    }
}

private fun flight(
    number: String,
    from: String,
    to: String,
    date: String,
    depart: String,
    arrive: String,
) = """
    {"departure_airport":{"id":"$from","time":"$date $depart"},"arrival_airport":{"id":"$to","time":"$date $arrive"},"duration":120,"airline":"British Airways","flight_number":"$number","travel_class":"Premium economy"}
    """.trimIndent()

private fun outboundBody(token: String) =
    """
    {"best_flights":[{"price":2800,"total_duration":600,"departure_token":"$token","flights":[${flight(
        "BA 211",
        "LHR",
        "TPA",
        "2026-12-19",
        "13:00",
        "17:45",
    )}]}]}
    """.trimIndent()

private fun returnBody() =
    """
    {"best_flights":[{"price":2833,"total_duration":560,"booking_token":"book-1","flights":[${flight(
        "BA 210",
        "TPA",
        "LHR",
        "2026-12-27",
        "20:10",
        "09:30",
    )}]}]}
    """.trimIndent()

private fun duffelBody() =
    """
    {"data":{"offers":[{"id":"off_1","total_amount":"200.00","total_currency":"GBP","slices":[
      {"segments":[{"origin":{"iata_code":"LHR"},"destination":{"iata_code":"TPA"},"departing_at":"2026-12-19T13:00:00","arriving_at":"2026-12-19T17:45:00","duration":"PT9H45M","marketing_carrier":{"iata_code":"BA","name":"British Airways"},"operating_carrier":{"iata_code":"BA","name":"British Airways"},"marketing_carrier_flight_number":"211","passengers":[{"cabin_class":"premium_economy"}]}]},
      {"segments":[{"origin":{"iata_code":"TPA"},"destination":{"iata_code":"LHR"},"departing_at":"2026-12-27T20:10:00","arriving_at":"2026-12-28T09:30:00","duration":"PT8H20M","marketing_carrier":{"iata_code":"BA","name":"British Airways"},"operating_carrier":{"iata_code":"BA","name":"British Airways"},"marketing_carrier_flight_number":"210","passengers":[{"cabin_class":"premium_economy"}]}]}
    ]}]}}
    """.trimIndent()

private fun travelpayoutsBody() =
    """
    {"success":true,"currency":"GBP","data":[{"origin":"LHR","destination":"TPA","departure_at":"2026-12-19T13:00:00Z","return_at":"2026-12-27T20:10:00Z","price":400,"airline":"BA","flight_number":"211","transfers":0,"return_transfers":0}]}
    """.trimIndent()
