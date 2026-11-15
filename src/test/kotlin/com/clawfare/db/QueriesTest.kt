package com.clawfare.db

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueriesTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        // Use a temp file for test database to avoid in-memory connection issues
        val dbPath = tempDir.resolve("test.db").toString()
        ClawfareDatabase.connect(path = dbPath, createSchema = true)
    }

    @AfterEach
    fun teardown() {
        ClawfareDatabase.dropTables()
    }

    // ========================================================================
    // Investigation Tests
    // ========================================================================

    @Test
    fun `create investigation`() {
        val dto = createTestInvestigation("tokyo-2026")
        val result = InvestigationQueries.create(dto)

        assertEquals(dto.slug, result.slug)
        assertEquals(dto.origin, result.origin)
        assertEquals(dto.destination, result.destination)
    }

    @Test
    fun `get investigation by slug`() {
        val dto = createTestInvestigation("tokyo-2026")
        InvestigationQueries.create(dto)

        val result = InvestigationQueries.getBySlug("tokyo-2026")

        assertNotNull(result)
        assertEquals("tokyo-2026", result.slug)
        assertEquals("LHR", result.origin)
        assertEquals("NRT", result.destination)
    }

    @Test
    fun `get investigation by slug returns null for non-existent`() {
        val result = InvestigationQueries.getBySlug("non-existent")
        assertNull(result)
    }

    @Test
    fun `list all investigations`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        InvestigationQueries.create(createTestInvestigation("paris-2026"))

        val results = InvestigationQueries.listAll()

        assertEquals(2, results.size)
        assertTrue(results.any { it.slug == "tokyo-2026" })
        assertTrue(results.any { it.slug == "paris-2026" })
    }

    @Test
    fun `update investigation`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))

        val updated =
            InvestigationQueries.update(
                createTestInvestigation("tokyo-2026").copy(
                    cabinClass = "business",
                    maxStops = 2,
                ),
            )

        assertNotNull(updated)
        assertEquals("business", updated.cabinClass)
        assertEquals(2, updated.maxStops)
    }

    @Test
    fun `update non-existent investigation returns null`() {
        val result =
            InvestigationQueries.update(
                createTestInvestigation("non-existent"),
            )
        assertNull(result)
    }

    @Test
    fun `delete investigation`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))

        val deleted = InvestigationQueries.delete("tokyo-2026")

        assertTrue(deleted)
        assertNull(InvestigationQueries.getBySlug("tokyo-2026"))
    }

    @Test
    fun `delete non-existent investigation returns false`() {
        val deleted = InvestigationQueries.delete("non-existent")
        assertTrue(!deleted)
    }

    // ========================================================================
    // Flight Tests
    // ========================================================================

    @Test
    fun `create flight`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        val dto = createTestFlight("abc123", "tokyo-2026")

        val result = FlightQueries.create(dto)

        assertEquals(dto.id, result.id)
        assertEquals(dto.shareLink, result.shareLink)
        assertEquals(dto.priceAmount, result.priceAmount)
    }

    @Test
    fun `get flight by id`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))

        val result = FlightQueries.getById("abc123")

        assertNotNull(result)
        assertEquals("abc123", result.id)
        assertEquals(2500.0, result.priceAmount)
    }

    @Test
    fun `get flight by id returns null for non-existent`() {
        val result = FlightQueries.getById("non-existent")
        assertNull(result)
    }

    @Test
    fun `get flight by share link`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))

        val result = FlightQueries.getByShareLink("https://google.com/flights/abc123")

        assertNotNull(result)
        assertEquals("abc123", result.id)
    }

    @Test
    fun `list flights by investigation`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        InvestigationQueries.create(createTestInvestigation("paris-2026"))

        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))
        FlightQueries.create(createTestFlight("def456", "tokyo-2026"))
        FlightQueries.create(createTestFlight("ghi789", "paris-2026"))

        val results = FlightQueries.listByInvestigation("tokyo-2026")

        assertEquals(2, results.size)
        assertTrue(results.all { it.investigationSlug == "tokyo-2026" })
    }

    @Test
    fun `list all flights`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))
        FlightQueries.create(createTestFlight("def456", "tokyo-2026"))

        val results = FlightQueries.listAll()

        assertEquals(2, results.size)
    }

    @Test
    fun `update flight`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))

        val updated =
            FlightQueries.update(
                createTestFlight("abc123", "tokyo-2026").copy(
                    priceAmount = 2200.0,
                    notes = "Price dropped!",
                ),
            )

        assertNotNull(updated)
        assertEquals(2200.0, updated.priceAmount)
        assertEquals("Price dropped!", updated.notes)
    }

    @Test
    fun `update non-existent flight returns null`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))

        val result =
            FlightQueries.update(
                createTestFlight("non-existent", "tokyo-2026"),
            )
        assertNull(result)
    }

    @Test
    fun `update price via dto`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        val flight = FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))

        val updated = FlightQueries.update(flight.copy(priceAmount = 1999.0, priceCurrency = "GBP"))

        assertNotNull(updated)
        assertEquals(1999.0, updated!!.priceAmount)
        assertEquals("GBP", updated.priceCurrency)
    }

    @Test
    fun `delete flight`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))

        val deleted = FlightQueries.delete("abc123")

        assertTrue(deleted)
        assertNull(FlightQueries.getById("abc123"))
    }

    @Test
    fun `delete flight also deletes price history`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))
        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2500.0))
        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2400.0))

        FlightQueries.delete("abc123")

        assertEquals(0, PriceHistoryQueries.getByFlightId("abc123").size)
    }

    @Test
    fun `delete flights by investigation`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))
        FlightQueries.create(createTestFlight("def456", "tokyo-2026"))

        val deleted = FlightQueries.deleteByInvestigation("tokyo-2026")

        assertEquals(2, deleted)
        assertEquals(0, FlightQueries.listByInvestigation("tokyo-2026").size)
    }

    @Test
    fun `delete flights by investigation also deletes price history`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))
        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2500.0))

        FlightQueries.deleteByInvestigation("tokyo-2026")

        assertEquals(0, PriceHistoryQueries.getByFlightId("abc123").size)
    }

    @Test
    fun `cabin mixed stored correctly`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(
            createTestFlight("abc123", "tokyo-2026").copy(cabinMixed = true),
        )

        val result = FlightQueries.getById("abc123")

        assertNotNull(result)
        assertTrue(result.cabinMixed)
    }

    // ========================================================================
    // Price History Tests
    // ========================================================================

    @Test
    fun `create price history`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))

        val dto = createTestPriceHistory("abc123", 2500.0)
        val result = PriceHistoryQueries.create(dto)

        assertNotNull(result.id)
        assertEquals(dto.flightId, result.flightId)
        assertEquals(dto.amount, result.amount)
    }

    @Test
    fun `get price history by flight id`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))

        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2500.0, "2024-01-01T00:00:00Z"))
        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2400.0, "2024-01-02T00:00:00Z"))
        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2300.0, "2024-01-03T00:00:00Z"))

        val results = PriceHistoryQueries.getByFlightId("abc123")

        assertEquals(3, results.size)
        // Should be ordered by checkedAt
        assertEquals(2500.0, results[0].amount)
        assertEquals(2400.0, results[1].amount)
        assertEquals(2300.0, results[2].amount)
    }

    @Test
    fun `get latest price history`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))

        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2500.0, "2024-01-01T00:00:00Z"))
        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2200.0, "2024-01-03T00:00:00Z"))
        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2400.0, "2024-01-02T00:00:00Z"))

        val latest = PriceHistoryQueries.getLatest("abc123")

        assertNotNull(latest)
        assertEquals(2200.0, latest.amount) // Most recent by checkedAt
    }

    @Test
    fun `get latest returns null for no history`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))

        val latest = PriceHistoryQueries.getLatest("abc123")

        assertNull(latest)
    }

    @Test
    fun `delete price history by flight id`() {
        InvestigationQueries.create(createTestInvestigation("tokyo-2026"))
        FlightQueries.create(createTestFlight("abc123", "tokyo-2026"))

        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2500.0))
        PriceHistoryQueries.create(createTestPriceHistory("abc123", 2400.0))

        val deleted = PriceHistoryQueries.deleteByFlightId("abc123")

        assertEquals(2, deleted)
        assertEquals(0, PriceHistoryQueries.getByFlightId("abc123").size)
    }

    // ========================================================================
    // Helper Functions
    // ========================================================================

    private fun createTestInvestigation(slug: String): InvestigationDto =
        InvestigationDto(
            slug = slug,
            origin = "LHR",
            destination = "NRT",
            departStart = "2026-05-01",
            departEnd = "2026-05-15",
            returnStart = "2026-05-15",
            returnEnd = "2026-05-30",
            cabinClass = "economy",
            maxStops = 1,
            createdAt = "2024-01-01T00:00:00Z",
            updatedAt = "2024-01-01T00:00:00Z",
        )

    private fun createTestFlight(
        id: String,
        investigationSlug: String,
    ): FlightDto =
        FlightDto(
            id = id,
            investigationSlug = investigationSlug,
            shareLink = "https://google.com/flights/$id",
            source = "google_flights",
            tripType = "round_trip",
            ticketStructure = "single",
            priceAmount = 2500.0,
            priceCurrency = "GBP",
            priceMarket = "UK",
            origin = "LHR",
            destination = "NRT",
            outboundJson = """{"depart_airport":"LHR","arrive_airport":"NRT","legs":[]}""",
            returnJson = """{"depart_airport":"NRT","arrive_airport":"LHR","legs":[]}""",
            bookingClass = "Y",
            cabinMixed = false,
            notes = null,
            tags = null,
            capturedAt = "2024-01-01T00:00:00Z",
            priceCheckedAt = "2024-01-01T00:00:00Z",
        )

    private fun createTestPriceHistory(
        flightId: String,
        amount: Double,
        checkedAt: String = "2024-01-01T00:00:00Z",
    ): PriceHistoryDto =
        PriceHistoryDto(
            flightId = flightId,
            amount = amount,
            currency = "GBP",
            checkedAt = checkedAt,
        )
}

