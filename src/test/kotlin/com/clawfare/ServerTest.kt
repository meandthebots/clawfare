package com.clawfare

import com.clawfare.db.ClawfareDatabase
import com.clawfare.db.InvestigationQueries
import com.clawfare.web.clawfareModule
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerTest {
    @Test
    fun sampleAndStatusStayOnTheServer() {
        ClawfareDatabase.connectInMemory()
        testApplication {
            application { clawfareModule() }
            val sample = client.get("/api/sample")
            assertEquals(HttpStatusCode.OK, sample.status)
            val body = sample.bodyAsText()
            assertTrue(body.contains("BA 211"))
            assertTrue(body.contains("3341"))
            assertFalse(body.contains("api_key"))
            assertFalse(body.contains("SERPAPI"))

            val status = client.get("/api/status")
            assertEquals(HttpStatusCode.OK, status.status)
            val statusBody = status.bodyAsText()
            assertTrue(statusBody.contains("serpapi"))
            assertTrue(statusBody.contains("duffel"))
            assertFalse(statusBody.contains("api_key="))

            val page = client.get("/")
            assertEquals(HttpStatusCode.OK, page.status)
            assertTrue(page.bodyAsText().contains("window.__SNAPSHOT__ = null"))

            val snapshot = client.get("/api/sample/export.html")
            assertEquals(HttpStatusCode.OK, snapshot.status)
            val html = snapshot.bodyAsText()
            assertTrue(html.contains("BA 211"))
            assertTrue(html.contains("BudgetAir"))
            assertFalse(html.contains("window.__SNAPSHOT__ = null"))
            assertFalse(html.contains("api_key"))

            val searches = client.get("/api/searches")
            assertEquals(HttpStatusCode.OK, searches.status)
            assertTrue(searches.bodyAsText().contains("florida-dec-2026"))

            val result = client.get("/api/results/florida-dec-2026")
            assertEquals(HttpStatusCode.OK, result.status)
            assertTrue(result.bodyAsText().contains("\"id\":\"11\"") || result.bodyAsText().contains("BA 211"))

            val missing = client.get("/api/results/no-such-trip")
            assertEquals(HttpStatusCode.NotFound, missing.status)

            val favicon = client.get("/favicon.ico")
            assertEquals(HttpStatusCode.NoContent, favicon.status)

            val quota = client.get("/api/quota")
            assertEquals(HttpStatusCode.OK, quota.status)
            assertTrue(quota.bodyAsText().contains("serpapi"))
            assertFalse(quota.bodyAsText().contains("api_key"))

            val estimate =
                client.post("/api/estimate") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"origins":["LHR"],"destinations":["TPA"],"outboundFrom":"2026-12-19","outboundTo":"2026-12-19","nights":8,"providers":["serpapi"],"cacheOnly":true}""",
                    )
                }
            assertEquals(HttpStatusCode.OK, estimate.status)
            assertTrue(estimate.bodyAsText().contains("liveCalls") || estimate.bodyAsText().contains("warnings"))

            val search =
                client.post("/api/search") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"origins":["LHR"],"destinations":["TPA"],"outboundFrom":"2026-12-19","outboundTo":"2026-12-19","nights":8,"providers":["serpapi"],"cacheOnly":true,"confirmed":true,"investigation":"cache-only"}""",
                    )
                }
            assertEquals(HttpStatusCode.OK, search.status)
            assertTrue(search.bodyAsText().contains("\"ok\":true"))

            val sellers =
                client.post("/api/results/florida-dec-2026/vendors") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"itineraryId":"H","markets":["uk"],"cacheOnly":true}""")
                }
            assertEquals(HttpStatusCode.OK, sellers.status)
            assertTrue(sellers.bodyAsText().contains("BudgetAir"))

            val tracked =
                client.post("/api/results/florida-dec-2026/track") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"itineraryId":"11","tripSlug":"tracked-ba"}""")
                }
            assertEquals(HttpStatusCode.OK, tracked.status)
            assertTrue(tracked.bodyAsText().contains("tracked-ba"))

            val badTrack =
                client.post("/api/results/florida-dec-2026/track") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"itineraryId":"missing","tripSlug":"nope"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, badTrack.status)

            val csv =
                client.post("/api/export/csv") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"result":{"id":"florida-dec-2026","title":"Florida"},"kind":"itineraries"}""")
                }
            assertEquals(HttpStatusCode.OK, csv.status)
        }
        assertTrue(InvestigationQueries.getBySlug("florida-dec-2026") != null)
        assertTrue(InvestigationQueries.getBySlug("tracked-ba") != null)
    }
}
