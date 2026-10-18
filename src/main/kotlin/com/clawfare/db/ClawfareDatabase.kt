package com.clawfare.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.sql.Connection

/**
 * Database connection and schema management for Clawfare.
 */
object ClawfareDatabase {
    private var initialized = false
    private var currentDb: Database? = null

    /**
     * Default database path: ~/.clawfare/data.db
     */
    val defaultPath: String by lazy {
        val home = System.getProperty("user.home")
        "$home/.clawfare/data.db"
    }

    /**
     * Connect to a SQLite database at the given path.
     * Creates parent directories if needed.
     *
     * @param path Path to SQLite file, or ":memory:" for in-memory database
     * @param createSchema Whether to create tables if they don't exist
     */
    fun connect(
        path: String? = defaultPath,
        createSchema: Boolean = true,
    ): Database {
        val jdbcUrl =
            when {
                path == null || path == ":memory:" -> "jdbc:sqlite::memory:"
                else -> {
                    // Regular file path - ensure parent directory exists
                    val file = File(path)
                    file.parentFile?.mkdirs()
                    "jdbc:sqlite:$path"
                }
            }

        val db =
            Database.connect(
                url = jdbcUrl,
                driver = "org.sqlite.JDBC",
            )

        // Set transaction isolation level for SQLite
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        currentDb = db

        if (createSchema) {
            createTables()
            runMigrations()
        }

        initialized = true
        return db
    }

    /**
     * Connect to an in-memory SQLite database.
     * Uses a temp file to ensure tables persist across connections.
     * Useful for testing.
     */
    fun connectInMemory(createSchema: Boolean = true): Database {
        // Use a temp file for consistent connection behavior in tests
        val tempFile = File.createTempFile("clawfare-test-", ".db")
        tempFile.deleteOnExit()

        val db =
            Database.connect(
                url = "jdbc:sqlite:${tempFile.absolutePath}",
                driver = "org.sqlite.JDBC",
            )

        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
        currentDb = db

        if (createSchema) {
            createTables()
            runMigrations()
        }

        initialized = true
        return db
    }

    /**
     * Create all tables if they don't exist.
     */
    fun createTables() {
        transaction {
            SchemaUtils.create(
                Investigations,
                Flights,
                PriceHistory,
                SellerOffers,
                ProviderCache,
                FlightProviderTokens,
            )
        }
    }

