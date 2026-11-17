package com.clawfare.db

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import java.util.UUID

/**
 * Produces a recoverable pre-migration SQLite snapshot without exposing its
 * contents to other local users while SQLite creates it.
 *
 * SQLite VACUUM INTO requires a nonexistent destination. A 0700 directory,
 * created alongside the database, protects the unfinished snapshot; the
 * verified 0600 file is then published using an exclusive hard link. Unlike
 * an ordinary rename, a hard link never replaces an existing backup.
 *
 * Called under ClawfareDatabase's migration lock. Other applications that
 * ignore that lock can still write to the live database; SQLite guarantees
 * the VACUUM INTO snapshot represents one consistent transaction.
 */
internal object SqliteMigrationSnapshots {
    fun beforeMigration(
        jdbcUrl: String,
        source: File,
    ): Path {
        val database = source.toPath().toAbsolutePath().normalize()
        val parent = database.parent ?: throw IOException("Database path has no parent.")
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(database)
        ) {
            throw IOException("Database must be a regular file under a non-symlink directory.")
        }
        if (Files.getFileAttributeView(parent, PosixFileAttributeView::class.java) == null) {
            throw IOException("Private migration snapshots require POSIX file permissions.")
        }

        val privateDirectory =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        val staging = Files.createTempDirectory(parent, ".clawfare-snapshot-", privateDirectory)
        val pending = staging.resolve("snapshot.sqlite")
        try {
            val sqlPath = pending.toString().replace("'", "''")
            DriverManager.getConnection(jdbcUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("VACUUM INTO '$sqlPath'")
                }
            }
            Files.setPosixFilePermissions(pending, PosixFilePermissions.fromString("rw-------"))
            validate(pending)
            FileChannel.open(pending, StandardOpenOption.WRITE).use { it.force(true) }
            val backup =
                parent.resolve(
                    database.fileName.toString() + ".backup-" +
                        System.currentTimeMillis() + "-" + UUID.randomUUID() + ".sqlite",
                )
            // Exclusive publication. If this name exists for any reason,
            // fail instead of clobbering a previous recovery point.
            Files.createLink(backup, pending)
            return backup
        } finally {
            Files.deleteIfExists(pending)
            Files.deleteIfExists(staging)
        }
    }

    /** Confirm the snapshot is an actual readable SQLite database. */
    private fun validate(path: Path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("SQLite migration snapshot is not a regular file.")
        }
        val jdbcUrl = "jdbc:sqlite:${path.toAbsolutePath()}"
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA integrity_check").use { results ->
                    if (!results.next() || results.getString(1) != "ok" || results.next()) {
                        throw IOException("SQLite migration snapshot failed integrity verification.")
                    }
                }
            }
        }
    }
}
