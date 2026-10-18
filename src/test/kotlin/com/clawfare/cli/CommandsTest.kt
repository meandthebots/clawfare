package com.clawfare.cli

import com.clawfare.db.ClawfareDatabase
import com.clawfare.db.FlightDto
import com.clawfare.db.FlightQueries
import com.clawfare.db.InvestigationDto
import com.clawfare.db.InvestigationQueries
import com.clawfare.db.PriceHistoryDto
import com.clawfare.db.PriceHistoryQueries
import com.clawfare.model.FlightValidator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant

/**
 * Integration tests for CLI commands.
 * Uses in-memory SQLite database with shared connection.
 */
class CommandsTest {
    private lateinit var outStream: ByteArrayOutputStream
    private lateinit var errStream: ByteArrayOutputStream
    private val originalOut = System.out
    private val originalErr = System.err

    @BeforeEach
    fun setup() {
        // Connect to shared in-memory database and create tables
        ClawfareDatabase.connectInMemory(createSchema = true)

        // Capture output
        outStream = ByteArrayOutputStream()
        errStream = ByteArrayOutputStream()
        System.setOut(PrintStream(outStream))
        System.setErr(PrintStream(errStream))
    }

    @AfterEach
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        ClawfareDatabase.dropTables()
    }

    private fun stdout(): String = outStream.toString()

    private fun stderr(): String = errStream.toString()

    /**
     * Execute CLI command with pre-configured database.
     * The command will use the already-connected database.
     */
    private fun execute(vararg args: String): Int {
        val clawfareCmd = ClawfareCommand()
        val cmd = CommandLine(clawfareCmd)
        return cmd.execute(*args)
    }

    // ========================================================================
    // Investigation Tests
    // ========================================================================

    @Test
    fun `inv new creates investigation`() {
        val exitCode =
            execute(
                "inv",
                "new",
                "tokyo-may-2026",
                "--origin",
                "LHR",
                "--dest",
                "NRT",
                "--depart",
                "2026-05-01:2026-05-15",
                "--return",
                "2026-05-15:2026-05-30",
                "--cabin",
                "business",
            )

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("Created investigation"))
        assertTrue(stdout().contains("tokyo-may-2026"))

        // Verify in database
        val inv = InvestigationQueries.getBySlug("tokyo-may-2026")
        assertNotNull(inv)
        assertEquals("LHR", inv!!.origin)
        assertEquals("NRT", inv.destination)
        assertEquals("business", inv.cabinClass)
    }

    @Test
    fun `inv new rejects duplicate slug`() {
        // Create first investigation
        createTestInvestigation("tokyo-may-2026")

        val exitCode =
            execute(
                "inv",
                "new",
                "tokyo-may-2026",
                "--origin",
                "LHR",
                "--dest",
                "NRT",
                "--depart",
                "2026-05-01:2026-05-15",
            )

        assertEquals(1, exitCode)
        assertTrue(stderr().contains("already exists"))
    }

    @Test
    fun `inv list shows all investigations`() {
        createTestInvestigation("tokyo-may-2026")
        createTestInvestigation("paris-june-2026")

        val exitCode = execute("inv", "list")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("tokyo-may-2026"))
        assertTrue(stdout().contains("paris-june-2026"))
    }

    @Test
    fun `inv list empty returns message`() {
        val exitCode = execute("inv", "list")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("No investigations found"))
    }

    @Test
    fun `inv show displays investigation details`() {
        createTestInvestigation("tokyo-may-2026")

        val exitCode = execute("inv", "show", "tokyo-may-2026")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("tokyo-may-2026"))
        assertTrue(stdout().contains("LHR → NRT"))
    }

    @Test
    fun `inv show returns error for unknown slug`() {
        val exitCode = execute("inv", "show", "unknown")

        assertEquals(1, exitCode)
        assertTrue(stderr().contains("not found"))
    }

    @Test
    fun `inv delete removes investigation`() {
        createTestInvestigation("tokyo-may-2026")

        val exitCode = execute("inv", "delete", "tokyo-may-2026", "--force")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("Deleted"))
        assertNull(InvestigationQueries.getBySlug("tokyo-may-2026"))
    }

    @Test
    fun `inv delete requires force when flights exist`() {
        createTestInvestigation("tokyo-may-2026")
        createTestFlight("tokyo-may-2026", "https://example.com/flight1")

        val exitCode = execute("inv", "delete", "tokyo-may-2026")

        assertEquals(1, exitCode)
        assertTrue(stdout().contains("Use --force"))
        assertNotNull(InvestigationQueries.getBySlug("tokyo-may-2026"))
    }

    @Test
    fun `inv delete with force removes flights too`() {
        createTestInvestigation("tokyo-may-2026")
        createTestFlight("tokyo-may-2026", "https://example.com/flight1")

        val exitCode = execute("inv", "delete", "tokyo-may-2026", "--force")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertNull(InvestigationQueries.getBySlug("tokyo-may-2026"))
        assertTrue(FlightQueries.listByInvestigation("tokyo-may-2026").isEmpty())
    }

    // ========================================================================
    // Flight Tests
    // ========================================================================

    private val testOutboundJson =
        """
        {
            "depart_airport": "LHR",
            "arrive_airport": "NRT",
            "depart_time": "2026-05-01T10:00:00Z",
            "arrive_time": "2026-05-02T06:00:00Z",
            "duration_minutes": 720,
            "stops": 0,
            "legs": [{
                "flight_number": "123",
                "airline": "British Airways",
                "airline_code": "BA",
                "depart_airport": "LHR",
                "arrive_airport": "NRT",
                "depart_time": "2026-05-01T10:00:00Z",
                "arrive_time": "2026-05-02T06:00:00Z",
                "duration_minutes": 720
            }]
        }
        """.trimIndent()

    private val testReturnJson =
        """
        {
            "depart_airport": "NRT",
            "arrive_airport": "LHR",
            "depart_time": "2026-05-15T10:00:00Z",
            "arrive_time": "2026-05-15T18:00:00Z",
            "duration_minutes": 780,
            "stops": 0,
            "legs": [{
                "flight_number": "124",
                "airline": "British Airways",
                "airline_code": "BA",
                "depart_airport": "NRT",
                "arrive_airport": "LHR",
                "depart_time": "2026-05-15T10:00:00Z",
                "arrive_time": "2026-05-15T18:00:00Z",
                "duration_minutes": 780
            }]
        }
        """.trimIndent()

    @Test
    fun `flight add creates flight`() {
        createTestInvestigation("tokyo-may-2026")

        val exitCode =
            execute(
                "flight",
                "add",
                "tokyo-may-2026",
                "--link",
                "https://example.com/flight",
                "--source",
                "google_flights",
                "--price",
                "2847",
                "--currency",
                "GBP",
                "--market",
                "UK",
                "--outbound",
                testOutboundJson,
                "--return",
                testReturnJson,
            )

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("Added flight"))

        // Verify in database
        val flights = FlightQueries.listByInvestigation("tokyo-may-2026")
        assertEquals(1, flights.size)

        // Price is canonical in price_history, not on flight
        val history = PriceHistoryQueries.getByFlightId(flights[0].id)
        assertEquals(1, history.size)
        assertEquals(2847.0, history[0].amount)
        assertEquals("https://example.com/flight", history[0].sourceUrl)
    }

    @Test
    fun `flight add rejects duplicate link`() {
        createTestInvestigation("tokyo-may-2026")
        createTestFlight("tokyo-may-2026", "https://example.com/flight")

        val exitCode =
            execute(
                "flight",
                "add",
                "tokyo-may-2026",
                "--link",
                "https://example.com/flight",
                "--source",
                "google_flights",
                "--price",
                "2847",
                "--currency",
                "GBP",
                "--market",
                "UK",
                "--outbound",
                testOutboundJson,
                "--return",
                testReturnJson,
            )

        assertEquals(1, exitCode)
        assertTrue(stderr().contains("already exists"))
    }

    @Test
    fun `flight add rejects invalid investigation`() {
        val exitCode =
            execute(
                "flight",
                "add",
                "unknown-investigation",
                "--link",
                "https://example.com/flight",
                "--source",
                "google_flights",
                "--price",
                "2847",
                "--currency",
                "GBP",
                "--market",
                "UK",
                "--outbound",
                testOutboundJson,
            )

        assertEquals(1, exitCode)
        assertTrue(stderr().contains("not found"))
    }

    @Test
    fun `flight list shows flights`() {
        createTestInvestigation("tokyo-may-2026")
        createTestFlight("tokyo-may-2026", "https://example.com/flight1", 2500.0)
        createTestFlight("tokyo-may-2026", "https://example.com/flight2", 2800.0)

        val exitCode = execute("flight", "list", "tokyo-may-2026")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("LHR→NRT"))
    }

    @Test
    fun `flight list sorts by price`() {
        createTestInvestigation("tokyo-may-2026")
        createTestFlight("tokyo-may-2026", "https://example.com/flight1", 2800.0)
        createTestFlight("tokyo-may-2026", "https://example.com/flight2", 2500.0)

        val exitCode = execute("flight", "list", "tokyo-may-2026", "--sort", "price")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        // First flight in output should be cheaper
        val lines = stdout().lines().filter { it.contains("£") }
        assertTrue(lines.isNotEmpty())
    }

    @Test
    fun `flight list filters by max price`() {
        createTestInvestigation("tokyo-may-2026")
        createTestFlight("tokyo-may-2026", "https://example.com/flight1", 2500.0)
        createTestFlight("tokyo-may-2026", "https://example.com/flight2", 3000.0)

        val exitCode =
            execute(
                "flight",
                "list",
                "tokyo-may-2026",
                "--max-price",
                "2600",
            )

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("2500"))
        assertFalse(stdout().contains("3000"))
    }

    @Test
    fun `flight show displays details`() {
        createTestInvestigation("tokyo-may-2026")
        val flightId = createTestFlight("tokyo-may-2026", "https://example.com/flight")

        val exitCode = execute("flight", "show", flightId)

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains(flightId))
        assertTrue(stdout().contains("LHR → NRT"))
    }

    @Test
    fun `flight show returns error for unknown id`() {
        val exitCode = execute("flight", "show", "unknown")

        assertEquals(1, exitCode)
        assertTrue(stderr().contains("not found"))
    }

    @Test
    fun `flight delete removes flight`() {
        createTestInvestigation("tokyo-may-2026")
        val flightId = createTestFlight("tokyo-may-2026", "https://example.com/flight")

        val exitCode = execute("flight", "delete", flightId)

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("Deleted"))
        assertNull(FlightQueries.getById(flightId))
    }

    @Test
    fun `flight tag adds tag`() {
        createTestInvestigation("tokyo-may-2026")
        val flightId = createTestFlight("tokyo-may-2026", "https://example.com/flight")

        val exitCode = execute("flight", "tag", flightId, "shortlist")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("Added tag"))

        val flight = FlightQueries.getById(flightId)
        assertNotNull(flight)
        assertTrue(flight!!.tags?.contains("shortlist") ?: false)
    }

    @Test
    fun `flight untag removes tag`() {
        createTestInvestigation("tokyo-may-2026")
        val flightId = createTestFlight("tokyo-may-2026", "https://example.com/flight")
        // First add the tag
        val flight = FlightQueries.getById(flightId)!!
        FlightQueries.update(flight.copy(tags = Output.encodeTags(listOf("shortlist", "reviewed"))))

        val exitCode = execute("flight", "untag", flightId, "shortlist")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("Removed tag"))

        val updatedFlight = FlightQueries.getById(flightId)
        val tags = updatedFlight!!.tags?.let { Output.parseTags(it) } ?: emptyList()
        assertFalse(tags.contains("shortlist"))
        assertTrue(tags.contains("reviewed"))
    }

    @Test
    fun `flight price updates price and records history`() {
        createTestInvestigation("tokyo-may-2026")
        val flightId = createTestFlight("tokyo-may-2026", "https://example.com/flight", 2500.0)

        val exitCode =
            execute(
                "flight",
                "price",
                flightId,
                "--amount",
                "2300",
            )

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("Updated price"))

        // Verify price updated
        val flight = FlightQueries.getById(flightId)
        assertEquals(2300.0, flight!!.priceAmount)

        // Verify history recorded
        val history = PriceHistoryQueries.getByFlightId(flightId)
        assertEquals(2, history.size) // Initial + update
        assertEquals(2300.0, history.last().amount)
    }

    @Test
    fun `flight list filters by tag`() {
        createTestInvestigation("tokyo-may-2026")
        val id1 = createTestFlight("tokyo-may-2026", "https://example.com/flight1", 2500.0)
        createTestFlight("tokyo-may-2026", "https://example.com/flight2", 2800.0)

        // Tag one flight
        val flight = FlightQueries.getById(id1)!!
        FlightQueries.update(flight.copy(tags = Output.encodeTags(listOf("shortlist"))))

        val exitCode =
            execute(
                "flight",
                "list",
                "tokyo-may-2026",
                "--tag",
                "shortlist",
            )

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        val output = stdout()
        // Only check for first 8 chars of ID (table truncates)
        assertTrue(output.contains(id1.take(8)), "Expected output to contain ${id1.take(8)}, got: $output")
    }

    // ========================================================================
    // Output Formatting Tests
    // ========================================================================

    @Test
    fun `formatPrice formats GBP correctly`() {
        assertEquals("£100.00", Output.formatPrice(100.0, "GBP"))
    }

    @Test
    fun `formatPrice formats USD correctly`() {
        assertEquals("$100.00", Output.formatPrice(100.0, "USD"))
    }

    @Test
    fun `formatPrice formats JPY without decimals`() {
        assertEquals("¥15000", Output.formatPrice(15000.0, "JPY"))
    }

    @Test
    fun `formatDuration formats hours and minutes`() {
        assertEquals("2h 30m", Output.formatDuration(150))
    }

    @Test
    fun `formatDuration handles hours only`() {
        assertEquals("2h", Output.formatDuration(120))
    }

    @Test
    fun `formatDuration handles minutes only`() {
        assertEquals("45m", Output.formatDuration(45))
    }

    @Test
    fun `parseTags handles valid JSON`() {
        val tags = Output.parseTags("""["shortlist","reviewed"]""")
        assertEquals(listOf("shortlist", "reviewed"), tags)
    }

    @Test
    fun `parseTags handles invalid JSON`() {
        val tags = Output.parseTags("invalid")
        assertEquals(emptyList<String>(), tags)
    }

    @Test
    fun `encodeTags creates valid JSON`() {
        val json = Output.encodeTags(listOf("a", "b"))
        assertEquals("""["a","b"]""", json)
    }

    // ========================================================================
    // Helper Functions
    // ========================================================================

    private fun createTestInvestigation(slug: String): InvestigationDto {
        val now = Instant.now().toString()
        val dto =
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
                createdAt = now,
                updatedAt = now,
            )
        return InvestigationQueries.create(dto)
    }

    private fun createTestFlight(
        investigationSlug: String,
        shareLink: String,
        price: Double = 2500.0,
        airline: String = "British Airways",
        airlineCode: String = "BA",
        departDate: String = "2026-05-01",
    ): String {
        val id = FlightValidator.generateId(shareLink)
        val now = Instant.now().toString()

        val outboundJson =
            """
            {
                "depart_airport": "LHR",
                "arrive_airport": "NRT",
                "depart_time": "${departDate}T10:00:00Z",
                "arrive_time": "${departDate}T06:00:00Z",
                "duration_minutes": 720,
                "stops": 0,
                "legs": [{
                    "flight_number": "123",
                    "airline": "$airline",
                    "airline_code": "$airlineCode",
                    "depart_airport": "LHR",
                    "arrive_airport": "NRT",
                    "depart_time": "${departDate}T10:00:00Z",
                    "arrive_time": "${departDate}T06:00:00Z",
                    "duration_minutes": 720
                }]
            }
            """.trimIndent()

        val dto =
            FlightDto(
                id = id,
                investigationSlug = investigationSlug,
                shareLink = shareLink,
                source = "google_flights",
                tripType = "round_trip",
                ticketStructure = "single",
                priceAmount = price,
                priceCurrency = "GBP",
                priceMarket = "UK",
                origin = "LHR",
                destination = "NRT",
                outboundJson = outboundJson,
                returnJson = outboundJson,
                capturedAt = now,
                priceCheckedAt = now,
            )
        FlightQueries.create(dto)

        // Add initial price history
        PriceHistoryQueries.create(
            PriceHistoryDto(
                flightId = id,
                amount = price,
                currency = "GBP",
                checkedAt = now,
            ),
        )

        return id
    }
}

