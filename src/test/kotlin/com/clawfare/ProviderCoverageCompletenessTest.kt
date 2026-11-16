package com.clawfare

import com.clawfare.search.BatchConfig
import com.clawfare.search.BatchScenario
import com.clawfare.search.BatchSweep
import com.clawfare.search.DiskCache
import com.clawfare.search.FareProvider
import com.clawfare.search.GoogleFlightsProvider
import com.clawfare.search.GoogleFlightsStyle
import com.clawfare.search.HttpFetcher
import com.clawfare.search.HttpResult
import com.clawfare.search.NoopSink
import com.clawfare.search.QuotaView
import com.clawfare.search.SearchQuery
import com.clawfare.search.SearchService
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderCoverageCompletenessTest {
    private class ScriptedHttp(
        vararg initial: HttpResult,
    ) : HttpFetcher {
        val queue = ArrayDeque(initial.toList())
        var calls = 0

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
        ): HttpResult {
            calls++
            return queue.removeFirst()
        }
    }

    private fun root(): File =
        File.createTempFile("coverage-completeness", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }

    private fun query() =
        SearchQuery(
            origins = listOf("LHR"),
            destinations = listOf("HND"),
            outboundFrom = "2028-06-01",
            outboundTo = "2028-06-01",
            tripType = "one_way",
            cabin = "business",
            adults = 2,
            providers = listOf("serpapi"),
            excludedAirlines = emptyList(),
            confirmed = true,
        )

    private fun service(
        http: ScriptedHttp,
        cache: DiskCache,
    ): SearchService {
        val underlying = GoogleFlightsProvider("serpapi", "Fixture", "test-key", GoogleFlightsStyle.SERPAPI, http)
        val provider =
            object : FareProvider by underlying {
                override fun quota(): QuotaView = QuotaView("serpapi", configured = true, remaining = 100)
            }
        return SearchService(listOf(provider), cache, NoopSink)
    }

    @Test
    fun offlineCacheMissIsNotAZeroFareAvailabilityResult() {
        val http = ScriptedHttp()
        val response = service(http, DiskCache(root())).search(query().copy(cacheOnly = true, confirmed = false))
        assertTrue(response.ok)
        val observed = response.providerObservations.single()
        assertEquals("not_cached", observed.state)
        assertEquals(1, observed.baseCallsExpected)
        assertEquals(0, observed.baseCallsLoaded)
        assertEquals(0, observed.returned)
        assertEquals(0, http.calls)
    }

    @Test
    fun errorAndMalformedPayloadCannotBeReportedAsSoldOut() {
        val http =
            ScriptedHttp(
                HttpResult(429, """{"error":"Out of searches"}"""),
                HttpResult(200, """{"success":true}"""),
                HttpResult(200, """{"best_flights":[],"other_flights":[]}"""),
            )
        val cache = DiskCache(root())
        val network = service(http, cache)
        val q = query()
        val failed = network.search(q).providerObservations.single()
        assertEquals("failed", failed.state)
        assertEquals(1, failed.baseCallsExpected)
        assertEquals(0, failed.baseCallsLoaded)
        val malformed = network.search(q).providerObservations.single()
        assertEquals("partial", malformed.state)
        assertEquals(1, malformed.malformedResponses)
        assertEquals(1, malformed.baseCallsLoaded)
        val cacheHit = network.search(q).providerObservations.single()
        assertEquals("partial", cacheHit.state)
        assertEquals(1, cacheHit.malformedResponses)
        assertEquals(2, http.calls)
        // An HTTP 200 unrecognized payload was cached, so a fresh cache is used
        // for a separate explicitly empty valid response.
        val valid = service(http, DiskCache(root())).search(q).providerObservations.single()
        assertEquals("completed", valid.state)
        assertEquals(0, valid.returned)
        assertEquals(1, valid.baseCallsLoaded)
        assertEquals(3, http.calls)
    }

    @Test
    fun oneSuccessfulBaseAndOneRateLimitIsPartialNotZeroReturned() {
        val http =
            ScriptedHttp(
                HttpResult(200, """{"best_flights":[]}"""),
                HttpResult(429, """{"error":"Out of searches"}"""),
                HttpResult(200, """{"best_flights":[]}"""),
            )
        val cache = DiskCache(root())
        val network = service(http, cache)
        val matrix =
            BatchConfig(
                name = "coverage-2028",
                scenarios =
                    listOf(
                        BatchScenario(
                            "dates",
                            query().copy(
                                outboundTo = "2028-06-02",
                                confirmed = false,
                            ),
                        ),
                    ),
                maxLiveCalls = 2,
            )
        val journal = root().resolve("audit.ndjson")
        val initial = BatchSweep.coverage(matrix, journal)
        assertEquals(listOf("not_searched", "not_searched"), initial.map { it.status })
        val first = BatchSweep.run(matrix, network, confirmed = true, auditFile = journal)
        assertEquals(2, first.executed)
        assertEquals(2, http.calls)
        val observed = BatchSweep.coverage(matrix, journal)
        assertEquals(listOf("zero_returned", "provider_failed"), observed.map { it.status })
        assertEquals(listOf(1, 1), observed.map { it.baseCallsExpected })
        assertEquals(listOf(1, 0), observed.map { it.baseCallsLoaded })
        val resumed = BatchSweep.run(matrix, network, confirmed = true, auditFile = journal, resume = true)
        assertEquals(1, resumed.resumedSkipped)
        assertEquals(1, resumed.executed)
        assertEquals(3, http.calls)
        assertEquals(listOf("zero_returned", "zero_returned"), BatchSweep.coverage(matrix, journal).map { it.status })
    }

    @Test
    fun missingReturnTokenIsUnresolvedNotACompletedRoundTrip() {
        val body =
            """
            {
              "best_flights": [{
                "price": 4200,
                "flights": [{
                  "flight_number": "BA 1",
                  "airline": "British Airways",
                  "travel_class": "Business",
                  "departure_airport": {"id": "LHR", "time": "2028-06-01 10:00"},
                  "arrival_airport": {"id": "HND", "time": "2028-06-02 08:00"}
                }]
              }]
            }
            """.trimIndent()
        val http = ScriptedHttp(HttpResult(200, body))
        val q =
            query().copy(
                tripType = "round_trip",
                returnFrom = "2028-06-10",
                returnTo = "2028-06-10",
                returnChoices = 1,
            )
        val result = service(http, DiskCache(root())).search(q)
        assertTrue(result.ok)
        val returnedTicket = result.result!!.itineraries.single()
        assertTrue(returnedTicket.returnMissing)
        val observation = result.providerObservations.single()
        assertEquals("partial", observation.state)
        assertEquals(1, observation.unresolvedReturns)
        assertEquals(0, observation.returnLookupsExpected)
        assertFalse(result.providerObservations.any { it.state == "completed" })
        assertEquals(1, http.calls)
    }
}
