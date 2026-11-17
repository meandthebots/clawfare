package com.clawfare.cli

import com.clawfare.db.ClawfareDatabase
import com.clawfare.db.FlightQueries
import com.clawfare.db.InvestigationQueries
import com.clawfare.db.PriceHistoryQueries
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hermetic command-level tests of the legacy `flight import-db` workflow.
 * The source and target SQLite files contain synthetic, never-bookable flights.
 * No provider credentials, network access, or production database paths are used.
 */
class FlightImportDbRegressionTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var target: Path

    private enum class SourceFlightColumn(
        val sqlName: String,
    ) {
        Legacy("source"),
        Renamed("flight_source"),
    }

    @BeforeEach
    fun setup() {
        target = directory.resolve("target.sqlite")
        ClawfareDatabase.connect(target.toString())
    }

    @AfterEach
    fun teardown() {
        ClawfareDatabase.dropTables()
    }

    private fun execute(vararg args: String): Int = CommandLine(ClawfareCommand()).execute("flight", "import-db", *args)

    /**
     * Deliberately omit newer optional columns from the *source* tables.
     * Legacy price_history.source_url and flights.flight_source are each
     * optional in old exports and must not be assumed to be present.
     */
    private fun source(
        hasSourceUrl: Boolean = false,
        sourceFlightColumn: SourceFlightColumn = SourceFlightColumn.Legacy,
    ): Path {
        val path = directory.resolve("source.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { sql ->
                sql.execute(
                    """
                    CREATE TABLE investigations (
                        slug TEXT PRIMARY KEY,
                        origin TEXT NOT NULL, destination TEXT NOT NULL,
                        depart_start TEXT NOT NULL, depart_end TEXT NOT NULL,
                        return_start TEXT, return_end TEXT,
                        cabin_class TEXT, max_stops INTEGER,
                        max_price REAL, depart_after TEXT, depart_before TEXT,
                        min_trip_days INTEGER, max_trip_days INTEGER,
                        must_include_date TEXT, max_layover_minutes INTEGER,
                        created_at TEXT, updated_at TEXT
                    )
                    """.trimIndent(),
                )
                sql.execute(
                    """
                    CREATE TABLE flights (
                        id TEXT PRIMARY KEY, investigation_slug TEXT NOT NULL,
                        share_link TEXT, ${sourceFlightColumn.sqlName} TEXT NOT NULL,
                        trip_type TEXT, ticket_structure TEXT,
                        price_amount REAL, price_currency TEXT,
                        price_market TEXT, price_checked_at TEXT,
                        origin TEXT, destination TEXT, outbound_json TEXT,
                        return_json TEXT, captured_at TEXT
                    )
                    """.trimIndent(),
                )
                sql.execute(
                    """
                    CREATE TABLE price_history (
                        id INTEGER PRIMARY KEY,
                        flight_id TEXT NOT NULL, amount REAL NOT NULL,
                        currency TEXT NOT NULL, checked_at TEXT NOT NULL,
                        source TEXT, price_market TEXT
                        ${if (hasSourceUrl) ", source_url TEXT" else ""}
                    )
                    """.trimIndent(),
                )
                for (slug in listOf("fixture-alpha", "fixture-beta")) {
                    connection
                        .prepareStatement(
                            """
                            INSERT INTO investigations (
                                slug, origin, destination, depart_start, depart_end,
                                cabin_class, max_stops, created_at, updated_at
                            ) VALUES (?, 'AAA', 'BBB', '2027-01-01', '2027-01-10',
                                      'business', 1, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')
                            """.trimIndent(),
                        ).use { insert ->
                            insert.setString(1, slug)
                            assertEquals(1, insert.executeUpdate())
                        }
                }
                for ((flightId, slug, amount) in listOf(
                    Triple("flight-alpha", "fixture-alpha", 120.0),
                    Triple("flight-beta", "fixture-beta", 220.0),
                )) {
                    connection
                        .prepareStatement(
                            """
                            INSERT INTO flights (
                                id, investigation_slug, share_link, ${sourceFlightColumn.sqlName},
                                trip_type, ticket_structure, price_amount,
                                price_currency, price_market, price_checked_at,
                                origin, destination, outbound_json, captured_at
                            ) VALUES (?, ?, ?, 'fixture-agent', 'one_way', 'single', ?,
                                      'EUR', 'FR', '2026-01-01T00:00:00Z',
                                      'AAA', 'BBB', '[]', '2026-01-01T00:00:00Z')
                            """.trimIndent(),
                        ).use { insert ->
                            insert.setString(1, flightId)
                            insert.setString(2, slug)
                            insert.setString(3, "https://example.invalid/booking/$flightId")
                            insert.setDouble(4, amount)
                            assertEquals(1, insert.executeUpdate())
                        }
                }
                sql.execute(
                    """
                    INSERT INTO price_history (
                        id, flight_id, amount, currency, checked_at, source, price_market
                    ) VALUES
                        (1, 'flight-alpha', 120.0, 'EUR', '2026-01-01T00:00:00Z', 'fixture-agent', 'FR'),
                        (2, 'flight-alpha', 119.5, 'EUR', '2026-01-02T00:00:00Z', 'fixture-agent', 'FR')
                    """.trimIndent(),
                )
                if (hasSourceUrl) {
                    sql.execute(
                        "UPDATE price_history SET source_url = 'https://example.invalid/direct-quote' WHERE id = 2",
                    )
                }
            }
        }
        return path
    }

    private fun addNewObservation(source: Path) {
        DriverManager.getConnection("jdbc:sqlite:$source").use { connection ->
            connection.createStatement().use { sql ->
                sql.execute(
                    """
                    INSERT INTO price_history (
                        id, flight_id, amount, currency, checked_at, source, price_market
                    ) VALUES (3, 'flight-alpha', 118.25, 'EUR',
                              '2026-01-03T00:00:00Z', 'fixture-agent', 'FR')
                    """.trimIndent(),
                )
            }
        }
    }

    @Test
    fun dryRunLeavesBothTargetAndSourceUntouched() {
        val source = source()
        assertEquals(0, execute(source.toString(), "--dry-run"))
        assertTrue(InvestigationQueries.listAll().isEmpty())
        assertTrue(FlightQueries.listByInvestigation("fixture-alpha").isEmpty())

        assertEquals(0, execute(source.toString(), "--investigation", "fixture-missing"))
        assertTrue(InvestigationQueries.listAll().isEmpty())
        DriverManager.getConnection("jdbc:sqlite:$source").use { connection ->
            connection.createStatement().use { sql ->
                sql.executeQuery("SELECT COUNT(*) FROM price_history").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(2, rows.getInt(1))
                }
            }
        }
    }

    @Test
    fun filteredImportPreservesHistoricalPricesAndDoesNotDuplicateOnReplay() {
        val source = source()
        val args = arrayOf(source.toString(), "--investigation", "fixture-alpha")
        assertEquals(0, execute(*args))

        assertNotNull(InvestigationQueries.getBySlug("fixture-alpha"))
        assertNull(InvestigationQueries.getBySlug("fixture-beta"))
        val flight = assertNotNull(FlightQueries.getById("flight-alpha"))
        assertEquals("fixture-agent", flight.source)
        assertEquals("AAA", flight.origin)
        assertEquals("BBB", flight.destination)
        assertEquals(120.0, flight.priceAmount)
        assertNull(FlightQueries.getById("flight-beta"))

        val prices = PriceHistoryQueries.getByFlightId(flight.id)
        assertEquals(listOf(120.0, 119.5), prices.map { it.amount })
        assertEquals(listOf("EUR", "EUR"), prices.map { it.currency })
        assertTrue(prices.all { it.source == "fixture-agent" && it.priceMarket == "FR" })
        assertTrue(prices.all { it.sourceUrl == "https://example.invalid/booking/flight-alpha" })

        assertEquals(0, execute(*args))
        assertEquals(2, PriceHistoryQueries.getByFlightId(flight.id).size)

        addNewObservation(source)
        assertEquals(0, execute(*args))
        assertEquals(
            listOf(120.0, 119.5, 118.25),
            PriceHistoryQueries.getByFlightId(flight.id).map { it.amount },
        )
        assertEquals(1, FlightQueries.listByInvestigation("fixture-alpha").size)
    }

    @Test
    fun skipPricesOnNewAndExistingFlightsAndFallbackWhenSourceHasNoHistory() {
        val source = source()
        assertEquals(0, execute(source.toString(), "--skip-prices"))
        assertEquals(2, InvestigationQueries.listAll().size)
        assertNotNull(FlightQueries.getById("flight-alpha"))
        assertNotNull(FlightQueries.getById("flight-beta"))
        assertTrue(PriceHistoryQueries.getByFlightId("flight-alpha").isEmpty())
        assertTrue(PriceHistoryQueries.getByFlightId("flight-beta").isEmpty())

        // A subsequent import fills missing history for existing flights.
        // No upstream observation for beta means beta stays history-free.
        assertEquals(0, execute(source.toString()))
        assertEquals(2, PriceHistoryQueries.getByFlightId("flight-alpha").size)
        assertTrue(PriceHistoryQueries.getByFlightId("flight-beta").isEmpty())

        assertEquals(0, execute(source.toString(), "--skip-prices"))
        assertEquals(2, PriceHistoryQueries.getByFlightId("flight-alpha").size)
    }

    @Test
    fun missingSourceHistoryUsesLegacyFlightPriceOnlyOnInitialImport() {
        val source = source()
        assertEquals(0, execute(source.toString()))
        val betaPrices = PriceHistoryQueries.getByFlightId("flight-beta")
        assertEquals(1, betaPrices.size)
        assertEquals(220.0, betaPrices.single().amount)
        assertEquals("EUR", betaPrices.single().currency)
        assertEquals("FR", betaPrices.single().priceMarket)
        assertEquals(
            "https://example.invalid/booking/flight-beta",
            betaPrices.single().sourceUrl,
        )
        assertEquals(0, execute(source.toString()))
        assertEquals(1, PriceHistoryQueries.getByFlightId("flight-beta").size)
    }

    @Test
    fun renamedFlightSourceAndExplicitPriceUrlAreParsedWithoutLosingFallback() {
        val source =
            source(
                hasSourceUrl = true,
                sourceFlightColumn = SourceFlightColumn.Renamed,
            )
        assertEquals(0, execute(source.toString(), "--investigation", "fixture-alpha"))
        val flight = assertNotNull(FlightQueries.getById("flight-alpha"))
        assertEquals("fixture-agent", flight.source)
        val prices = PriceHistoryQueries.getByFlightId(flight.id)
        assertEquals(2, prices.size)
        assertEquals("https://example.invalid/booking/flight-alpha", prices[0].sourceUrl)
        assertEquals("https://example.invalid/direct-quote", prices[1].sourceUrl)
    }

    @Test
    fun missingSourceDatabaseFailsBeforeWritingAnyRows() {
        val source = directory.resolve("does-not-exist.sqlite")
        assertEquals(1, execute(source.toString()))
        assertTrue(InvestigationQueries.listAll().isEmpty())
        assertTrue(FlightQueries.listByInvestigation("fixture-alpha").isEmpty())
    }
    @Test
    fun dryRunAgainstExistingFlightLeavesNewPriceUnwritten() {
        val source = source()
        assertEquals(0, execute(source.toString(), "--investigation", "fixture-alpha"))
        addNewObservation(source)

        assertEquals(0, execute(source.toString(), "--investigation", "fixture-alpha", "--dry-run"))
        assertEquals(2, PriceHistoryQueries.getByFlightId("flight-alpha").size)
        assertEquals(1, FlightQueries.listByInvestigation("fixture-alpha").size)

        assertEquals(0, execute(source.toString(), "--investigation", "fixture-alpha"))
        assertEquals(3, PriceHistoryQueries.getByFlightId("flight-alpha").size)
    }

    @Test
    fun zeroPricedLegacyFlightWithoutHistoryDoesNotFabricateAQuote() {
        val source = source()
        DriverManager.getConnection("jdbc:sqlite:$source").use { connection ->
            connection.createStatement().use { sql ->
                sql.execute("DELETE FROM price_history WHERE flight_id = 'flight-alpha'")
                sql.execute("UPDATE flights SET price_amount = 0 WHERE id = 'flight-alpha'")
            }
        }

        assertEquals(0, execute(source.toString(), "--investigation", "fixture-alpha"))
        assertNotNull(FlightQueries.getById("flight-alpha"))
        assertTrue(PriceHistoryQueries.getByFlightId("flight-alpha").isEmpty())
    }

    @Test
    fun duplicateBookingLinkWithDifferentFlightIdDoesNotImportAnotherFlight() {
        val source = source()
        assertEquals(0, execute(source.toString(), "--investigation", "fixture-alpha"))
        DriverManager.getConnection("jdbc:sqlite:$source").use { connection ->
            connection.createStatement().use { sql ->
                sql.execute(
                    """
                    INSERT INTO flights (
                        id, investigation_slug, share_link, source,
                        trip_type, ticket_structure, price_amount, price_currency,
                        price_market, price_checked_at, origin, destination,
                        outbound_json, captured_at
                    )
                    SELECT 'flight-alias', investigation_slug, share_link, source,
                           trip_type, ticket_structure, price_amount, price_currency,
                           price_market, price_checked_at, origin, destination,
                           outbound_json, captured_at
                    FROM flights WHERE id = 'flight-alpha'
                    """.trimIndent(),
                )
            }
        }

        assertEquals(0, execute(source.toString(), "--investigation", "fixture-alpha"))
        assertNull(FlightQueries.getById("flight-alias"))
        assertEquals(1, FlightQueries.listByInvestigation("fixture-alpha").size)
        assertEquals(2, PriceHistoryQueries.getByFlightId("flight-alpha").size)
    }

    @Test
    fun optionalLegacyFieldsAreReadWhenPresentInsteadOfDefaulted() {
        val source =
            source(
                hasSourceUrl = true,
                sourceFlightColumn = SourceFlightColumn.Renamed,
            )
        DriverManager.getConnection("jdbc:sqlite:$source").use { connection ->
            connection.createStatement().use { sql ->
                sql.execute("ALTER TABLE flights ADD COLUMN cabin_mixed INTEGER")
                sql.execute("ALTER TABLE flights ADD COLUMN stale INTEGER")
                sql.execute("ALTER TABLE flights ADD COLUMN booking_class TEXT")
                sql.execute("ALTER TABLE flights ADD COLUMN fare_brand TEXT")
                sql.execute("ALTER TABLE flights ADD COLUMN notes TEXT")
                sql.execute("ALTER TABLE flights ADD COLUMN tags TEXT")
                sql.execute(
                    """
                    UPDATE flights SET cabin_mixed = 1, stale = 1,
                        booking_class = 'D', fare_brand = 'Synthetic Flex',
                        notes = 'fixture note', tags = 'fixture tag'
                    WHERE id = 'flight-alpha'
                    """.trimIndent(),
                )
                sql.execute("ALTER TABLE price_history ADD COLUMN price_source TEXT")
                sql.execute("UPDATE price_history SET price_source = 'price-source-field'")
            }
        }
        assertEquals(0, execute(source.toString(), "--investigation", "fixture-alpha"))

        val flight = assertNotNull(FlightQueries.getById("flight-alpha"))
        assertTrue(flight.cabinMixed)
        assertTrue(flight.stale)
        assertEquals("D", flight.bookingClass)
        assertEquals("Synthetic Flex", flight.fareBrand)
        assertEquals("fixture note", flight.notes)
        assertEquals("fixture tag", flight.tags)
        assertTrue(PriceHistoryQueries.getByFlightId(flight.id).all { it.source == "price-source-field" })
    }

}