/**
 * Integration tests for flight import commands.
 * Tests both JSON import and database import functionality.
 */
class FlightImportTest {
    private lateinit var outStream: ByteArrayOutputStream
    private lateinit var errStream: ByteArrayOutputStream
    private lateinit var originalIn: java.io.InputStream
    private val originalOut = System.out
    private val originalErr = System.err

    @BeforeEach
    fun setup() {
        ClawfareDatabase.connectInMemory(createSchema = true)

        outStream = ByteArrayOutputStream()
        errStream = ByteArrayOutputStream()
        originalIn = System.`in`
        System.setOut(PrintStream(outStream))
        System.setErr(PrintStream(errStream))
    }

    @AfterEach
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        System.setIn(originalIn)
        ClawfareDatabase.dropTables()
    }

    private fun stdout(): String = outStream.toString()

    private fun stderr(): String = errStream.toString()

    private fun executeWithStdin(
        input: String,
        vararg args: String,
    ): Int {
        System.setIn(java.io.ByteArrayInputStream(input.toByteArray()))
        val clawfareCmd = ClawfareCommand()
        val cmd = CommandLine(clawfareCmd)
        return cmd.execute(*args)
    }

    private fun execute(vararg args: String): Int {
        val clawfareCmd = ClawfareCommand()
        val cmd = CommandLine(clawfareCmd)
        return cmd.execute(*args)
    }

    // ========================================================================
    // JSON Import Tests (flight import)
    // ========================================================================

    @Test
    fun `flight import schema prints expected format`() {
        val exitCode = execute("flight", "import", "--schema")

        assertEquals(0, exitCode)
        assertTrue(stdout().contains("origin"))
        assertTrue(stdout().contains("destination"))
        assertTrue(stdout().contains("departDate"))
        assertTrue(stdout().contains("price"))
    }

    @Test
    fun `flight import updates existing flight price`() {
        createTestInvestigation("tokyo-may-2026")
        createTestFlight("tokyo-may-2026", "https://example.com/ba-flight", 2500.0, "British Airways", "BA", "2026-05-01")

        val importJson = """[{
            "origin": "LHR",
            "destination": "NRT",
            "departDate": "2026-05-01",
            "airline": "British Airways",
            "price": 2200.0,
            "currency": "GBP"
        }]"""

        val exitCode = executeWithStdin(importJson, "flight", "import", "tokyo-may-2026")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("1 updated"))

        // Verify price history was recorded
        val flights = FlightQueries.listByInvestigation("tokyo-may-2026")
        assertEquals(1, flights.size)

        val history = PriceHistoryQueries.getByFlightId(flights[0].id)
        assertEquals(2, history.size) // Initial + update
        assertEquals(2200.0, history.last().amount)
    }

    @Test
    fun `flight import records unchanged price as check`() {
        createTestInvestigation("tokyo-may-2026")
        createTestFlight("tokyo-may-2026", "https://example.com/ba-flight", 2500.0, "British Airways", "BA", "2026-05-01")

        val importJson = """[{
            "origin": "LHR",
            "destination": "NRT",
            "departDate": "2026-05-01",
            "airline": "British Airways",
            "price": 2500.0,
            "currency": "GBP"
        }]"""

        val exitCode = executeWithStdin(importJson, "flight", "import", "tokyo-may-2026")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("1 unchanged"))

        // Verify a price check was still recorded
        val flights = FlightQueries.listByInvestigation("tokyo-may-2026")
        val history = PriceHistoryQueries.getByFlightId(flights[0].id)
        assertEquals(2, history.size) // Initial + check
    }

    @Test
    fun `flight import with add-new creates new flights`() {
        createTestInvestigation("tokyo-may-2026")

        val importJson = """[{
            "origin": "LHR",
            "destination": "NRT",
            "departDate": "2026-05-14",
            "returnDate": "2026-05-30",
            "airline": "Korean Air",
            "price": 4347,
            "currency": "GBP",
            "link": "https://www.kayak.co.uk/book/flight/KE907-2026-05-14/details"
        }]"""

        val exitCode = executeWithStdin(importJson, "flight", "import", "--add-new", "tokyo-may-2026")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("1 added"))

        // Verify flight was created
        val flights = FlightQueries.listByInvestigation("tokyo-may-2026")
        assertEquals(1, flights.size)
        assertEquals("LHR", flights[0].origin)
        assertEquals("NRT", flights[0].destination)

        // Price is canonical in price_history
        val history = PriceHistoryQueries.getByFlightId(flights[0].id)
        assertEquals(1, history.size)
        assertEquals(4347.0, history[0].amount)
        assertEquals("GBP", history[0].currency)
        assertEquals("https://www.kayak.co.uk/book/flight/KE907-2026-05-14/details", history[0].sourceUrl)
        assertEquals("NRT", flights[0].destination)
    }

    @Test
    fun `flight import with add-new skips existing links`() {
        createTestInvestigation("tokyo-may-2026")

        // First import
        val importJson = """[{
            "origin": "LHR",
            "destination": "NRT",
            "departDate": "2026-05-14",
            "airline": "Korean Air",
            "price": 4347,
            "currency": "GBP",
            "link": "https://www.kayak.co.uk/book/flight/test-booking-123"
        }]"""
        executeWithStdin(importJson, "flight", "import", "--add-new", "tokyo-may-2026")

        // Reset streams
        outStream.reset()

        // Second import with same link - will match existing flight and report unchanged
        val exitCode = executeWithStdin(importJson, "flight", "import", "--add-new", "tokyo-may-2026")

        assertEquals(0, exitCode)
        // Second import matches by origin/dest/date/airline, so it reports unchanged (same price)
        assertTrue(stdout().contains("1 unchanged"), "Expected 1 unchanged in output: ${stdout()}")

        // Should still be only 1 flight
        val flights = FlightQueries.listByInvestigation("tokyo-may-2026")
        assertEquals(1, flights.size)
    }

    @Test
    fun `flight import without add-new reports unmatched`() {
        createTestInvestigation("tokyo-may-2026")

        val importJson = """[{
            "origin": "LHR",
            "destination": "NRT",
            "departDate": "2026-05-14",
            "airline": "Korean Air",
            "price": 4347,
            "currency": "GBP"
        }]"""

        val exitCode = executeWithStdin(importJson, "flight", "import", "tokyo-may-2026")

        assertEquals(0, exitCode)
        assertTrue(stdout().contains("1 not matched"))

        // No flights should be created
        val flights = FlightQueries.listByInvestigation("tokyo-may-2026")
        assertEquals(0, flights.size)
    }

    @Test
    fun `flight import dry-run shows what would happen`() {
        createTestInvestigation("tokyo-may-2026")
        createTestFlight("tokyo-may-2026", "https://example.com/ba-flight", 2500.0, "British Airways", "BA", "2026-05-01")

        val importJson = """[{
            "origin": "LHR",
            "destination": "NRT",
            "departDate": "2026-05-01",
            "airline": "British Airways",
            "price": 2200.0,
            "currency": "GBP"
        }]"""

        val exitCode = executeWithStdin(importJson, "flight", "import", "--dry-run", "tokyo-may-2026")

        assertEquals(0, exitCode)
        assertTrue(stdout().contains("[DRY RUN]"))

        // Price should NOT be updated
        val flights = FlightQueries.listByInvestigation("tokyo-may-2026")
        val history = PriceHistoryQueries.getByFlightId(flights[0].id)
        assertEquals(1, history.size) // Only initial, no update
        assertEquals(2500.0, history[0].amount)
    }

    @Test
    fun `flight import add-new dry-run shows what would be added`() {
        createTestInvestigation("tokyo-may-2026")

        val importJson = """[{
            "origin": "LHR",
            "destination": "NRT",
            "departDate": "2026-05-14",
            "airline": "Korean Air",
            "price": 4347,
            "currency": "GBP"
        }]"""

        val exitCode = executeWithStdin(importJson, "flight", "import", "--add-new", "--dry-run", "tokyo-may-2026")

        assertEquals(0, exitCode)
        assertTrue(stdout().contains("[DRY RUN] Would add"))
        assertTrue(stdout().contains("Korean Air"))

        // No flights should be created
        val flights = FlightQueries.listByInvestigation("tokyo-may-2026")
        assertEquals(0, flights.size)
    }

    @Test
    fun `flight import handles multiple flights`() {
        createTestInvestigation("tokyo-may-2026")
        createTestFlight("tokyo-may-2026", "https://example.com/ba-flight", 2500.0, "British Airways", "BA", "2026-05-01")

        val importJson = """[
            {"origin": "LHR", "destination": "NRT", "departDate": "2026-05-01", "airline": "British Airways", "price": 2200.0, "currency": "GBP"},
            {"origin": "LHR", "destination": "NRT", "departDate": "2026-05-14", "airline": "Korean Air", "price": 4347, "currency": "GBP", "link": "https://kayak.com/book/test-123"}
        ]"""

        val exitCode = executeWithStdin(importJson, "flight", "import", "--add-new", "tokyo-may-2026")

        assertEquals(0, exitCode, "Expected success but got stderr: ${stderr()}")
        assertTrue(stdout().contains("1 updated"))
        assertTrue(stdout().contains("1 added"))

        val flights = FlightQueries.listByInvestigation("tokyo-may-2026")
        assertEquals(2, flights.size)
    }

    @Test
    fun `flight import rejects empty input`() {
        createTestInvestigation("tokyo-may-2026")

        val exitCode = executeWithStdin("", "flight", "import", "tokyo-may-2026")

        assertEquals(1, exitCode)
        assertTrue(stderr().contains("No input"))
    }

    @Test
    fun `flight import rejects invalid json`() {
        createTestInvestigation("tokyo-may-2026")

        val exitCode = executeWithStdin("not valid json", "flight", "import", "tokyo-may-2026")

        assertEquals(1, exitCode)
        assertTrue(stderr().contains("Failed to parse"))
    }

    @Test
    fun `flight import rejects unknown investigation`() {
        val importJson = """[{"origin": "LHR", "destination": "NRT", "departDate": "2026-05-01", "airline": "BA", "price": 1000}]"""

        val exitCode = executeWithStdin(importJson, "flight", "import", "unknown-investigation")

        assertEquals(1, exitCode)
        assertTrue(stderr().contains("not found"))
    }

    // ========================================================================
    // Helper Functions
    // ========================================================================

    private fun createTestInvestigation(slug: String): InvestigationDto {
        val now = Instant.now().toString()
        val dto =
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
                createdAt = now,
                updatedAt = now,
            )
        return InvestigationQueries.create(dto)
    }

    private fun createTestFlight(
        investigationSlug: String,
        shareLink: String,
        price: Double,
        airline: String,
        airlineCode: String,
        departDate: String,
    ): String {
        val id = FlightValidator.generateId(shareLink)
        val now = Instant.now().toString()

        val outboundJson =
            """
            {
                "depart_airport": "LHR",
                "arrive_airport": "NRT",
                "depart_time": "${departDate}T10:00:00Z",
                "arrive_time": "${departDate}T06:00:00Z",
                "duration_minutes": 720,
                "stops": 0,
                "legs": [{
                    "flight_number": "123",
                    "airline": "$airline",
                    "airline_code": "$airlineCode",
                    "depart_airport": "LHR",
                    "arrive_airport": "NRT",
                    "depart_time": "${departDate}T10:00:00Z",
                    "arrive_time": "${departDate}T06:00:00Z",
                    "duration_minutes": 720
                }]
            }
            """.trimIndent()

        val dto =
            FlightDto(
                id = id,
                investigationSlug = investigationSlug,
                shareLink = shareLink,
                source = "google_flights",
                tripType = "round_trip",
                ticketStructure = "single",
                priceAmount = price,
                priceCurrency = "GBP",
                priceMarket = "UK",
                origin = "LHR",
                destination = "NRT",
                outboundJson = outboundJson,
                returnJson = outboundJson,
                capturedAt = now,
                priceCheckedAt = now,
            )
        FlightQueries.create(dto)

        PriceHistoryQueries.create(
            PriceHistoryDto(
                flightId = id,
                amount = price,
                currency = "GBP",
                checkedAt = now,
            ),
        )

        return id
    }
}

