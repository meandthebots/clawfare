package com.clawfare

import com.clawfare.cli.ClawfareCommand
import com.clawfare.db.ClawfareDatabase
import com.clawfare.search.AppJson
import com.clawfare.search.BatchConfig
import com.clawfare.search.BatchScenario
import com.clawfare.search.BatchSweep
import com.clawfare.search.DiskCache
import com.clawfare.search.Export
import com.clawfare.search.FareStore
import com.clawfare.search.FareWatch
import com.clawfare.search.FilterSpec
import com.clawfare.search.GoogleFlightsProvider
import com.clawfare.search.GoogleFlightsStyle
import com.clawfare.search.HttpFetcher
import com.clawfare.search.HttpResult
import com.clawfare.search.SearchQuery
import com.clawfare.search.SearchService
import com.clawfare.web.Server
import com.clawfare.web.clawfareModule
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hermetic end-to-end search, replay, report, watch and web journey.
 * All routes, prices, dates and the deliberately fake booking token are synthetic.
 */
class OfflineEndToEndSmokeTest {
    private val fixtureBody =
        """
        {"best_flights":[{"price":4200,"booking_token":"SYNTHETIC_DO_NOT_BOOK",
         "flights":[{"departure_airport":{"id":"LHR","time":"2028-06-01 12:00"},
         "arrival_airport":{"id":"HND","time":"2028-06-02 08:00"},"duration":740,
         "airline":"British Airways","flight_number":"BA 5","travel_class":"Business"}]}]}
        """.trimIndent()

    @Test
    fun offlineFixtureCanPlanSearchReplayReportWatchAndOpenBrowser() {
        ClawfareDatabase.connectInMemory()
        val directory =
            File.createTempFile("clawfare-e2e", "").apply {
                delete()
                mkdirs()
            }
        var actualHttpCalls = 0
        val fakeHttp =
            object : HttpFetcher {
                override fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    body: String?,
                ): HttpResult {
                    assertEquals("GET", method)
                    assertEquals(null, body)
                    assertTrue(url.startsWith("https://serpapi.com/search.json?"))
                    // Any second request indicates cache replay failed and
                    // must not escape to the actual provider in CI.
                    actualHttpCalls++
                    assertEquals(1, actualHttpCalls)
                    return HttpResult(200, fixtureBody)
                }
            }
        val provider =
            GoogleFlightsProvider(
                "serpapi",
                "Synthetic provider",
                "synthetic-not-a-credential",
                GoogleFlightsStyle.SERPAPI,
                fakeHttp,
            )
        val service = SearchService(listOf(provider), DiskCache(directory.resolve("cache")), FareStore)
        val query =
            SearchQuery(
                origins = listOf("LHR"),
                destinations = listOf("HND"),
                outboundFrom = "2028-06-01",
                tripType = "one_way",
                adults = 2,
                cabin = "business",
                checkedBags = 0,
                providers = listOf("serpapi"),
                investigation = "synthetic-e2e",
            )

        // Quota is never spent while preparing the user-visible estimate.
        val estimate = service.estimate(query, includeQuota = false)
        assertEquals(1, estimate.liveCalls)
        assertEquals(0, actualHttpCalls)
        val requiresConsent = service.search(query.copy(confirmed = false))
        assertTrue(requiresConsent.needsConfirmation)
        assertEquals(0, actualHttpCalls)

        val fetched = service.search(query.copy(confirmed = true))
        assertTrue(fetched.ok, fetched.error.orEmpty())
        assertEquals(1, actualHttpCalls)
        val saved = assertNotNull(FareStore.project("synthetic-e2e"))
        assertEquals(1, saved.itineraries.size)
        assertEquals(2, saved.passengers)
        val itinerary = saved.itineraries.single()
        assertTrue(itinerary.airlineSummary.contains("British Airways"))
        assertFalse(itinerary.cashRefundProofVerified)
        assertFalse(Export.itineraryCsv(saved, FilterSpec()).contains("SYNTHETIC_DO_NOT_BOOK"))
        val offlineHtml = Export.html(Server.page(), saved)
        assertTrue(offlineHtml.contains("synthetic-e2e"))
        assertFalse(offlineHtml.contains("SYNTHETIC_DO_NOT_BOOK"))
        assertFalse(offlineHtml.contains("synthetic-not-a-credential"))

        val replay = service.search(query.copy(confirmed = true, cacheOnly = true, investigation = "synthetic-replay"))
        assertTrue(replay.ok, replay.error.orEmpty())
        assertEquals(1, actualHttpCalls)
        assertTrue(replay.result!!.itineraries.isNotEmpty())
        assertTrue(replay.result!!.itineraries.all { it.sourceLabel == "cached" })
        assertFalse(replay.result!!.itineraries.any { it.livePrice })

        val config =
            BatchConfig(
                name = "synthetic-e2e",
                scenarios =
                    listOf(
                        BatchScenario(
                            id = "smoke",
                            query = query.copy(cacheOnly = true, confirmed = true),
                            positioningGbp = 0.0,
                            hotelsGbp = 0.0,
                            groundTransfersGbp = 0.0,
                            baggageGbp = 0.0,
                        ),
                    ),
                maxLiveCalls = 0,
                maxDailyLiveCalls = 0,
            )
        val planned =
            BatchSweep
                .estimate(config, service)
        assertEquals(0, planned.upperBound)
        assertEquals(1, planned.entries.size)

        val audit = directory.resolve("attempts.ndjson")
        val completed = BatchSweep.run(config, service, confirmed = true, auditFile = audit)
        assertTrue(completed.failures.isEmpty())
        assertEquals(1, completed.executed)
        assertEquals(1, actualHttpCalls)
        assertEquals("cache_only", BatchSweep.coverage(config, audit).single().status)
        val ranked = BatchSweep.compare(config, FareStore)
        assertTrue(ranked.isNotEmpty())
        assertEquals(4200.0, ranked.first().partyFareGbp)

        val configFile = directory.resolve("synthetic-config.json")
        configFile.writeText(AppJson.encodeToString(BatchConfig.serializer(), config))
        val csv = directory.resolve("comparison.csv")
        assertEquals(
            0,
            CommandLine(ClawfareCommand()).execute(
                "fare",
                "batch",
                "report",
                configFile.absolutePath,
                "--out",
                csv.absolutePath,
            ),
        )
        assertTrue(csv.readText().contains("4200"))
        assertEquals(1, actualHttpCalls)

        val watch =
            FareWatch.runOnce(
                config,
                service,
                FareStore,
                directory.resolve("watch.lock"),
                audit,
                directory.resolve("alerts.ndjson"),
                thresholdGbp = 100.0,
                confirmed = true,
            )
        assertTrue(watch.batch.failures.isEmpty())
        assertTrue(watch.alerts.isEmpty())
        assertEquals(1, actualHttpCalls)

        testApplication {
            application { clawfareModule() }
            val result = client.get("/api/results/synthetic-e2e")
            assertEquals(HttpStatusCode.OK, result.status)
            assertTrue(result.bodyAsText().contains("synthetic-e2e"))
            assertFalse(result.bodyAsText().contains("SYNTHETIC_DO_NOT_BOOK"))
            val report = client.get("/api/sample/export.html")
            assertEquals(HttpStatusCode.OK, report.status)
            assertFalse(report.bodyAsText().contains("synthetic-not-a-credential"))
        }
    }
}