/**
 * Tests for the auto-migration system.
 * Verifies that opening an old-schema DB auto-upgrades it.
 */
class MigrationTest {
    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: java.nio.file.Path

    @Test
    fun `migration adds source_url to old price_history`() {
        val dbPath = tempDir.resolve("old-schema.db").toString()

        // Create DB with old schema (no source_url)
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().executeUpdate(
            """
            CREATE TABLE investigations (
                slug TEXT PRIMARY KEY, origin TEXT, destination TEXT,
                depart_start TEXT, depart_end TEXT, return_start TEXT, return_end TEXT,
                cabin_class TEXT, max_stops INTEGER, created_at TEXT, updated_at TEXT,
                min_trip_days INTEGER, max_trip_days INTEGER, must_include_date TEXT, max_layover_minutes INTEGER
            )
        """,
        )
        conn.createStatement().executeUpdate(
            """
            CREATE TABLE flights (
                id TEXT PRIMARY KEY, investigation_slug TEXT, share_link TEXT, source TEXT,
                trip_type TEXT, ticket_structure TEXT,
                price_amount DOUBLE, price_currency TEXT, price_market TEXT, price_checked_at TEXT,
                origin TEXT, destination TEXT, outbound_json TEXT, return_json TEXT,
                booking_class TEXT, cabin_mixed INTEGER DEFAULT 0, stale INTEGER DEFAULT 0,
                aircraft_type TEXT, fare_brand TEXT, disqualified TEXT, notes TEXT, tags TEXT,
                captured_at TEXT
            )
        """,
        )
        conn.createStatement().executeUpdate(
            """
            CREATE TABLE price_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT, flight_id TEXT, amount DOUBLE,
                currency TEXT, checked_at TEXT, source TEXT DEFAULT 'manual', price_market TEXT DEFAULT 'UK'
            )
        """,
        )
        // Insert test data
        conn.createStatement().executeUpdate(
            "INSERT INTO investigations VALUES ('test', 'LHR', 'NRT', '2026-05-01', '2026-05-15', '2026-05-15', '2026-05-30', 'business', 1, '2026-01-01', '2026-01-01', 14, 18, '2026-05-16', 300)",
        )
        conn.createStatement().executeUpdate(
            "INSERT INTO flights VALUES ('f1', 'test', 'https://example.com/flight1', 'gf', 'round_trip', 'single', 2500, 'GBP', 'UK', '2026-03-01', 'LHR', 'NRT', '{\"depart_airport\":\"LHR\",\"arrive_airport\":\"NRT\",\"depart_time\":\"2026-05-01T10:00:00Z\",\"arrive_time\":\"2026-05-02T06:00:00Z\",\"duration_minutes\":720,\"stops\":0,\"legs\":[{\"flight_number\":\"123\",\"airline\":\"BA\",\"airline_code\":\"BA\",\"depart_airport\":\"LHR\",\"arrive_airport\":\"NRT\",\"depart_time\":\"2026-05-01T10:00:00Z\",\"arrive_time\":\"2026-05-02T06:00:00Z\",\"duration_minutes\":720}]}', NULL, 'business', 0, 0, NULL, NULL, NULL, NULL, NULL, '2026-03-01')",
        )
        conn.createStatement().executeUpdate(
            "INSERT INTO price_history (flight_id, amount, currency, checked_at) VALUES ('f1', 2500, 'GBP', '2026-03-01')",
        )
        conn.close()

        // Verify old schema has no source_url
        val connCheck = java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath")
        val cols = mutableSetOf<String>()
        val rs = connCheck.createStatement().executeQuery("PRAGMA table_info(price_history)")
        while (rs.next()) cols.add(rs.getString("name"))
        assertFalse(cols.contains("source_url"), "Old schema should not have source_url")
        connCheck.close()

        // Now open with ClawfareDatabase — this should auto-migrate
        ClawfareDatabase.connect(path = dbPath, createSchema = true)

        // Verify source_url was added and populated
        val history = PriceHistoryQueries.getByFlightId("f1")
        assertEquals(1, history.size)
        assertEquals("https://example.com/flight1", history[0].sourceUrl, "source_url should be migrated from flights.share_link")

        // Verify we can still read flights
        val flight = FlightQueries.getById("f1")
        assertNotNull(flight)
        assertEquals("LHR", flight!!.origin)

        ClawfareDatabase.dropTables()
    }