/**
 * Tests for price tracking and denormalization.
 */
class PriceTrackingTest {
    private lateinit var outStream: ByteArrayOutputStream
    private val originalOut = System.out

    @BeforeEach
    fun setup() {
        ClawfareDatabase.connectInMemory(createSchema = true)
        outStream = ByteArrayOutputStream()
        System.setOut(PrintStream(outStream))
    }

    @AfterEach
    fun tearDown() {
        System.setOut(originalOut)
        ClawfareDatabase.dropTables()
    }

    private fun execute(vararg args: String): Int = CommandLine(ClawfareCommand()).execute(*args)

    @Test
    fun `flight price updates denormalized price`() {
        createInv("tokyo")
        val id = createFlight("tokyo", "https://example.com/f1", 2500.0)

        execute("flight", "price", id, "--amount", "2200")

        assertEquals(2200.0, FlightQueries.getById(id)!!.priceAmount)
    }

    @Test
    fun `flight price records history entry`() {
        createInv("tokyo")
        val id = createFlight("tokyo", "https://example.com/f1", 2500.0)

        execute("flight", "price", id, "--amount", "2200")

        val history = PriceHistoryQueries.getByFlightId(id)
        assertEquals(2, history.size)
        assertEquals(2200.0, history.last().amount)
    }

