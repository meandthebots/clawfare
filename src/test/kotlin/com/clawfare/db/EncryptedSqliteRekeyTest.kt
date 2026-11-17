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

/** All data and key material are synthetic and confined to temporary test directories. */
class EncryptedSqliteRekeyTest {
    @TempDir
    lateinit var directory: Path

    private val oldPassphrase = "synthetic-old-archive-password-2026".toCharArray()
    private val newPassphrase = "synthetic-new-archive-password-2027".toCharArray()
    private val privatePermissions = PosixFilePermissions.fromString("rw-------")

    private fun requirePosix() {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView::class.java) != null)
    }

    private fun sourceDatabase(): Path {
        val path = directory.resolve("fixture.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE recovery_fixture (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
                statement.execute("INSERT INTO recovery_fixture (id, value) VALUES (1, 'synthetic-record')")
                statement.execute("PRAGMA user_version = 1")
            }
        }
        return path
    }

    private fun originalArchive(): Path {
        val source = sourceDatabase()
        val archive = directory.resolve("original.clwdb")
        EncryptedSqliteBackups.backup(source, archive, oldPassphrase)
        return archive
    }

    private fun assertNoStaging() {
        assertFalse(
            Files.list(directory).use { paths ->
                paths.anyMatch { it.fileName.toString().startsWith(".clawfare-archive-") }
            },
        )
    }

    private fun verifyRestoredDatabase(database: Path) {
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA integrity_check").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("ok", rows.getString(1))
                }
                statement.executeQuery("PRAGMA user_version").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1, rows.getInt(1))
                }
                statement.executeQuery("SELECT value FROM recovery_fixture WHERE id = 1").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("synthetic-record", rows.getString(1))
                }
            }
        }
    }

    @Test
    fun rekeyKeepsOriginalAndPublishesPrivateArchiveThatRequiresNewPassphrase() {
        requirePosix()
        val original = originalArchive()
        val originalBytes = Files.readAllBytes(original)
        val rotated = directory.resolve("rotated.clwdb")

        EncryptedSqliteBackups.rekey(original, rotated, oldPassphrase, newPassphrase)

        assertTrue(originalBytes.contentEquals(Files.readAllBytes(original)))
        assertFalse(originalBytes.contentEquals(Files.readAllBytes(rotated)))
        assertEquals(privatePermissions, Files.getPosixFilePermissions(rotated))
        val recoveredOld = directory.resolve("recovered-old.sqlite")
        val recoveredNew = directory.resolve("recovered-new.sqlite")
        EncryptedSqliteBackups.restore(original, recoveredOld, oldPassphrase)
        EncryptedSqliteBackups.restore(rotated, recoveredNew, newPassphrase)
        verifyRestoredDatabase(recoveredOld)
        verifyRestoredDatabase(recoveredNew)
        assertFails {
            EncryptedSqliteBackups.restore(rotated, directory.resolve("wrong-new.sqlite"), oldPassphrase)
        }
        assertFails {
            EncryptedSqliteBackups.restore(original, directory.resolve("wrong-old.sqlite"), newPassphrase)
        }
        assertFalse(Files.exists(directory.resolve("wrong-new.sqlite")))
        assertFalse(Files.exists(directory.resolve("wrong-old.sqlite")))
        assertNoStaging()
    }

    @Test
    fun rejectedKeysNeverPublishAndLeaveOriginalBytesUntouched() {
        requirePosix()
        val original = originalArchive()
        val bytes = Files.readAllBytes(original)
        val invalidOldOutput = directory.resolve("wrong-old.clwdb")
        assertFails {
            EncryptedSqliteBackups.rekey(
                original,
                invalidOldOutput,
                "wrong-old-archive-password".toCharArray(),
                newPassphrase,
            )
        }
        assertFalse(Files.exists(invalidOldOutput))

        val sameKeyOutput = directory.resolve("same-key.clwdb")
        assertFails {
            EncryptedSqliteBackups.rekey(original, sameKeyOutput, oldPassphrase, oldPassphrase.copyOf())
        }
        assertFalse(Files.exists(sameKeyOutput))

        val weakKeyOutput = directory.resolve("weak-key.clwdb")
        assertFails {
            EncryptedSqliteBackups.rekey(original, weakKeyOutput, oldPassphrase, "short".toCharArray())
        }
        assertFalse(Files.exists(weakKeyOutput))
        assertTrue(bytes.contentEquals(Files.readAllBytes(original)))
        assertNoStaging()
    }

    @Test
    fun corruptOrTruncatedOldArchiveCannotBeRotated() {
        requirePosix()
        val original = originalArchive()
        val bytes = Files.readAllBytes(original)
        val tampered = directory.resolve("tampered.clwdb")
        val mutated = bytes.copyOf()
        mutated[mutated.lastIndex] = (mutated.last().toInt() xor 1).toByte()
        Files.write(tampered, mutated)
        val truncated = directory.resolve("truncated.clwdb")
        Files.write(truncated, bytes.copyOf(bytes.size - 1))
        for (archive in listOf(tampered, truncated)) {
            val target = directory.resolve(archive.fileName.toString() + ".new")
            assertFails { EncryptedSqliteBackups.rekey(archive, target, oldPassphrase, newPassphrase) }
            assertFalse(Files.exists(target))
        }
        assertTrue(bytes.contentEquals(Files.readAllBytes(original)))
        assertNoStaging()
    }

    @Test
    fun rekeyRefusesExistingDestinationsAndSymlinkSources() {
        requirePosix()
        val original = originalArchive()
        val existing = directory.resolve("existing.clwdb")
        Files.writeString(existing, "keep-existing")
        assertFails { EncryptedSqliteBackups.rekey(original, existing, oldPassphrase, newPassphrase) }
        assertEquals("keep-existing", Files.readString(existing))

        val symlinkOutput = directory.resolve("symlink-output.clwdb")
        Files.createSymbolicLink(symlinkOutput, existing.fileName)
        assertFails { EncryptedSqliteBackups.rekey(original, symlinkOutput, oldPassphrase, newPassphrase) }
        assertEquals("keep-existing", Files.readString(existing))

        val symlinkInput = directory.resolve("symlink-input.clwdb")
        Files.createSymbolicLink(symlinkInput, original.fileName)
        assertFails { EncryptedSqliteBackups.rekey(symlinkInput, directory.resolve("from-link.clwdb"), oldPassphrase, newPassphrase) }
        assertFalse(Files.exists(directory.resolve("from-link.clwdb")))
        assertNoStaging()
    }

    @Test
    fun cliRekeyRequiresPrivateKeyFilesAndDoesNotConnectToLiveDatabase() {
        requirePosix()
        val original = originalArchive()
        val oldKeyFile = directory.resolve("old.key")
        val newKeyFile = directory.resolve("new.key")
        Files.writeString(oldKeyFile, String(oldPassphrase) + "\n")
        Files.writeString(newKeyFile, String(newPassphrase) + "\n")
        Files.setPosixFilePermissions(oldKeyFile, privatePermissions)
        Files.setPosixFilePermissions(newKeyFile, PosixFilePermissions.fromString("rw-r--r--"))
        val output = directory.resolve("cli-rekey.clwdb")
        val args =
            arrayOf(
                "db",
                "rekey",
                "--from",
                original.toString(),
                "--out",
                output.toString(),
                "--old-passphrase-file",
                oldKeyFile.toString(),
                "--new-passphrase-file",
                newKeyFile.toString(),
            )
        assertEquals(2, CommandLine(ClawfareCommand()).execute(*args))
        assertFalse(Files.exists(output))

        Files.setPosixFilePermissions(newKeyFile, privatePermissions)
        assertEquals(0, CommandLine(ClawfareCommand()).execute(*args))
        assertEquals(privatePermissions, Files.getPosixFilePermissions(output))
        val database = directory.resolve("cli-recovered.sqlite")
        EncryptedSqliteBackups.restore(output, database, newPassphrase)
        verifyRestoredDatabase(database)
        assertNoStaging()
    }
}