    @Test
    fun `migration adds stale column to old flights`() {
        val dbPath = tempDir.resolve("no-stale.db").toString()

        // Create DB without stale column
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().executeUpdate(
            """
            CREATE TABLE investigations (
                slug TEXT PRIMARY KEY, origin TEXT, destination TEXT,
                depart_start TEXT, depart_end TEXT, return_start TEXT, return_end TEXT,
                cabin_class TEXT, max_stops INTEGER, created_at TEXT, updated_at TEXT,
                min_trip_days INTEGER, max_trip_days INTEGER, must_include_date TEXT, max_layover_minutes INTEGER
            )
        """,
        )
        conn.createStatement().executeUpdate(
            """
            CREATE TABLE flights (
                id TEXT PRIMARY KEY, investigation_slug TEXT, share_link TEXT, source TEXT,
                trip_type TEXT, ticket_structure TEXT,
                price_amount DOUBLE, price_currency TEXT, price_market TEXT, price_checked_at TEXT,
                origin TEXT, destination TEXT, outbound_json TEXT, return_json TEXT,
                booking_class TEXT, cabin_mixed INTEGER DEFAULT 0,
                aircraft_type TEXT, fare_brand TEXT, disqualified TEXT, notes TEXT, tags TEXT,
                captured_at TEXT
            )
        """,
        )
        conn.createStatement().executeUpdate(
            """
            CREATE TABLE price_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT, flight_id TEXT, amount DOUBLE,
                currency TEXT, source_url TEXT, checked_at TEXT, source TEXT DEFAULT 'manual', price_market TEXT DEFAULT 'UK'
            )
        """,
        )
        conn.close()

        // Open with ClawfareDatabase — should add stale column
        ClawfareDatabase.connect(path = dbPath, createSchema = true)

        // Verify stale column exists by creating a flight
        val now =
            java.time.Instant
                .now()
                .toString()
        InvestigationQueries.create(
            InvestigationDto(
                slug = "t",
                origin = "LHR",
                destination = "NRT",
                departStart = "2026-05-01",
                departEnd = "2026-05-15",
                returnStart = "2026-05-15",
                returnEnd = "2026-05-30",
                cabinClass = "business",
                maxStops = 1,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val json =
            "{\"depart_airport\":\"LHR\",\"arrive_airport\":\"NRT\"," +
                "\"depart_time\":\"2026-05-01T10:00:00Z\",\"arrive_time\":\"2026-05-02T06:00:00Z\"," +
                "\"duration_minutes\":720,\"stops\":0,\"legs\":[{\"flight_number\":\"1\"," +
                "\"airline\":\"BA\",\"airline_code\":\"BA\",\"depart_airport\":\"LHR\",\"arrive_airport\":\"NRT\"," +
                "\"depart_time\":\"2026-05-01T10:00:00Z\",\"arrive_time\":\"2026-05-02T06:00:00Z\"," +
                "\"duration_minutes\":720}]}"
        FlightQueries.create(
            FlightDto(
                id = "f1",
                investigationSlug = "t",
                source = "gf",
                tripType = "round_trip",
                ticketStructure = "single",
                origin = "LHR",
                destination = "NRT",
                outboundJson = json,
                capturedAt = now,
            ),
        )

        val flight = FlightQueries.getById("f1")
        assertNotNull(flight)
        assertFalse(flight!!.stale, "New flight should not be stale by default")

        // Mark stale and verify
        FlightQueries.update(flight.copy(stale = true))
        assertTrue(FlightQueries.getById("f1")!!.stale)

        ClawfareDatabase.dropTables()
    }
}