    @Test
    fun `flight price allows currency change`() {
        createInv("tokyo")
        val id = createFlight("tokyo", "https://example.com/f1", 2500.0)

        execute("flight", "price", id, "--amount", "3500", "--currency", "USD")

        val flight = FlightQueries.getById(id)!!
        assertEquals(3500.0, flight.priceAmount)
        assertEquals("USD", flight.priceCurrency)
    }

    @Test
    fun `flight price preserves market info in history`() {
        createInv("tokyo")
        val id = createFlight("tokyo", "https://example.com/f1", 2500.0)

        execute("flight", "price", id, "--amount", "2200")

        val history = PriceHistoryQueries.getByFlightId(id)
        assertEquals("UK", history.last().priceMarket)
    }

    private fun createInv(slug: String) =
        InvestigationQueries.create(
            InvestigationDto(
                slug = slug,
                origin = "LHR",
                destination = "NRT",
                departStart = "2026-05-01",
                departEnd = "2026-05-15",
                returnStart = "2026-05-15",
                returnEnd = "2026-05-30",
                cabinClass = "business",
                maxStops = 1,
                createdAt = Instant.now().toString(),
                updatedAt = Instant.now().toString(),
            ),
        )

    private fun createFlight(
        investigationSlug: String,
        shareLink: String,
        price: Double,
    ): String {
        val id = FlightValidator.generateId(shareLink)
        val now = Instant.now().toString()
        val json =
            "{\"depart_airport\":\"LHR\",\"arrive_airport\":\"NRT\"," +
                "\"depart_time\":\"2026-05-01T10:00:00Z\",\"arrive_time\":\"2026-05-02T06:00:00Z\"," +
                "\"duration_minutes\":720,\"stops\":0,\"legs\":[{\"flight_number\":\"123\"," +
                "\"airline\":\"BA\",\"airline_code\":\"BA\",\"depart_airport\":\"LHR\",\"arrive_airport\":\"NRT\"," +
                "\"depart_time\":\"2026-05-01T10:00:00Z\",\"arrive_time\":\"2026-05-02T06:00:00Z\"," +
                "\"duration_minutes\":720}]}"
        FlightQueries.create(
            FlightDto(
                id = id,
                investigationSlug = investigationSlug,
                shareLink = shareLink,
                source = "google_flights",
                tripType = "round_trip",
                ticketStructure = "single",
                priceAmount = price,
                priceCurrency = "GBP",
                priceMarket = "UK",
                origin = "LHR",
                destination = "NRT",
                outboundJson = json,
                returnJson = json,
                capturedAt = now,
                priceCheckedAt = now,
            ),
        )
        PriceHistoryQueries.create(PriceHistoryDto(flightId = id, amount = price, currency = "GBP", checkedAt = now))
        return id
    }
}

