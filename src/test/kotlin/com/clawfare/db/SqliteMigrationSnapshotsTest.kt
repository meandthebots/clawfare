package com.clawfare.db

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** All databases and values used by these tests are synthetic local fixtures. */
class SqliteMigrationSnapshotsTest {
    @TempDir
    lateinit var directory: Path

    private fun requirePosix() {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView::class.java) != null)
    }

    private fun backups(db: Path): List<Path> =
        Files.list(directory).use { entries ->
            entries
                .filter { it.fileName.toString().startsWith(db.fileName.toString() + ".backup-") }
                .filter { it.fileName.toString().endsWith(".sqlite") }
                .toList()
        }

    private fun assertNoStagingDirectories() {
        assertFalse(
            Files.list(directory).use { entries ->
                entries.anyMatch { it.fileName.toString().startsWith(".clawfare-snapshot-") }
            },
        )
    }

    @Test
    fun walBackedPreMigrationSnapshotIsOwnerOnlyAndRecoverable() {
        requirePosix()
        val db = directory.resolve("trip.db")
        ClawfareDatabase.connect(db.toString())

        // Keep a WAL-mode writer connection open with committed synthetic data
        // during migration; VACUUM INTO must include the committed WAL records.
        val url = "jdbc:sqlite:$db"
        DriverManager.getConnection(url).use { writer ->
            writer.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("CREATE TABLE recovery_probe (id TEXT PRIMARY KEY, note TEXT NOT NULL)")
                statement.execute("INSERT INTO recovery_probe VALUES ('fixture', 'committed-in-wal')")
                statement.execute("PRAGMA user_version=1")
            }
            assertTrue(Files.exists(Path.of("$db-wal")), "Expected active SQLite WAL during snapshot.")
            ClawfareDatabase.connect(db.toString())
        }

        val snapshot = backups(db).single()
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(snapshot),
        )
        assertNoStagingDirectories()

        // Independent SQLite connection can read a standalone recovery point,
        // without any of the original DB's WAL or shared-memory files.
        DriverManager.getConnection("jdbc:sqlite:$snapshot").use { restored ->
            restored.createStatement().use { statement ->
                statement.executeQuery("PRAGMA integrity_check").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("ok", rows.getString(1))
                }
                statement.executeQuery("PRAGMA user_version").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1, rows.getInt(1))
                }
                statement.executeQuery("SELECT note FROM recovery_probe WHERE id='fixture'").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("committed-in-wal", rows.getString(1))
                }
            }
        }
        DriverManager.getConnection(url).use { migrated ->
            migrated.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(2, rows.getInt(1))
                }
                statement.executeQuery("SELECT note FROM recovery_probe").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("committed-in-wal", rows.getString(1))
                }
            }
        }
    }

    @Test
    fun repeatedMigrationsNeverReplaceAnEarlierBackup() {
        requirePosix()
        val db = directory.resolve("repeated.db")
        ClawfareDatabase.connect(db.toString())
        val url = "jdbc:sqlite:$db"
        repeat(2) {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA user_version=1")
                }
            }
            ClawfareDatabase.connect(db.toString())
        }
        val snapshots = backups(db)
        assertEquals(2, snapshots.size)
        assertEquals(2, snapshots.map { it.fileName.toString() }.toSet().size)
        snapshots.forEach { path ->
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(path),
            )
        }
        assertNoStagingDirectories()
    }

    @Test
    fun invalidSourceCannotPublishIncompleteBackupOrLeaveTemporaryFiles() {
        requirePosix()
        val db = directory.resolve("invalid.db")
        Files.writeString(db, "not a SQLite database")
        assertFails {
            SqliteMigrationSnapshots.beforeMigration("jdbc:sqlite:$db", db.toFile())
        }
        assertTrue(backups(db).isEmpty())
        assertNoStagingDirectories()
    }
}
