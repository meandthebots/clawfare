package com.clawfare

import com.clawfare.search.DiskCache
import com.clawfare.search.GoogleFlightsProvider
import com.clawfare.search.GoogleFlightsStyle
import com.clawfare.search.HttpFetcher
import com.clawfare.search.HttpResult
import com.clawfare.search.MeteredProviderHttp
import com.clawfare.search.ProviderHttpBudget
import com.clawfare.search.SearchQuery
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderHttpBudgetTest {
    private fun tempDir(): File =
        File.createTempFile("provider-meter", "").apply {
            delete()
            mkdirs()
        }

    private fun at(stamp: String): Clock = Clock.fixed(Instant.parse(stamp), ZoneOffset.UTC)

    @Test
    fun reserveBeforeDispatchAndCrashConsumesTheRemainingAllowance() {
        val root = tempDir()
        val clock = at("2026-11-16T02:00:00Z")
        val budget = ProviderHttpBudget(root, mapOf("serpapi" to 1), clock = clock)
        val reservation = budget.reserve("serpapi", "base_search")
        assertEquals(1, budget.usage("serpapi").unfinished)
        assertFailsWith<IllegalArgumentException> { budget.reserve("serpapi", "base_search") }
        assertEquals(1, budget.usage("serpapi").reservedToday)
        assertEquals(1, ProviderHttpBudget(root, mapOf("serpapi" to 1), clock = clock).usage("serpapi").unfinished)
        budget.complete(reservation, "serpapi", "base_search", 200, "response")
        assertEquals(0, budget.usage("serpapi").unfinished)
        assertFailsWith<IllegalArgumentException> { budget.reserve("serpapi", "base_search") }
        val rows = root.resolve("provider-http-budget.ndjson").readText()
        assertFalse(rows.contains("booking_token"))
        assertFalse(rows.contains("api_key"))
    }

    @Test
    fun twoBudgetInstancesCompeteOnOneDurableCap() {
        val root = tempDir()
        val clock = at("2026-11-16T02:00:00Z")
        val results = Collections.synchronizedList(mutableListOf<Boolean>())
        val threads =
            (1..12).map { n ->
                thread(start = true) {
                    val budget = ProviderHttpBudget(root, mapOf("searchapi" to 3), clock = clock)
                    results += runCatching { budget.reserve("searchapi", "booking_options") }.isSuccess
                }
            }
        threads.forEach { it.join() }
        assertEquals(3, results.count { it })
        assertEquals(3, ProviderHttpBudget(root, mapOf("searchapi" to 3), clock = clock).usage("searchapi").unfinished)
    }

    @Test
    fun http429TimeoutAndRetryAreEachConservativelyReserved() {
        var dispatched = 0
        val fake =
            object : HttpFetcher {
                override fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    body: String?,
                ): HttpResult {
                    dispatched++
                    if (dispatched == 2) throw java.io.IOException("synthetic timeout")
                    return HttpResult(if (dispatched == 1) 429 else 200, "{}")
                }
            }
        val root = tempDir()
        val meter =
            MeteredProviderHttp(
                fake,
                ProviderHttpBudget(root, mapOf("serpapi" to 3), clock = at("2026-11-16T02:00:00Z")),
            )
        val query = "https://serpapi.com/search.json?engine=google_flights&booking_token=PRIVATE"
        assertEquals(429, meter.request("GET", query, emptyMap(), null).status)
        assertFailsWith<java.io.IOException> { meter.request("GET", query, emptyMap(), null) }
        assertEquals(200, meter.request("GET", query, emptyMap(), null).status)
        assertFailsWith<IllegalArgumentException> { meter.request("GET", query, emptyMap(), null) }
        assertEquals(3, dispatched)
        val saved = root.resolve("provider-http-budget.ndjson").readText()
        assertFalse(saved.contains("PRIVATE"))
        assertFalse(saved.contains("booking_token"))
        assertTrue(saved.contains("rate_limited"))
        assertTrue(saved.contains("transport_error"))
    }

    @Test
    fun missingCapsCorruptJournalUnknownPathsAndBookingPostsFailClosed() {
        val root = tempDir()
        val never =
            object : HttpFetcher {
                override fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    body: String?,
                ): HttpResult = error("Must not dispatch")
            }
        val meter = MeteredProviderHttp(never, ProviderHttpBudget(root, emptyMap()))
        assertFailsWith<IllegalStateException> {
            meter.request("GET", "https://serpapi.com/search.json?engine=google_flights", emptyMap(), null)
        }
        assertFailsWith<IllegalStateException> {
            meter.request("POST", "https://api.duffel.com/air/orders", emptyMap(), "{}")
        }
        val withCap =
            MeteredProviderHttp(
                never,
                ProviderHttpBudget(root, mapOf("duffel" to 2)),
            )
        root.resolve("provider-http-budget.ndjson").writeText("{truncated")
        assertFailsWith<Exception> {
            withCap.request("POST", "https://api.duffel.com/air/offer_requests?return_offers=true", emptyMap(), "{}")
        }
    }

    @Test
    fun monthCapDoesNotResetAtDayBoundary() {
        val root = tempDir()
        val day1 = ProviderHttpBudget(root, mapOf("duffel" to 3), mapOf("duffel" to 1), at("2026-11-16T23:59:00Z"))
        day1.reserve("duffel", "exact_offer")
        val day2 = ProviderHttpBudget(root, mapOf("duffel" to 3), mapOf("duffel" to 1), at("2026-11-17T00:01:00Z"))
        assertEquals(0, day2.usage("duffel").reservedToday)
        assertEquals(1, day2.usage("duffel").reservedThisMonth)
        assertFailsWith<IllegalArgumentException> { day2.reserve("duffel", "exact_offer") }
    }

    @Test
    fun cachedFareRequestsNeverCrossMeteredHttpBoundary() {
        val root = tempDir()
        var requests = 0
        val fake =
            object : HttpFetcher {
                override fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    body: String?,
                ): HttpResult {
                    requests++
                    return HttpResult(
                        200,
                        """
                        {"best_flights":[{"price":4200,
                          "flights":[{"departure_airport":{"id":"LHR","time":"2028-05-21 09:00"},
                          "arrival_airport":{"id":"HND","time":"2028-05-22 08:00"},
                          "flight_number":"BA 5","airline":"British Airways","travel_class":"Business"}]}]}
                        """.trimIndent(),
                    )
                }
            }
        val ledger = ProviderHttpBudget(root, mapOf("serpapi" to 1), clock = at("2026-11-16T02:00:00Z"))
        val provider = GoogleFlightsProvider("serpapi", "fixture", "test-token", GoogleFlightsStyle.SERPAPI, MeteredProviderHttp(fake, ledger))
        val query =
            SearchQuery(
                origins = listOf("LHR"),
                destinations = listOf("HND"),
                outboundFrom = "2028-05-21",
                tripType = "one_way",
                adults = 2,
                cabin = "business",
                providers = listOf("serpapi"),
            )
        val cache = DiskCache(root.resolve("cache"))
        val live = provider.search(query, cache, true)
        val cached = provider.search(query, cache, true)
        assertEquals(1, requests)
        assertEquals(1, live.liveCalls)
        assertEquals(1, cached.cachedCalls)
        assertEquals(1, ledger.usage("serpapi").reservedToday)
    }
}