/**
 * Tests for flight matching logic in imports.
 */
class FlightMatchingTest {
    private lateinit var outStream: ByteArrayOutputStream
    private lateinit var originalIn: java.io.InputStream
    private val originalOut = System.out

    @BeforeEach
    fun setup() {
        ClawfareDatabase.connectInMemory(createSchema = true)
        outStream = ByteArrayOutputStream()
        originalIn = System.`in`
        System.setOut(PrintStream(outStream))
    }

    @AfterEach
    fun tearDown() {
        System.setOut(originalOut)
        System.setIn(originalIn)
        ClawfareDatabase.dropTables()
    }

    private fun stdout(): String = outStream.toString()

    private fun execStdin(
        input: String,
        vararg args: String,
    ): Int {
        System.setIn(java.io.ByteArrayInputStream(input.toByteArray()))
        return CommandLine(ClawfareCommand()).execute(*args)
    }

    @Test
    fun `import matches by airline name case-insensitive`() {
        createInv("tokyo")
        createFlight("tokyo", "https://example.com/f1", 2500.0, "British Airways", "BA", "2026-05-01")

        execStdin(
            """[{"origin":"LHR","destination":"NRT","departDate":"2026-05-01","airline":"BRITISH AIRWAYS","price":2200.0,"currency":"GBP"}]""",
            "flight",
            "import",
            "tokyo",
        )

        assertTrue(stdout().contains("1 updated"))
    }

    @Test
    fun `import matches by airline code`() {
        createInv("tokyo")
        createFlight("tokyo", "https://example.com/f1", 2500.0, "British Airways", "BA", "2026-05-01")

        execStdin(
            """[{"origin":"LHR","destination":"NRT","departDate":"2026-05-01","airline":"Other","airlineCode":"BA","price":2200.0,"currency":"GBP"}]""",
            "flight",
            "import",
            "tokyo",
        )

        assertTrue(stdout().contains("1 updated"))
    }

    @Test
    fun `import no match on different date`() {
        createInv("tokyo")
        createFlight("tokyo", "https://example.com/f1", 2500.0, "British Airways", "BA", "2026-05-01")

        execStdin(
            """[{"origin":"LHR","destination":"NRT","departDate":"2026-05-15","airline":"British Airways","price":2200.0,"currency":"GBP"}]""",
            "flight",
            "import",
            "tokyo",
        )

        assertTrue(stdout().contains("1 not matched"))
    }

    @Test
    fun `import no match on different airline`() {
        createInv("tokyo")
        createFlight("tokyo", "https://example.com/f1", 2500.0, "British Airways", "BA", "2026-05-01")

        execStdin(
            """[{"origin":"LHR","destination":"NRT","departDate":"2026-05-01","airline":"Lufthansa","price":2200.0,"currency":"GBP"}]""",
            "flight",
            "import",
            "tokyo",
        )

        assertTrue(stdout().contains("1 not matched"))
    }

    @Test
    fun `import no match on different origin`() {
        createInv("tokyo")
        createFlight("tokyo", "https://example.com/f1", 2500.0, "British Airways", "BA", "2026-05-01")

        execStdin(
            """[{"origin":"LGW","destination":"NRT","departDate":"2026-05-01","airline":"British Airways","price":2200.0,"currency":"GBP"}]""",
            "flight",
            "import",
            "tokyo",
        )

        assertTrue(stdout().contains("1 not matched"))
    }

    @Test
    fun `import add-new creates round trip with return date`() {
        createInv("tokyo")

        execStdin(
            """[{"origin":"LHR","destination":"NRT","departDate":"2026-05-14","returnDate":"2026-05-30","airline":"Korean Air","price":4347,"currency":"GBP"}]""",
            "flight",
            "import",
            "--add-new",
            "tokyo",
        )

        val flights = FlightQueries.listByInvestigation("tokyo")
        assertEquals(1, flights.size)
        assertEquals("round_trip", flights[0].tripType)
        assertNotNull(flights[0].returnJson)

        // Price is canonical in price_history
        val history = PriceHistoryQueries.getByFlightId(flights[0].id)
        assertEquals(1, history.size)
        assertEquals(4347.0, history[0].amount)
    }

    @Test
    fun `import add-new creates one-way without return date`() {
        createInv("tokyo")

        execStdin(
            """[{"origin":"LHR","destination":"NRT","departDate":"2026-05-14","airline":"Korean Air","price":2000,"currency":"GBP"}]""",
            "flight",
            "import",
            "--add-new",
            "tokyo",
        )

        val flights = FlightQueries.listByInvestigation("tokyo")
        assertEquals(1, flights.size)
        assertEquals("one_way", flights[0].tripType)
        assertNull(flights[0].returnJson)
    }

    @Test
    fun `import add-new sets source to import`() {
        createInv("tokyo")

        execStdin(
            """[{"origin":"LHR","destination":"NRT","departDate":"2026-05-14","airline":"Korean Air","price":2000,"currency":"GBP"}]""",
            "flight",
            "import",
            "--add-new",
            "tokyo",
        )

        val flights = FlightQueries.listByInvestigation("tokyo")
        assertEquals("import", flights[0].source)
    }

    private fun createInv(slug: String) =
        InvestigationQueries.create(
            InvestigationDto(
                slug = slug,
                origin = "LHR",
                destination = "NRT",
                departStart = "2026-05-01",
                departEnd = "2026-05-15",
                returnStart = "2026-05-15",
                returnEnd = "2026-05-30",
                cabinClass = "business",
                maxStops = 1,
                createdAt = Instant.now().toString(),
                updatedAt = Instant.now().toString(),
            ),
        )