    /**
     * Run schema migrations to add new columns safely.
     * This handles the case where an older database is missing newer columns.
     */
    fun runMigrations() {
        transaction {
            val conn = TransactionManager.current().connection.connection as Connection
            val stmt = conn.createStatement()

            // Get existing columns for each table
            val priceHistoryCols = getTableColumns(conn, "price_history")
            val flightsCols = getTableColumns(conn, "flights")

            // Migration: Add source_url to price_history if missing
            if (!priceHistoryCols.contains("source_url")) {
                stmt.executeUpdate("ALTER TABLE price_history ADD COLUMN source_url TEXT DEFAULT ''")
                // Older databases stored the booking URL on the flight. Copy it only when that column exists.
                if (flightsCols.contains("share_link")) {
                    stmt.executeUpdate(
                        """
                        UPDATE price_history
                        SET source_url = (
                            SELECT share_link FROM flights WHERE flights.id = price_history.flight_id
                        )
                        WHERE source_url IS NULL OR source_url = ''
                        """.trimIndent(),
                    )
                }
            }

            // Migration: Make flights.share_link nullable if needed (already nullable in new schema)
            // SQLite doesn't support ALTER COLUMN, so we skip this - new rows just use NULL

            // Migration: Add stale column to flights if missing
            if (!flightsCols.contains("stale")) {
                stmt.executeUpdate("ALTER TABLE flights ADD COLUMN stale INTEGER DEFAULT 0")
            }

            // Migration: Add price fields to flights if missing (for backward compat)
            if (!flightsCols.contains("price_amount")) {
                stmt.executeUpdate("ALTER TABLE flights ADD COLUMN price_amount DOUBLE PRECISION DEFAULT 0")
            }
            if (!flightsCols.contains("price_currency")) {
                stmt.executeUpdate("ALTER TABLE flights ADD COLUMN price_currency TEXT DEFAULT 'GBP'")
            }
            if (!flightsCols.contains("price_market")) {
                stmt.executeUpdate("ALTER TABLE flights ADD COLUMN price_market TEXT DEFAULT 'UK'")
            }
            if (!flightsCols.contains("price_checked_at")) {
                stmt.executeUpdate("ALTER TABLE flights ADD COLUMN price_checked_at TEXT DEFAULT ''")
            }

            // Migration: Add new investigation columns if missing
            val invCols = getTableColumns(conn, "investigations")
            if (!invCols.contains("max_price")) {
                stmt.executeUpdate("ALTER TABLE investigations ADD COLUMN max_price DOUBLE PRECISION")
            }
            if (!invCols.contains("depart_after")) {
                stmt.executeUpdate("ALTER TABLE investigations ADD COLUMN depart_after TEXT")
            }
            if (!invCols.contains("depart_before")) {
                stmt.executeUpdate("ALTER TABLE investigations ADD COLUMN depart_before TEXT")
            }
            if (!invCols.contains("excluded_airlines")) {
                stmt.executeUpdate("ALTER TABLE investigations ADD COLUMN excluded_airlines TEXT")
            }
            if (!invCols.contains("included_airlines")) {
                stmt.executeUpdate("ALTER TABLE investigations ADD COLUMN included_airlines TEXT")
            }
            addColumnIfMissing(stmt, invCols, "investigations", "origins", "TEXT")
            addColumnIfMissing(stmt, invCols, "investigations", "destinations", "TEXT")
            addColumnIfMissing(stmt, invCols, "investigations", "checked_bags", "INTEGER DEFAULT 0")
            addColumnIfMissing(stmt, invCols, "investigations", "passengers", "INTEGER DEFAULT 1")
            addColumnIfMissing(stmt, invCols, "investigations", "currency", "TEXT DEFAULT 'GBP'")
            addColumnIfMissing(stmt, invCols, "investigations", "nights", "INTEGER")
            addColumnIfMissing(stmt, invCols, "investigations", "scenario", "TEXT")
            addColumnIfMissing(stmt, invCols, "investigations", "query_json", "TEXT")
            addColumnIfMissing(stmt, invCols, "investigations", "presentation_json", "TEXT")

            addColumnIfMissing(stmt, flightsCols, "flights", "checked_bags", "INTEGER DEFAULT 0")
            addColumnIfMissing(stmt, flightsCols, "flights", "passengers", "INTEGER DEFAULT 1")
            addColumnIfMissing(stmt, flightsCols, "flights", "source_label", "TEXT")
            addColumnIfMissing(stmt, flightsCols, "flights", "live_price", "INTEGER DEFAULT 0")
            addColumnIfMissing(stmt, flightsCols, "flights", "display_id", "TEXT")
            addColumnIfMissing(stmt, flightsCols, "flights", "planned_return_date", "TEXT")
            addColumnIfMissing(stmt, flightsCols, "flights", "links_json", "TEXT")
            addColumnIfMissing(stmt, flightsCols, "flights", "cabin_class", "TEXT")

            stmt.close()
        }
    }

    /**
     * Get list of column names for a table.
     */
    private fun addColumnIfMissing(
        stmt: java.sql.Statement,
        columns: Set<String>,
        table: String,
        column: String,
        type: String,
    ) {
        if (!columns.contains(column)) {
            stmt.executeUpdate("ALTER TABLE $table ADD COLUMN $column $type")
        }
    }

    private fun getTableColumns(
        conn: Connection,
        tableName: String,
    ): Set<String> {
        val columns = mutableSetOf<String>()
        val rs = conn.createStatement().executeQuery("PRAGMA table_info($tableName)")
        while (rs.next()) {
            columns.add(rs.getString("name").lowercase())
        }
        rs.close()
        return columns
    }

    /**
     * Drop all tables. Use with caution!
     */
    fun dropTables() {
        transaction {
            SchemaUtils.drop(
                FlightProviderTokens,
                SellerOffers,
                ProviderCache,
                PriceHistory,
                Flights,
                Investigations,
            )
        }
    }

    /**
     * Check if the database has been initialized.
     */
    fun isInitialized(): Boolean = initialized
}
