package com.clawfare.cli

import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * Owner-only, atomic replacement of private local reports.
 *
 * The caller chooses the destination. Never create a report directly at that
 * path, open an existing file for writing, or follow a destination symlink.
 * The temporary file stays in the same directory to permit an atomic rename.
 *
 * Refuse filesystems without POSIX permissions rather than silently claiming
 * confidentiality on a platform whose ACLs we have not implemented.
 */
object PrivateReportFiles {
    fun writeText(
        filename: String,
        text: String,
    ) {
        val destination = Path.of(filename).toAbsolutePath().normalize()
        val directory = destination.parent ?: throw IOException("Report path has no parent directory.")
        if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) {
            throw IOException("Report parent must be an existing, non-symlink directory: $directory")
        }
        if (Files.getFileAttributeView(directory, PosixFileAttributeView::class.java) == null) {
            throw IOException("Owner-only report permissions are unavailable on this filesystem: $directory")
        }
        requireSafeDestination(destination)

        val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        val temporary = Files.createTempFile(directory, ".clawfare-report-", ".tmp", ownerOnly)
        try {
            FileOutputStream(temporary.toFile()).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            // Re-check just before the rename. Replacement never writes through
            // a pre-existing hard link or symlink into an unrelated target.
            requireSafeDestination(destination)
            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun requireSafeDestination(destination: Path) {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("Refusing to replace a symlink or non-file report destination: $destination")
        }
    }
}