    private fun createFlight(
        investigationSlug: String,
        shareLink: String,
        price: Double,
        airline: String,
        airlineCode: String,
        departDate: String,
    ): String {
        val id = FlightValidator.generateId(shareLink)
        val now = Instant.now().toString()
        val json =
            "{\"depart_airport\":\"LHR\",\"arrive_airport\":\"NRT\"," +
                "\"depart_time\":\"${departDate}T10:00:00Z\",\"arrive_time\":\"${departDate}T06:00:00Z\"," +
                "\"duration_minutes\":720,\"stops\":0,\"legs\":[{\"flight_number\":\"123\"," +
                "\"airline\":\"$airline\",\"airline_code\":\"$airlineCode\"," +
                "\"depart_airport\":\"LHR\",\"arrive_airport\":\"NRT\"," +
                "\"depart_time\":\"${departDate}T10:00:00Z\",\"arrive_time\":\"${departDate}T06:00:00Z\"," +
                "\"duration_minutes\":720}]}"
        FlightQueries.create(
            FlightDto(
                id = id,
                investigationSlug = investigationSlug,
                shareLink = shareLink,
                source = "google_flights",
                tripType = "round_trip",
                ticketStructure = "single",
                priceAmount = price,
                priceCurrency = "GBP",
                priceMarket = "UK",
                origin = "LHR",
                destination = "NRT",
                outboundJson = json,
                returnJson = json,
                capturedAt = now,
                priceCheckedAt = now,
            ),
        )
        PriceHistoryQueries.create(PriceHistoryDto(flightId = id, amount = price, currency = "GBP", checkedAt = now))
        return id
    }
}

/**
 * Tests for URL validation (search vs booking URLs).
 */
class UrlValidationTest {
    private lateinit var outStream: ByteArrayOutputStream
    private lateinit var originalIn: java.io.InputStream
    private val originalOut = System.out

    @BeforeEach
    fun setup() {
        ClawfareDatabase.connectInMemory(createSchema = true)
        outStream = ByteArrayOutputStream()
        originalIn = System.`in`
        System.setOut(PrintStream(outStream))
    }

    @AfterEach
    fun tearDown() {
        System.setOut(originalOut)
        System.setIn(originalIn)
        ClawfareDatabase.dropTables()
    }

    private fun stdout(): String = outStream.toString()

    private fun execStdin(
        input: String,
        vararg args: String,
    ): Int {
        System.setIn(java.io.ByteArrayInputStream(input.toByteArray()))
        return CommandLine(ClawfareCommand()).execute(*args)
    }

    @Test
    fun `rejects kayak search page url`() {
        createInv("tokyo")

        execStdin(
            """[{"origin":"LHR","destination":"NRT","departDate":"2026-05-14","airline":"KE","price":2000,"currency":"GBP","link":"https://www.kayak.co.uk/flights/LHR-NRT/2026-05-14/2026-05-30?sort=price"}]""",
            "flight",
            "import",
            "--add-new",
            "tokyo",
        )

        assertTrue(stdout().contains("Rejected") || stdout().contains("search page"))
    }

    @Test
    fun `rejects amex travel search page url`() {
        createInv("tokyo")

        execStdin(
            """[{"origin":"LHR","destination":"NRT","departDate":"2026-05-14","airline":"KE","price":2000,"currency":"GBP","link":"https://www.americanexpress.com/en-gb/travel/flights/"}]""",
            "flight",
            "import",
            "--add-new",
            "tokyo",
        )

        assertTrue(stdout().contains("Rejected") || stdout().contains("search page"))
    }

    @Test
    fun `accepts booking url`() {
        createInv("tokyo")

        execStdin(
            """[{"origin":"LHR","destination":"NRT","departDate":"2026-05-14","airline":"KE","price":2000,"currency":"GBP","link":"https://www.google.com/travel/flights/booking?flight=KE123"}]""",
            "flight",
            "import",
            "--add-new",
            "tokyo",
        )

        assertTrue(stdout().contains("1 added"))
    }

    @Test
    fun `accepts url without link field`() {
        createInv("tokyo")

        execStdin(
            """[{"origin":"LHR","destination":"NRT","departDate":"2026-05-14","airline":"Korean Air","price":2000,"currency":"GBP"}]""",
            "flight",
            "import",
            "--add-new",
            "tokyo",
        )

        assertTrue(stdout().contains("1 added"))
    }

    private fun createInv(slug: String) =
        InvestigationQueries.create(
            InvestigationDto(
                slug = slug,
                origin = "LHR",
                destination = "NRT",
                departStart = "2026-05-01",
                departEnd = "2026-05-15",
                returnStart = "2026-05-15",
                returnEnd = "2026-05-30",
                cabinClass = "business",
                maxStops = 1,
                createdAt = Instant.now().toString(),
                updatedAt = Instant.now().toString(),
            ),
        )
}

/**
 * Tests for auto-migration system.
 * Verifies that old databases are upgraded safely.
 */
class MigrationTest {
    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: java.nio.file.Path

    @Test
    fun `migration adds source_url to price_history`() {
        val dbPath = tempDir.resolve("old.db").toString()

        // Create old-schema DB manually
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().executeUpdate(
            """
            CREATE TABLE investigations (
                slug TEXT PRIMARY KEY, origin TEXT, destination TEXT,
                depart_start TEXT, depart_end TEXT, return_start TEXT, return_end TEXT,
                cabin_class TEXT, max_stops INTEGER, max_price REAL, depart_after TEXT,
                depart_before TEXT, min_trip_days INTEGER, max_trip_days INTEGER,
                must_include_date TEXT, max_layover_minutes INTEGER,
                created_at TEXT, updated_at TEXT
            )
            """.trimIndent(),
        )
        conn.createStatement().executeUpdate(
            """
            CREATE TABLE flights (
                id TEXT PRIMARY KEY, investigation_slug TEXT, share_link TEXT UNIQUE,
                source TEXT, trip_type TEXT, ticket_structure TEXT,
                price_amount REAL, price_currency TEXT, price_market TEXT, price_checked_at TEXT,
                origin TEXT, destination TEXT, outbound_json TEXT, return_json TEXT,
                booking_class TEXT, cabin_mixed INTEGER DEFAULT 0, stale INTEGER DEFAULT 0,
                aircraft_type TEXT, fare_brand TEXT, disqualified TEXT, notes TEXT, tags TEXT,
                captured_at TEXT
            )
            """.trimIndent(),
        )
        // Old schema: no source_url column
        conn.createStatement().executeUpdate(
            """
            CREATE TABLE price_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT, flight_id TEXT,
                amount REAL, currency TEXT, checked_at TEXT,
                source TEXT DEFAULT 'manual', price_market TEXT DEFAULT 'UK'
            )
            """.trimIndent(),
        )
        // Insert test data
        conn.createStatement().executeUpdate(
            """
            INSERT INTO investigations VALUES ('test-inv', 'LHR', 'NRT', '2026-05-01', '2026-05-15', '2026-05-15', '2026-05-30', 'business', 1, NULL, NULL, NULL, NULL, NULL, NULL, NULL, '2026-01-01', '2026-01-01')
            """.trimIndent(),
        )
        conn.createStatement().executeUpdate(
            """
            INSERT INTO flights VALUES ('flight1', 'test-inv', 'https://example.com/flight1', 'google', 'round_trip', 'single', 2500.0, 'GBP', 'UK', '2026-01-01', 'LHR', 'NRT', '{}', '{}', 'business', 0, 0, NULL, NULL, NULL, NULL, NULL, '2026-01-01')
            """.trimIndent(),
        )
        conn.createStatement().executeUpdate(
            """
            INSERT INTO price_history (flight_id, amount, currency, checked_at) VALUES ('flight1', 2500.0, 'GBP', '2026-01-01')
            """.trimIndent(),
        )
        conn.close()

        // Connect with migration
        ClawfareDatabase.connect(path = dbPath, createSchema = true)

        // Verify source_url column exists and was populated from share_link
        val history = PriceHistoryQueries.getByFlightId("flight1")
        assertEquals(1, history.size)
        assertEquals("https://example.com/flight1", history[0].sourceUrl)

        ClawfareDatabase.dropTables()
    }

