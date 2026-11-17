package com.clawfare.db

import com.clawfare.cli.ClawfareCommand
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** All fixtures are synthetic. No live provider tokens or real trip data. */
class EncryptedSqliteBackupsTest {
    @TempDir
    lateinit var directory: Path

    private val passphrase = "synthetic-archive-password-2026".toCharArray()

    private fun requirePosix() {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView::class.java) != null)
    }

    private fun createDatabase(): Path {
        val source = directory.resolve("source.sqlite")
        ClawfareDatabase.connect(source.toString())
        return source
    }

    private fun assertNoStaging() {
        assertFalse(
            Files.list(directory).use { entries ->
                entries.anyMatch { it.fileName.toString().startsWith(".clawfare-archive-") }
            },
        )
    }

    private fun verifyRestoredData(restored: Path) {
        DriverManager.getConnection("jdbc:sqlite:$restored").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA integrity_check").use { result ->
                    assertTrue(result.next())
                    assertEquals("ok", result.getString(1))
                }
                statement.executeQuery("SELECT note FROM recovery_probe WHERE id='trip'").use { result ->
                    assertTrue(result.next())
                    assertEquals("WAL_SECRET_SENTINEL", result.getString(1))
                }
            }
        }
    }

    @Test
    fun encryptedBackupIncludesCommittedWalAndRestoresIndependentDatabase() {
        requirePosix()
        val source = createDatabase()
        val archive = directory.resolve("backup.clwdb")
        val restored = directory.resolve("recovered.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$source").use { writer ->
            writer.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("CREATE TABLE recovery_probe (id TEXT PRIMARY KEY, note TEXT NOT NULL)")
                statement.execute("INSERT INTO recovery_probe VALUES ('trip', 'WAL_SECRET_SENTINEL')")
            }
            assertTrue(Files.exists(Path.of("$source-wal")))
            EncryptedSqliteBackups.backup(source, archive, passphrase)
        }
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(archive),
        )
        assertFalse(Files.readAllBytes(archive).toString(Charsets.ISO_8859_1).contains("WAL_SECRET_SENTINEL"))
        EncryptedSqliteBackups.restore(archive, restored, passphrase)
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(restored),
        )
        verifyRestoredData(restored)
        assertNoStaging()
    }

    @Test
    fun wrongPassphraseCannotPublishRecoveryAndOriginalArchiveSurvives() {
        requirePosix()
        val source = createDatabase()
        val archive = directory.resolve("encrypted.clwdb")
        EncryptedSqliteBackups.backup(source, archive, passphrase)
        val original = Files.readAllBytes(archive)
        val restored = directory.resolve("wrong-password.sqlite")

        assertFails {
            EncryptedSqliteBackups.restore(archive, restored, "wrong-but-sufficiently-long-key".toCharArray())
        }
        assertFalse(Files.exists(restored))
        assertTrue(original.contentEquals(Files.readAllBytes(archive)))
        assertNoStaging()
    }

    @Test
    fun ciphertextTamperingFailsAuthenticationAndLeavesNoOutput() {
        requirePosix()
        val source = createDatabase()
        val archive = directory.resolve("encrypted.clwdb")
        EncryptedSqliteBackups.backup(source, archive, passphrase)
        val corrupted = directory.resolve("tampered.clwdb")
        val bytes = Files.readAllBytes(archive)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        Files.write(corrupted, bytes)

        val restored = directory.resolve("tampered.sqlite")
        assertFails { EncryptedSqliteBackups.restore(corrupted, restored, passphrase) }
        assertFalse(Files.exists(restored))
        assertNoStaging()
    }

    @Test
    fun existingArchivesAndRecoveryFilesAreNeverOverwritten() {
        requirePosix()
        val source = createDatabase()
        val archive = directory.resolve("existing.clwdb")
        Files.writeString(archive, "existing-archive")
        assertFails { EncryptedSqliteBackups.backup(source, archive, passphrase) }
        assertEquals("existing-archive", Files.readString(archive))

        val freshArchive = directory.resolve("fresh.clwdb")
        EncryptedSqliteBackups.backup(source, freshArchive, passphrase)
        val destination = directory.resolve("existing.sqlite")
        Files.writeString(destination, "existing-database")
        assertFails { EncryptedSqliteBackups.restore(freshArchive, destination, passphrase) }
        assertEquals("existing-database", Files.readString(destination))

        val sidecarDestination = directory.resolve("uncreated.sqlite")
        Files.writeString(directory.resolve("uncreated.sqlite-wal"), "protected-wal")
        assertFails { EncryptedSqliteBackups.restore(freshArchive, sidecarDestination, passphrase) }
        assertFalse(Files.exists(sidecarDestination))
        assertNoStaging()
    }

    @Test
    fun truncationOrAppendedBytesInvalidateTheArchive() {
        requirePosix()
        val source = createDatabase()
        val archive = directory.resolve("complete.clwdb")
        EncryptedSqliteBackups.backup(source, archive, passphrase)
        val bytes = Files.readAllBytes(archive)
        val truncated = directory.resolve("truncated.clwdb")
        Files.write(truncated, bytes.copyOf(bytes.size - 1))
        val truncatedDestination = directory.resolve("truncated.sqlite")
        assertFails { EncryptedSqliteBackups.restore(truncated, truncatedDestination, passphrase) }
        assertFalse(Files.exists(truncatedDestination))

        val appended = directory.resolve("extra.clwdb")
        Files.write(appended, bytes + byteArrayOf(42))
        val appendedDestination = directory.resolve("extra.sqlite")
        assertFails { EncryptedSqliteBackups.restore(appended, appendedDestination, passphrase) }
        assertFalse(Files.exists(appendedDestination))
        assertNoStaging()
    }

    @Test
    fun largeDatabaseCrossesMultipleAuthenticatedRecordBoundaries() {
        requirePosix()
        val source = createDatabase()
        val payload = "MULTI_RECORD_SENTINEL".repeat(20_000)
        DriverManager.getConnection("jdbc:sqlite:$source").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE big_fixture (payload TEXT NOT NULL)")
            }
            connection.prepareStatement("INSERT INTO big_fixture VALUES (?)").use { statement ->
                statement.setString(1, payload)
                statement.executeUpdate()
            }
        }
        val archive = directory.resolve("multirecord.clwdb")
        val recovered = directory.resolve("multirecord.sqlite")
        EncryptedSqliteBackups.backup(source, archive, passphrase)
        assertTrue(Files.size(archive) > 64L * 1024L)
        EncryptedSqliteBackups.restore(archive, recovered, passphrase)
        DriverManager.getConnection("jdbc:sqlite:$recovered").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT payload FROM big_fixture").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(payload, rows.getString(1))
                }
            }
        }
        assertNoStaging()
    }

    @Test
    fun symlinksAndInsecurePassphraseFilesAreRefused() {
        requirePosix()
        val source = createDatabase()
        val link = directory.resolve("source-link.sqlite")
        Files.createSymbolicLink(link, source.fileName)
        assertFails {
            EncryptedSqliteBackups.backup(link, directory.resolve("link.clwdb"), passphrase)
        }

        val secret = directory.resolve("secret.txt")
        Files.writeString(secret, "synthetic-password-at-least-sixteen")
        Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-r--r--"))
        assertFails { EncryptedSqliteBackups.passphraseFromFile(secret) }
        Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"))
        val chars = EncryptedSqliteBackups.passphraseFromFile(secret)
        assertEquals("synthetic-password-at-least-sixteen", String(chars))
        chars.fill('\u0000')
    }

    @Test
    fun cliCanCreateAndRestoreEncryptedDatabaseWithoutInitialisingIt() {
        requirePosix()
        val source = createDatabase()
        val secret = directory.resolve("password.txt")
        Files.writeString(secret, "synthetic-password-file-secret-2026\n")
        Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"))
        val archive = directory.resolve("cli-backup.clwdb")
        val recovered = directory.resolve("cli-recovery.sqlite")

        assertEquals(
            0,
            CommandLine(ClawfareCommand())
                .execute(
                    "--db",
                    source.toString(),
                    "db",
                    "backup",
                    "--out",
                    archive.toString(),
                    "--passphrase-file",
                    secret.toString(),
                ),
        )
        assertEquals(
            0,
            CommandLine(ClawfareCommand())
                .execute(
                    "db",
                    "restore",
                    "--from",
                    archive.toString(),
                    "--into",
                    recovered.toString(),
                    "--passphrase-file",
                    secret.toString(),
                ),
        )
        assertTrue(Files.isRegularFile(recovered))
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(recovered),
        )
        assertNoStaging()
    }
}
