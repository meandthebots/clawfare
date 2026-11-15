package com.clawfare

import com.clawfare.cli.ClawfareCommand
import com.clawfare.db.ClawfareDatabase
import com.clawfare.db.FlightQueries
import com.clawfare.db.InvestigationQueries
import com.clawfare.db.PriceHistoryQueries
import com.clawfare.export.JsonExporter
import com.clawfare.model.FlightValidator
import com.clawfare.search.FareStore
import com.clawfare.search.GoogleFlightsProvider
import com.clawfare.search.GoogleFlightsStyle
import com.clawfare.search.HttpFetcher
import com.clawfare.search.HttpResult
import com.clawfare.search.SampleData
import com.clawfare.search.SampleSeeder
import com.clawfare.search.SearchQuery
import com.clawfare.search.SearchService
import com.clawfare.search.SqliteResponseCache
import com.clawfare.search.StoredResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.io.File
import java.sql.DriverManager

class FareStoreTest {
    @Test
    fun sampleRoundTripsThroughTheDatabase() {
        ClawfareDatabase.connectInMemory()
        val loaded = SampleSeeder.ensure()
        assertEquals(SampleData.ID, loaded.id)
        assertEquals(26, loaded.itineraries.size)
        assertEquals(8, loaded.addons.size)
        assertEquals(165, loaded.vendors.size)
        assertTrue(
            loaded.vendors
                .first()
                .vendor
                .contains("BudgetAir"),
        )
        assertTrue(loaded.vendors.first().mix)
        assertEquals(745.0, loaded.vendors.first().allInGbp)
        val cell = loaded.cells.first { it.primaryId == "23" && it.addonId == "H" }
        assertEquals(3341.0, cell.total)
        val ba = loaded.itineraries.first { it.id == "11" }
        assertTrue(
            ba.links
                .first { it.kind == "exact" }
                .url
                .contains("tfs="),
        )
        assertTrue(ba.links.any { it.kind == "airline" })
        val missing = loaded.itineraries.first { it.id == "24" }
        assertTrue(missing.returnMissing)
        assertEquals("2026-12-31", missing.returnDate)
        val jetBlue = loaded.itineraries.first { it.id == "12" }
        assertTrue(jetBlue.airlineCodes.contains("B6"))

        val flight = FlightQueries.listByInvestigation(SampleData.ID).first { it.displayId == "11" }
        val withPrice = FlightQueries.getWithPrice(flight.id)
        assertNotNull(withPrice)
        val entry = JsonExporter.flightWithPriceToEntry(withPrice!!)
        val validation = FlightValidator.validate(entry)
        assertTrue(validation.isValid, validation.errors.joinToString("; "))
        assertTrue(PriceHistoryQueries.getByFlightId(flight.id).size >= 2)

        val again = SampleSeeder.ensure()
        assertEquals(26, again.itineraries.size)
        val inv = InvestigationQueries.getBySlug(SampleData.ID)
        assertNotNull(inv)
        assertTrue(inv!!.origins!!.contains("LHR"))
        assertTrue(inv.origins!!.contains("LGW"))
        assertEquals(1, inv.checkedBags)
    }

    @Test
    fun providerResultBecomesAFlightAndARecheckAppendsHistory() {
        ClawfareDatabase.connectInMemory()
        val http = Scripted()
        http.enqueue(200, outbound())
        http.enqueue(200, inbound())
        val provider = GoogleFlightsProvider("serpapi", "SerpApi", "test-key", GoogleFlightsStyle.SERPAPI, http)
        val service = SearchService(listOf(provider), SqliteResponseCache(), FareStore)
        val query =
            SearchQuery(
                origins = listOf("LHR"),
                destinations = listOf("TPA"),
                outboundFrom = "2026-12-19",
                outboundTo = "2026-12-19",
                nights = 8,
                adults = 1,
                cabin = "premium_economy",
                providers = listOf("serpapi"),
                confirmed = true,
                investigation = "tampa-dec",
            )
        val first = service.search(query)
        assertTrue(first.ok, first.error)
        assertEquals(1, first.result!!.itineraries.size)
        val flight = FlightQueries.listByInvestigation("tampa-dec").single()
        val entry = JsonExporter.flightWithPriceToEntry(FlightQueries.getWithPrice(flight.id)!!)
        assertTrue(FlightValidator.validate(entry).isValid, FlightValidator.validate(entry).errors.joinToString("; "))
        assertEquals(
            "BA",
            entry.outbound.legs
                .first()
                .airlineCode,
        )
        assertNotNull(
            entry.outbound.legs
                .first()
                .operatingCarrier,
        )

        val priced = first.result!!.itineraries.first()
        FareStore.save(
            StoredResult(
                first.result!!.copy(
                    itineraries =
                        listOf(
                            priced.copy(pricePerPerson = priced.pricePerPerson!! + 40, fetchedAt = "2026-11-01T00:00:00Z"),
                        ),
                ),
            ),
        )
        assertEquals(2, PriceHistoryQueries.getByFlightId(flight.id).size)

        http.enqueue(429, """{"error":"Your account has run out of searches"}""")
        val blocked = service.search(query.copy(outboundFrom = "2026-12-20", outboundTo = "2026-12-20", confirmed = true))
        assertTrue(blocked.ok)
        assertTrue(blocked.result!!.warnings.any { it.contains("out of credits", ignoreCase = true) })
        assertFalse(
            blocked.result!!
                .warnings
                .joinToString()
                .contains("test-key"),
        )
    }