    @Test
    fun `migration adds stale column if missing`() {
        val dbPath = tempDir.resolve("old-no-stale.db").toString()

        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().executeUpdate(
            """
            CREATE TABLE investigations (
                slug TEXT PRIMARY KEY, origin TEXT, destination TEXT,
                depart_start TEXT, depart_end TEXT, return_start TEXT, return_end TEXT,
                cabin_class TEXT, max_stops INTEGER, max_price REAL, depart_after TEXT,
                depart_before TEXT, min_trip_days INTEGER, max_trip_days INTEGER,
                must_include_date TEXT, max_layover_minutes INTEGER,
                created_at TEXT, updated_at TEXT
            )
            """.trimIndent(),
        )
        // No stale column
        conn.createStatement().executeUpdate(
            """
            CREATE TABLE flights (
                id TEXT PRIMARY KEY, investigation_slug TEXT, share_link TEXT,
                source TEXT, trip_type TEXT, ticket_structure TEXT,
                price_amount REAL, price_currency TEXT, price_market TEXT, price_checked_at TEXT,
                origin TEXT, destination TEXT, outbound_json TEXT, return_json TEXT,
                booking_class TEXT, cabin_mixed INTEGER DEFAULT 0,
                aircraft_type TEXT, fare_brand TEXT, disqualified TEXT, notes TEXT, tags TEXT,
                captured_at TEXT
            )
            """.trimIndent(),
        )
        conn.createStatement().executeUpdate(
            """
            CREATE TABLE price_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT, flight_id TEXT,
                amount REAL, currency TEXT, source_url TEXT, checked_at TEXT,
                source TEXT DEFAULT 'manual', price_market TEXT DEFAULT 'UK'
            )
            """.trimIndent(),
        )
        conn.createStatement().executeUpdate(
            """
            INSERT INTO investigations VALUES ('test-inv', 'LHR', 'NRT', '2026-05-01', '2026-05-15', '2026-05-15', '2026-05-30', 'business', 1, NULL, NULL, NULL, NULL, NULL, NULL, NULL, '2026-01-01', '2026-01-01')
            """.trimIndent(),
        )
        conn.createStatement().executeUpdate(
            """
            INSERT INTO flights (id, investigation_slug, share_link, source, trip_type, ticket_structure, price_amount, price_currency, price_market, price_checked_at, origin, destination, outbound_json, captured_at) VALUES ('flight1', 'test-inv', 'https://example.com/f1', 'google', 'round_trip', 'single', 2500.0, 'GBP', 'UK', '2026-01-01', 'LHR', 'NRT', '{}', '2026-01-01')
            """.trimIndent(),
        )
        conn.close()

        // Connect with migration — should add stale column
        ClawfareDatabase.connect(path = dbPath, createSchema = true)

        // Verify stale column works
        val flight = FlightQueries.getById("flight1")
        assertNotNull(flight)
        assertFalse(flight!!.stale)

        // Should be able to mark stale
        FlightQueries.update(flight.copy(stale = true))
        assertTrue(FlightQueries.getById("flight1")!!.stale)

        ClawfareDatabase.dropTables()
    }
}

/**
 * Tests for mark-stale command.
 */
class MarkStaleTest {
    private lateinit var outStream: ByteArrayOutputStream
    private val originalOut = System.out

    @BeforeEach
    fun setup() {
        ClawfareDatabase.connectInMemory(createSchema = true)
        outStream = ByteArrayOutputStream()
        System.setOut(PrintStream(outStream))
    }

    @AfterEach
    fun tearDown() {
        System.setOut(originalOut)
        ClawfareDatabase.dropTables()
    }

    private fun stdout(): String = outStream.toString()

    private fun execute(vararg args: String): Int = CommandLine(ClawfareCommand()).execute(*args)

    @Test
    fun `mark-stale all marks all flights`() {
        createInv("tokyo")
        val id = createFlight("tokyo", "https://example.com/f1", 2500.0)

        execute("flight", "mark-stale", "tokyo", "--all")

        assertTrue(FlightQueries.getById(id)!!.stale)
        assertTrue(stdout().contains("1 flights"))
    }

    @Test
    fun `mark-stale fresh clears stale flag`() {
        createInv("tokyo")
        val id = createFlight("tokyo", "https://example.com/f1", 2500.0)
        FlightQueries.update(FlightQueries.getById(id)!!.copy(stale = true))

        execute("flight", "mark-stale", "tokyo", "--all", "--fresh")

        assertFalse(FlightQueries.getById(id)!!.stale)
    }

    @Test
    fun `mark-stale dry-run does not change`() {
        createInv("tokyo")
        val id = createFlight("tokyo", "https://example.com/f1", 2500.0)

        execute("flight", "mark-stale", "tokyo", "--all", "--dry-run")

        assertFalse(FlightQueries.getById(id)!!.stale)
        assertTrue(stdout().contains("DRY RUN"))
    }

    @Test
    fun `mark-stale by id marks single flight`() {
        createInv("tokyo")
        val id1 = createFlight("tokyo", "https://example.com/f1", 2500.0)
        val id2 = createFlight("tokyo", "https://example.com/f2", 3000.0)

        execute("flight", "mark-stale", "--id", id1)

        assertTrue(FlightQueries.getById(id1)!!.stale)
        assertFalse(FlightQueries.getById(id2)!!.stale)
    }

    private fun createInv(slug: String) =
        InvestigationQueries.create(
            InvestigationDto(
                slug = slug,
                origin = "LHR",
                destination = "NRT",
                departStart = "2026-05-01",
                departEnd = "2026-05-15",
                returnStart = "2026-05-15",
                returnEnd = "2026-05-30",
                cabinClass = "business",
                maxStops = 1,
                createdAt = Instant.now().toString(),
                updatedAt = Instant.now().toString(),
            ),
        )

    private fun createFlight(
        investigationSlug: String,
        shareLink: String,
        price: Double,
    ): String {
        val id = FlightValidator.generateId(shareLink)
        val now = Instant.now().toString()
        val json =
            "{\"depart_airport\":\"LHR\",\"arrive_airport\":\"NRT\"," +
                "\"depart_time\":\"2026-05-01T10:00:00Z\",\"arrive_time\":\"2026-05-02T06:00:00Z\"," +
                "\"duration_minutes\":720,\"stops\":0,\"legs\":[{\"flight_number\":\"123\"," +
                "\"airline\":\"BA\",\"airline_code\":\"BA\",\"depart_airport\":\"LHR\",\"arrive_airport\":\"NRT\"," +
                "\"depart_time\":\"2026-05-01T10:00:00Z\",\"arrive_time\":\"2026-05-02T06:00:00Z\"," +
                "\"duration_minutes\":720}]}"
        FlightQueries.create(
            FlightDto(
                id = id,
                investigationSlug = investigationSlug,
                shareLink = shareLink,
                source = "google_flights",
                tripType = "round_trip",
                ticketStructure = "single",
                origin = "LHR",
                destination = "NRT",
                outboundJson = json,
                returnJson = json,
                capturedAt = now,
            ),
        )
        PriceHistoryQueries.create(PriceHistoryDto(flightId = id, amount = price, currency = "GBP", sourceUrl = shareLink, checkedAt = now))
        return id
    }
}

/**
 * Tests for flight price command with link.
 */
class FlightPriceLinkTest {
    private lateinit var outStream: ByteArrayOutputStream
    private val originalOut = System.out

    @BeforeEach
    fun setup() {
        ClawfareDatabase.connectInMemory(createSchema = true)
        outStream = ByteArrayOutputStream()
        System.setOut(PrintStream(outStream))
    }

    @AfterEach
    fun tearDown() {
        System.setOut(originalOut)
        ClawfareDatabase.dropTables()
    }

    private fun execute(vararg args: String): Int = CommandLine(ClawfareCommand()).execute(*args)

    @Test
    fun `flight price with link records source url`() {
        createInv("tokyo")
        val id = createFlight("tokyo", "https://example.com/f1", 2500.0)

        execute("flight", "price", id, "--amount", "2200", "--link", "https://www.google.com/travel/flights/s/abc123")

        val history = PriceHistoryQueries.getByFlightId(id)
        assertEquals(2, history.size)
        assertEquals("https://www.google.com/travel/flights/s/abc123", history.last().sourceUrl)
        assertEquals(2200.0, history.last().amount)
    }

