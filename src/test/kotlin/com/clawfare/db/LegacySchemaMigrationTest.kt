package com.clawfare.db

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies a complete synthetic pre-v2 database, not just snapshot permissions.
 * No real travel data, credentials or external provider endpoints are involved.
 */
class LegacySchemaMigrationTest {
    @TempDir
    lateinit var directory: Path

    private fun requirePosix() {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView::class.java) != null)
    }

    private fun connection(path: Path): Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    private fun schemaVersion(path: Path): Int =
        connection(path).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA user_version").use { rs ->
                    assertTrue(rs.next())
                    rs.getInt(1)
                }
            }
        }

    private fun columns(
        path: Path,
        table: String,
    ): Set<String> =
        connection(path).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA table_info($table)").use { rs ->
                    buildSet {
                        while (rs.next()) {
                            add(rs.getString("name"))
                        }
                    }
                }
            }
        }

    private fun snapshots(path: Path): List<Path> =
        Files.list(directory).use { stream ->
            stream
                .filter { it.fileName.toString().startsWith(path.fileName.toString() + ".backup-") }
                .filter { it.fileName.toString().endsWith(".sqlite") }
                .toList()
        }

    private fun createLegacyDatabase(
        path: Path,
        version: Int,
        failBackfill: Boolean = false,
    ) {
        require(version in 0..1) { "Fixture must model pre-v2 schema." }
        connection(path).use { conn ->
            conn.createStatement().use { stmt ->
                // Deliberately omit the fields that runMigrations is expected to
                // introduce. Keep the earlier, non-migrated required fields.
                stmt.execute(
                    """
                    CREATE TABLE investigations (
                        slug TEXT PRIMARY KEY,
                        origin TEXT NOT NULL,
                        destination TEXT NOT NULL,
                        depart_start TEXT NOT NULL,
                        depart_end TEXT NOT NULL,
                        return_start TEXT,
                        return_end TEXT,
                        cabin_class TEXT NOT NULL,
                        max_stops INTEGER NOT NULL,
                        min_trip_days INTEGER,
                        max_trip_days INTEGER,
                        must_include_date TEXT,
                        max_layover_minutes INTEGER,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE flights (
                        id TEXT PRIMARY KEY,
                        investigation_slug TEXT NOT NULL,
                        share_link TEXT,
                        source TEXT NOT NULL,
                        trip_type TEXT NOT NULL,
                        ticket_structure TEXT NOT NULL,
                        origin TEXT NOT NULL,
                        destination TEXT NOT NULL,
                        outbound_json TEXT NOT NULL,
                        return_json TEXT,
                        booking_class TEXT,
                        cabin_mixed INTEGER DEFAULT 0,
                        aircraft_type TEXT,
                        fare_brand TEXT,
                        disqualified TEXT,
                        notes TEXT,
                        tags TEXT,
                        captured_at TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE price_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        flight_id TEXT NOT NULL,
                        amount DOUBLE PRECISION NOT NULL,
                        currency TEXT NOT NULL,
                        checked_at TEXT NOT NULL,
                        source TEXT NOT NULL,
                        price_market TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    INSERT INTO investigations (
                        slug, origin, destination, depart_start, depart_end,
                        cabin_class, max_stops, created_at, updated_at
                    ) VALUES (
                        'synthetic-legacy', 'AAA', 'BBB', '2027-01-01', '2027-01-07',
                        'business', 1, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    INSERT INTO flights (
                        id, investigation_slug, share_link, source, trip_type,
                        ticket_structure, origin, destination, outbound_json, captured_at
                    ) VALUES (
                        'synthetic-flight', 'synthetic-legacy', 'https://example.invalid/offer-1',
                        'fixture', 'round-trip', 'single', 'AAA', 'BBB', '[]',
                        '2026-01-02T00:00:00Z'
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    INSERT INTO price_history (flight_id, amount, currency, checked_at, source, price_market)
                    VALUES ('synthetic-flight', 123.45, 'EUR', '2026-01-02T00:00:00Z', 'fixture', 'FR')
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    INSERT INTO price_history (flight_id, amount, currency, checked_at, source, price_market)
                    VALUES ('synthetic-flight', 135.79, 'EUR', '2026-01-03T00:00:00Z', 'fixture', 'FR')
                    """.trimIndent(),
                )
                if (failBackfill) {
                    // Failure occurs AFTER runMigrations adds source_url, during
                    // the UPDATE that copies legacy flights.share_link.
                    stmt.execute(
                        """
                        CREATE TRIGGER force_synthetic_migration_failure
                        BEFORE UPDATE OF source_url ON price_history
                        BEGIN
                          SELECT RAISE(ABORT, 'synthetic backfill failure');
                        END
                        """.trimIndent(),
                    )
                }
                stmt.execute("PRAGMA user_version = $version")
            }
        }
    }

    private fun assertFixtureRows(path: Path) {
        connection(path).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT origin, destination, cabin_class FROM investigations").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("AAA", rs.getString(1))
                    assertEquals("BBB", rs.getString(2))
                    assertEquals("business", rs.getString(3))
                    assertFalse(rs.next())
                }
                stmt.executeQuery("SELECT share_link, outbound_json FROM flights").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("https://example.invalid/offer-1", rs.getString(1))
                    assertEquals("[]", rs.getString(2))
                    assertFalse(rs.next())
                }
                stmt.executeQuery("SELECT amount, currency, source, price_market FROM price_history ORDER BY id").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(123.45, rs.getDouble(1))
                    assertEquals("EUR", rs.getString(2))
                    assertEquals("fixture", rs.getString(3))
                    assertEquals("FR", rs.getString(4))
                    assertTrue(rs.next())
                    assertEquals(135.79, rs.getDouble(1))
                    assertEquals("EUR", rs.getString(2))
                    assertFalse(rs.next())
                }
            }
        }
    }

    @Test
    fun versionZeroAndOneUpgradePreservesAllRowsAndCreatesRecoverableSnapshots() {
        requirePosix()
        for (version in 0..1) {
            val db = directory.resolve("legacy-v$version.db")
            createLegacyDatabase(db, version)
            assertFalse("source_url" in columns(db, "price_history"))
            assertFalse("refund_evidence_json" in columns(db, "flights"))
            assertEquals(version, schemaVersion(db))

            ClawfareDatabase.connect(db.toString())
            assertEquals(2, schemaVersion(db))
            assertFixtureRows(db)
            assertTrue("source_url" in columns(db, "price_history"))
            assertTrue("refund_evidence_json" in columns(db, "flights"))
            assertTrue("passengers" in columns(db, "investigations"))
            assertTrue("stale" in columns(db, "flights"))
            connection(db).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT source_url FROM price_history ORDER BY id").use { rs ->
                        repeat(2) {
                            assertTrue(rs.next())
                            assertEquals("https://example.invalid/offer-1", rs.getString(1))
                        }
                        assertFalse(rs.next())
                    }
                }
            }
            assertEquals("AAA", assertNotNull(InvestigationQueries.getBySlug("synthetic-legacy")).origin)
            assertEquals("synthetic-flight", assertNotNull(FlightQueries.getById("synthetic-flight")).id)
            val prices = PriceHistoryQueries.getByFlightId("synthetic-flight")
            assertEquals(listOf(123.45, 135.79), prices.map { it.amount })
            assertTrue(prices.all { it.sourceUrl == "https://example.invalid/offer-1" })

            val backup = snapshots(db).single()
            assertEquals(version, schemaVersion(backup))
            assertFixtureRows(backup)
            assertFalse("source_url" in columns(backup, "price_history"))
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(backup),
            )

            ClawfareDatabase.connect(db.toString())
            assertEquals(2, schemaVersion(db))
            assertEquals(1, snapshots(db).size, "Reopening a v2 database must not create another backup.")
            assertFixtureRows(db)
        }
    }

    @Test
    fun failedMigrationRollsBackSchemaAndKeepsRecoverableOriginalData() {
        requirePosix()
        val db = directory.resolve("failure.db")
        createLegacyDatabase(db, version = 1, failBackfill = true)

        assertFails { ClawfareDatabase.connect(db.toString()) }

        // The failure must not advance the version or expose half-migrated data.
        assertEquals(1, schemaVersion(db))
        assertFixtureRows(db)
        assertFalse("source_url" in columns(db, "price_history"))
        val backup = snapshots(db).single()
        assertEquals(1, schemaVersion(backup))
        assertFixtureRows(backup)
        assertFalse("source_url" in columns(backup, "price_history"))

        // Rehearse manual recovery from the *independent* published snapshot,
        // without overwriting the original or needing its WAL sidecars.
        val restored = directory.resolve("recovered-from-snapshot.db")
        Files.copy(backup, restored, StandardCopyOption.COPY_ATTRIBUTES)
        connection(restored).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TRIGGER force_synthetic_migration_failure")
            }
        }
        ClawfareDatabase.connect(restored.toString())
        assertEquals(2, schemaVersion(restored))
        assertFixtureRows(restored)
        assertEquals(2, PriceHistoryQueries.getByFlightId("synthetic-flight").size)
    }
}