    @Test
    fun sqliteCacheDropsSecretsAndSupportsReingest() {
        ClawfareDatabase.connectInMemory()
        val cache = SqliteResponseCache()
        cache.put(
            "serpapi",
            mapOf("engine" to "google_flights", "api_key" to "serp-secret-KEY123"),
            """{"error":"serp-secret-KEY123"}""",
            listOf("serp-secret-KEY123"),
        )
        val hit = cache.get("serpapi", mapOf("engine" to "google_flights", "token" to "other"))
        assertNotNull(hit)
        assertFalse(hit!!.body.contains("serp-secret-KEY123"))
        assertTrue(hit.body.contains("REDACTED"))
    }

    @Test
    fun migrationAddsFareColumnsToAnOlderDatabase() {
        val file = File.createTempFile("clawfare-migrate-", ".db")
        file.deleteOnExit()
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().executeUpdate(
                """
                CREATE TABLE investigations (
                    slug TEXT PRIMARY KEY,
                    origin TEXT NOT NULL,
                    destination TEXT NOT NULL,
                    depart_start TEXT NOT NULL,
                    depart_end TEXT NOT NULL,
                    cabin_class TEXT,
                    max_stops INTEGER,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            conn.createStatement().executeUpdate(
                """
                CREATE TABLE flights (
                    id TEXT PRIMARY KEY,
                    investigation_slug TEXT NOT NULL,
                    source TEXT NOT NULL,
                    trip_type TEXT NOT NULL,
                    ticket_structure TEXT NOT NULL,
                    origin TEXT NOT NULL,
                    destination TEXT NOT NULL,
                    outbound_json TEXT NOT NULL,
                    captured_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            conn.createStatement().executeUpdate(
                """
                CREATE TABLE price_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    flight_id TEXT NOT NULL,
                    amount DOUBLE PRECISION NOT NULL,
                    currency TEXT NOT NULL,
                    checked_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
        ClawfareDatabase.connect(file.absolutePath)
        val columns = mutableSetOf<String>()
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            val rs = conn.createStatement().executeQuery("PRAGMA table_info(investigations)")
            while (rs.next()) columns += rs.getString("name")
            val flights = mutableSetOf<String>()
            val frs = conn.createStatement().executeQuery("PRAGMA table_info(flights)")
            while (frs.next()) flights += frs.getString("name")
            assertTrue(columns.contains("origins"))
            assertTrue(columns.contains("checked_bags"))
            assertTrue(flights.contains("links_json"))
            assertTrue(flights.contains("cabin_class"))
            val tables = mutableSetOf<String>()
            val trs = conn.createStatement().executeQuery("SELECT name FROM sqlite_master WHERE type='table'")
            while (trs.next()) tables += trs.getString("name")
            assertTrue(tables.contains("seller_offers"))
            assertTrue(tables.contains("provider_cache"))
        }
    }

    @Test
    fun cliLoadsTheSampleAndExportsWithoutCallingAProvider() {
        ClawfareDatabase.connectInMemory()
        assertEquals(0, CommandLine(ClawfareCommand()).execute("fare", "load-sample"))
        val html = File.createTempFile("clawfare", ".html")
        val csv = File.createTempFile("clawfare", ".csv")
        assertEquals(0, CommandLine(ClawfareCommand()).execute("report", "html", SampleData.ID, "--out", html.absolutePath))
        assertEquals(0, CommandLine(ClawfareCommand()).execute("report", "csv", SampleData.ID, "--out", csv.absolutePath, "--vendors"))
        assertTrue(html.readText().contains("BA 211"))
        assertFalse(html.readText().contains("window.__SNAPSHOT__ = null"))
        assertTrue(csv.readText().contains("BudgetAir"))
        assertEquals(0, CommandLine(ClawfareCommand()).execute("report", "top", SampleData.ID, "--limit", "3"))
        val estimate =
            CommandLine(ClawfareCommand()).execute(
                "fare",
                "estimate",
                "--origin",
                "LHR,LGW",
                "--dest",
                "TPA",
                "--depart",
                "2026-12-19:2026-12-20",
                "--nights",
                "8",
                "--providers",
                "serpapi",
                "--cache-only",
            )
        assertEquals(0, estimate)
        val unconfirmed =
            CommandLine(ClawfareCommand()).execute(
                "fare",
                "fetch",
                "would-spend",
                "--origin",
                "LHR",
                "--dest",
                "TPA",
                "--depart",
                "2026-12-19",
                "--nights",
                "8",
                "--providers",
                "serpapi",
            )
        assertEquals(2, unconfirmed)

        assertEquals(0, CommandLine(ClawfareCommand()).execute("fare"))
        assertEquals(0, CommandLine(ClawfareCommand()).execute("report"))
        assertEquals(0, CommandLine(ClawfareCommand()).execute("fare", "quota"))
        assertEquals(0, CommandLine(ClawfareCommand()).execute("report", "json", SampleData.ID))
        val itineraryCsv = File.createTempFile("clawfare-itins", ".csv")
        assertEquals(
            0,
            CommandLine(ClawfareCommand()).execute(
                "report",
                "csv",
                SampleData.ID,
                "--out",
                itineraryCsv.absolutePath,
                "--max-stops",
                "0",
                "--nonstop",
            ),
        )
        assertTrue(itineraryCsv.readText().contains("BA"))
        assertEquals(1, CommandLine(ClawfareCommand()).execute("report", "html", "missing-trip", "--out", html.absolutePath))
        assertEquals(1, CommandLine(ClawfareCommand()).execute("report", "csv", "missing-trip", "--out", csv.absolutePath))
        assertEquals(
            1,
            CommandLine(ClawfareCommand()).execute(
                "fare",
                "estimate",
                "--origin",
                "LHR",
                "--dest",
                "TPA",
                "--depart",
                "2026-12-19",
                "--providers",
                "serpapi",
            ),
        )
        assertEquals(
            0,
            CommandLine(ClawfareCommand()).execute(
                "fare",
                "fetch",
                "empty-search",
                "--origin",
                "LHR",
                "--dest",
                "TPA",
                "--depart",
                "2026-12-19",
                "--nights",
                "8",
                "--providers",
                "serpapi",
                "--cache-only",
            ),
        )
        assertEquals(0, CommandLine(ClawfareCommand()).execute("fare", "reingest", "empty-search"))
        assertEquals(1, CommandLine(ClawfareCommand()).execute("fare", "reingest", "never-fetched"))
    }
}

private class Scripted : HttpFetcher {
    private val queue = ArrayDeque<HttpResult>()

    fun enqueue(
        status: Int,
        body: String,
    ) {
        queue.add(HttpResult(status, body))
    }

    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResult = queue.removeFirst()
}

private fun leg(
    number: String,
    from: String,
    to: String,
    date: String,
    depart: String,
    arrive: String,
) =
    """{"departure_airport":{"id":"$from","time":"$date $depart"},"arrival_airport":{"id":"$to","time":"$date $arrive"},"duration":480,"airline":"British Airways","flight_number":"$number","travel_class":"Premium economy","airplane":"777"}"""

private fun outbound() =
    """{"best_flights":[{"price":2800,"total_duration":600,"departure_token":"DEP-1","flights":[${leg(
        "BA 211",
        "LHR",
        "TPA",
        "2026-12-19",
        "13:00",
        "17:45",
    )}]}]}"""

private fun inbound() =
    """{"best_flights":[{"price":2833,"total_duration":560,"booking_token":"book-1","flights":[${leg(
        "BA 210",
        "TPA",
        "LHR",
        "2026-12-27",
        "20:10",
        "09:30",
    )}]}]}"""