    @Test
    fun `flight price clears stale flag`() {
        createInv("tokyo")
        val id = createFlight("tokyo", "https://example.com/f1", 2500.0)
        FlightQueries.update(FlightQueries.getById(id)!!.copy(stale = true))
        assertTrue(FlightQueries.getById(id)!!.stale)

        execute("flight", "price", id, "--amount", "2200")

        assertFalse(FlightQueries.getById(id)!!.stale)
    }

    @Test
    fun `flight price without link uses existing link`() {
        createInv("tokyo")
        val id = createFlight("tokyo", "https://example.com/original", 2500.0)

        execute("flight", "price", id, "--amount", "2200")

        val history = PriceHistoryQueries.getByFlightId(id)
        assertEquals("https://example.com/original", history.last().sourceUrl)
    }

    private fun createInv(slug: String) =
        InvestigationQueries.create(
            InvestigationDto(
                slug = slug,
                origin = "LHR",
                destination = "NRT",
                departStart = "2026-05-01",
                departEnd = "2026-05-15",
                returnStart = "2026-05-15",
                returnEnd = "2026-05-30",
                cabinClass = "business",
                maxStops = 1,
                createdAt = Instant.now().toString(),
                updatedAt = Instant.now().toString(),
            ),
        )

    private fun createFlight(
        investigationSlug: String,
        shareLink: String,
        price: Double,
    ): String {
        val id = FlightValidator.generateId(shareLink)
        val now = Instant.now().toString()
        val json =
            "{\"depart_airport\":\"LHR\",\"arrive_airport\":\"NRT\"," +
                "\"depart_time\":\"2026-05-01T10:00:00Z\",\"arrive_time\":\"2026-05-02T06:00:00Z\"," +
                "\"duration_minutes\":720,\"stops\":0,\"legs\":[{\"flight_number\":\"123\"," +
                "\"airline\":\"BA\",\"airline_code\":\"BA\",\"depart_airport\":\"LHR\",\"arrive_airport\":\"NRT\"," +
                "\"depart_time\":\"2026-05-01T10:00:00Z\",\"arrive_time\":\"2026-05-02T06:00:00Z\"," +
                "\"duration_minutes\":720}]}"
        FlightQueries.create(
            FlightDto(
                id = id,
                investigationSlug = investigationSlug,
                shareLink = shareLink,
                source = "google_flights",
                tripType = "round_trip",
                ticketStructure = "single",
                origin = "LHR",
                destination = "NRT",
                outboundJson = json,
                returnJson = json,
                capturedAt = now,
            ),
        )
        PriceHistoryQueries.create(PriceHistoryDto(flightId = id, amount = price, currency = "GBP", sourceUrl = shareLink, checkedAt = now))
        return id
    }

    private fun createBusinessFlight(
        investigationSlug: String,
        shareLink: String,
        price: Double,
        tripType: String = "round_trip",
    ): String {
        val id = FlightValidator.generateId(shareLink)
        val now = Instant.now().toString()
        val json =
            "{\"depart_airport\":\"LHR\",\"arrive_airport\":\"NRT\"," +
                "\"depart_time\":\"2026-05-01T10:00:00Z\",\"arrive_time\":\"2026-05-02T06:00:00Z\"," +
                "\"duration_minutes\":720,\"stops\":0,\"legs\":[{\"flight_number\":\"123\"," +
                "\"airline\":\"BA\",\"airline_code\":\"BA\",\"depart_airport\":\"LHR\",\"arrive_airport\":\"NRT\"," +
                "\"depart_time\":\"2026-05-01T10:00:00Z\",\"arrive_time\":\"2026-05-02T06:00:00Z\"," +
                "\"duration_minutes\":720}]}"
        FlightQueries.create(
            FlightDto(
                id = id,
                investigationSlug = investigationSlug,
                shareLink = shareLink,
                source = "google_flights",
                tripType = tripType,
                ticketStructure = "single",
                origin = "LHR",
                destination = "NRT",
                outboundJson = json,
                returnJson =
                    if (tripType ==
                        "round_trip"
                    ) {
                        json
                    } else {
                        null
                    },
                bookingClass = "business",
                capturedAt = now,
            ),
        )
        PriceHistoryQueries.create(PriceHistoryDto(flightId = id, amount = price, currency = "GBP", sourceUrl = shareLink, checkedAt = now))
        return id
    }

    // === Price validation tests ===

    @Test
    fun `flight price rejects non-URL link`() {
        createInv("tokyo")
        val id = createFlight("tokyo", "https://example.com/f1", 2500.0)
        val exitCode = execute("flight", "price", id, "--amount", "2200", "--link", "gf-ba-may1-22")
        assertEquals(1, exitCode)
    }

    @Test
    fun `flight price rejects fabricated google flights URL`() {
        createInv("tokyo")
        val id = createFlight("tokyo", "https://example.com/f1", 2500.0)
        val exitCode = execute("flight", "price", id, "--amount", "2200", "--link", "https://google.com/flights?ba-may1-22")
        assertEquals(1, exitCode)
    }

    @Test
    fun `flight price rejects google flights search URL`() {
        createInv("tokyo")
        val id = createFlight("tokyo", "https://example.com/f1", 2500.0)
        val exitCode =
            execute("flight", "price", id, "--amount", "2200", "--link", "https://www.google.com/travel/flights?q=Flights+to+TYO")
        assertEquals(1, exitCode)
    }

    @Test
    fun `flight price accepts google flights share link`() {
        createInv("tokyo")
        val id = createFlight("tokyo", "https://example.com/f1", 2500.0)
        val exitCode = execute("flight", "price", id, "--amount", "2200", "--link", "https://www.google.com/travel/flights/s/abc123")
        assertEquals(0, exitCode)
    }

    @Test
    fun `flight price accepts google flights booking link`() {
        createInv("tokyo")
        val id = createFlight("tokyo", "https://example.com/f1", 2500.0)
        val exitCode =
            execute("flight", "price", id, "--amount", "2200", "--link", "https://www.google.com/travel/flights/booking?tfs=CAIQAh")
        assertEquals(0, exitCode)
    }

    @Test
    fun `flight price rejects business RT below floor`() {
        createInv("tokyo")
        val id = createBusinessFlight("tokyo", "https://example.com/f1", 3000.0, "round_trip")
        val exitCode = execute("flight", "price", id, "--amount", "1200")
        assertEquals(1, exitCode)
    }

    @Test
    fun `flight price rejects business OW below floor`() {
        createInv("tokyo")
        val id = createBusinessFlight("tokyo", "https://example.com/f2", 2000.0, "one_way")
        val exitCode = execute("flight", "price", id, "--amount", "500")
        assertEquals(1, exitCode)
    }

    @Test
    fun `flight price warns on large deviation without force`() {
        createInv("tokyo")
        val id = createFlight("tokyo", "https://example.com/f1", 3000.0)
        val exitCode = execute("flight", "price", id, "--amount", "1000")
        assertEquals(1, exitCode)
    }

    @Test
    fun `flight price allows large deviation with force`() {
        createInv("tokyo")
        val id = createFlight("tokyo", "https://example.com/f1", 3000.0)
        val exitCode = execute("flight", "price", id, "--amount", "1000", "--force")
        assertEquals(0, exitCode)
    }

    @Test
    fun `flight price rejects negative amount`() {
        createInv("tokyo")
        val id = createFlight("tokyo", "https://example.com/f1", 2500.0)
        val exitCode = execute("flight", "price", id, "--amount", "-100")
        assertEquals(1, exitCode)
    }

    @Test
    fun `flight price rejects price above ceiling`() {
        createInv("tokyo")
        val id = createFlight("tokyo", "https://example.com/f1", 2500.0)
        val exitCode = execute("flight", "price", id, "--amount", "50000", "--force")
        assertEquals(1, exitCode)
    }
}
