package com.clawfare

import com.clawfare.db.ClawfareDatabase
import com.clawfare.db.InvestigationQueries
import com.clawfare.web.clawfareModule
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
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
            val sample = client.get("/api/sample") { header("Host", "127.0.0.1:8080") }
            assertEquals(HttpStatusCode.OK, sample.status)
            val body = sample.bodyAsText()
            assertTrue(body.contains("BA 211"))
            assertTrue(body.contains("3341"))
            assertFalse(body.contains("api_key"))
            assertFalse(body.contains("SERPAPI"))

            val status = client.get("/api/status") { header("Host", "127.0.0.1:8080") }
            assertEquals(HttpStatusCode.OK, status.status)
            val statusBody = status.bodyAsText()
            assertTrue(statusBody.contains("serpapi"))
            assertTrue(statusBody.contains("duffel"))
            assertFalse(statusBody.contains("api_key="))

            val page = client.get("/") { header("Host", "127.0.0.1:8080") }
            assertEquals(HttpStatusCode.OK, page.status)
            assertTrue(page.bodyAsText().contains("window.__SNAPSHOT__ = null"))

            val snapshot = client.get("/api/sample/export.html") { header("Host", "127.0.0.1:8080") }
            assertEquals(HttpStatusCode.OK, snapshot.status)
            val html = snapshot.bodyAsText()
            assertTrue(html.contains("BA 211"))
            assertTrue(html.contains("BudgetAir"))
            assertFalse(html.contains("window.__SNAPSHOT__ = null"))
            assertFalse(html.contains("api_key"))

            val searches = client.get("/api/searches") { header("Host", "127.0.0.1:8080") }
            assertEquals(HttpStatusCode.OK, searches.status)
            assertTrue(searches.bodyAsText().contains("florida-dec-2026"))

            val result = client.get("/api/results/florida-dec-2026") { header("Host", "127.0.0.1:8080") }
            assertEquals(HttpStatusCode.OK, result.status)
            assertTrue(result.bodyAsText().contains("\"id\":\"11\"") || result.bodyAsText().contains("BA 211"))

            val missing = client.get("/api/results/no-such-trip") { header("Host", "127.0.0.1:8080") }
            assertEquals(HttpStatusCode.NotFound, missing.status)

            val favicon = client.get("/favicon.ico") { header("Host", "127.0.0.1:8080") }
            assertEquals(HttpStatusCode.NoContent, favicon.status)

            val quota = client.get("/api/quota") { header("Host", "127.0.0.1:8080") }
            assertEquals(HttpStatusCode.OK, quota.status)
            assertTrue(quota.bodyAsText().contains("serpapi"))
            assertFalse(quota.bodyAsText().contains("api_key"))

            // DNS rebinding is a read-path threat too: a third-party page
            // could otherwise access our API under an attacker-owned hostname.
            // Supplying a trusted X-Forwarded-Host must not override the Host gate.
            val readPaths =
                listOf(
                    "/",
                    "/api/status",
                    "/api/quota",
                    "/api/sample",
                    "/api/searches",
                    "/api/results/florida-dec-2026",
                    "/api/sample/export.html",
                    "/api/session",
                    "/favicon.ico",
                )
            for (path in readPaths) {
                for (host in listOf("attacker.example:8080", "127.0.0.1.attacker.example:8080")) {
                    val rebound =
                        client.get(path) {
                            header("Host", host)
                            header("X-Forwarded-Host", "127.0.0.1:8080")
                        }
                    assertEquals(HttpStatusCode.Forbidden, rebound.status, "Host $host must not read $path")
                    assertTrue(rebound.bodyAsText().contains("Invalid local host."))
                }
            }

            val session =
                client.get("/api/session") {
                    header("Host", "127.0.0.1:8080")
                }
            assertEquals(HttpStatusCode.OK, session.status)
            val sessionToken =
                Regex(""""token":"([^"]+)"""").find(session.bodyAsText())?.groupValues?.get(1)
                    ?: error("No local session token")

            // Body enforcement must inspect actual bytes, not just the optional
            // Content-Length header. The query cap is lower than the export cap.
            val oversizedJson = """{"opaque":"${"x".repeat(70 * 1024)}"}"""
            val protectedRoutes =
                listOf(
                    "/api/estimate",
                    "/api/search",
                    "/api/results/florida-dec-2026/vendors",
                    "/api/results/florida-dec-2026/track",
                )
            for (path in protectedRoutes) {
                val oversized =
                    client.post(path) {
                        header("Host", "127.0.0.1:8080")
                        header("X-Clawfare-Session", sessionToken)
                        contentType(ContentType.Application.Json)
                        setBody(oversizedJson)
                    }
                assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status, "JSON cap on $path")
                assertTrue(oversized.bodyAsText().contains("Request body too large."))
            }

            // A streaming request with no declared content length must also
            // hit the byte-counting guard rather than reaching JSON decoding.
            val streamed =
                client.post("/api/estimate") {
                    header("Host", "127.0.0.1:8080")
                    header("X-Clawfare-Session", sessionToken)
                    setBody(
                        object : OutgoingContent.ReadChannelContent() {
                            override val contentType = ContentType.Application.Json

                            override fun readFrom(): ByteReadChannel = ByteReadChannel(oversizedJson.toByteArray())
                        },
                    )
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, streamed.status)
            assertTrue(streamed.bodyAsText().contains("Request body too large."))

            val malformed =
                client.post("/api/estimate") {
                    header("Host", "127.0.0.1:8080")
                    header("X-Clawfare-Session", sessionToken)
                    contentType(ContentType.Application.Json)
                    setBody("""{"secret":"confidential-test-string","broken":}""")
                }
            assertEquals(HttpStatusCode.BadRequest, malformed.status)
            assertEquals("""{"error":"Invalid JSON request body."}""", malformed.bodyAsText())
            assertFalse(malformed.bodyAsText().contains("confidential-test-string"))

            val noToken =
                client.post("/api/search") {
                    header("Host", "127.0.0.1:8080")
                    contentType(ContentType.Application.Json)
                    setBody("""{"cacheOnly":true}""")
                }
            assertEquals(HttpStatusCode.Forbidden, noToken.status)

            val wrongOrigin =
                client.post("/api/results/florida-dec-2026/track") {
                    header("Host", "127.0.0.1:8080")
                    contentType(ContentType.Application.Json)
                    header("X-Clawfare-Session", sessionToken)
                    header("Origin", "https://outside.example")
                    setBody("""{"itineraryId":"11","tripSlug":"should-not-write"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, wrongOrigin.status)

            val estimate =
                client.post("/api/estimate") {
                    header("Host", "127.0.0.1:8080")
                    contentType(ContentType.Application.Json)
                    header("X-Clawfare-Session", sessionToken)
                    setBody(
                        """{"origins":["LHR"],"destinations":["TPA"],"outboundFrom":"2026-12-19","outboundTo":"2026-12-19","nights":8,"providers":["serpapi"],"cacheOnly":true}""",
                    )
                }
            assertEquals(HttpStatusCode.OK, estimate.status)
            assertTrue(estimate.bodyAsText().contains("liveCalls") || estimate.bodyAsText().contains("warnings"))

            val search =
                client.post("/api/search") {
                    header("Host", "127.0.0.1:8080")
                    contentType(ContentType.Application.Json)
                    header("X-Clawfare-Session", sessionToken)
                    setBody(
                        """{"origins":["LHR"],"destinations":["TPA"],"outboundFrom":"2026-12-19","outboundTo":"2026-12-19","nights":8,"providers":["serpapi"],"cacheOnly":true,"confirmed":true,"investigation":"cache-only"}""",
                    )
                }
            assertEquals(HttpStatusCode.OK, search.status)
            assertTrue(search.bodyAsText().contains("\"ok\":true"))

            val sellers =
                client.post("/api/results/florida-dec-2026/vendors") {
                    header("Host", "127.0.0.1:8080")
                    contentType(ContentType.Application.Json)
                    header("X-Clawfare-Session", sessionToken)
                    setBody("""{"itineraryId":"H","markets":["uk"],"cacheOnly":true}""")
                }
            assertEquals(HttpStatusCode.OK, sellers.status)
            assertTrue(sellers.bodyAsText().contains("BudgetAir"))

            val tracked =
                client.post("/api/results/florida-dec-2026/track") {
                    header("Host", "127.0.0.1:8080")
                    contentType(ContentType.Application.Json)
                    header("X-Clawfare-Session", sessionToken)
                    setBody("""{"itineraryId":"11","tripSlug":"tracked-ba"}""")
                }
            assertEquals(HttpStatusCode.OK, tracked.status)
            assertTrue(tracked.bodyAsText().contains("tracked-ba"))

            val badTrack =
                client.post("/api/results/florida-dec-2026/track") {
                    header("Host", "127.0.0.1:8080")
                    contentType(ContentType.Application.Json)
                    header("X-Clawfare-Session", sessionToken)
                    setBody("""{"itineraryId":"missing","tripSlug":"nope"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, badTrack.status)
            assertEquals("""{"error":"Could not save."}""", badTrack.bodyAsText())

            val csv =
                client.post("/api/export/csv") {
                    header("Host", "127.0.0.1:8080")
                    contentType(ContentType.Application.Json)
                    header("X-Clawfare-Session", sessionToken)
                    setBody("""{"result":{"id":"florida-dec-2026","title":"Florida"},"kind":"itineraries"}""")
                }
            assertEquals(HttpStatusCode.OK, csv.status)
        }
        assertTrue(InvestigationQueries.getBySlug("florida-dec-2026") != null)
        assertTrue(InvestigationQueries.getBySlug("tracked-ba") != null)
    }
}
