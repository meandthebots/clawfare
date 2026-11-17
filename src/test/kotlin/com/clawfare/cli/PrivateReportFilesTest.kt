package com.clawfare.cli

import com.clawfare.db.ClawfareDatabase
import com.clawfare.search.SampleData
import com.clawfare.search.SampleSeeder
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivateReportFilesTest {
    @TempDir
    lateinit var directory: Path

    private val privateMode = PosixFilePermissions.fromString("rw-------")

    private fun requirePosix() {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView::class.java) != null)
    }

    @Test
    fun newAndReplacedReportsStayOwnerOnlyWithoutTemporaryFiles() {
        requirePosix()
        val destination = directory.resolve("report.csv")
        PrivateReportFiles.writeText(destination.toString(), "synthetic-first")
        assertEquals("synthetic-first", Files.readString(destination))
        assertEquals(privateMode, Files.getPosixFilePermissions(destination))

        // An existing broadly readable report must be replaced, not overwritten in place.
        Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("rw-r--r--"))
        PrivateReportFiles.writeText(destination.toString(), "synthetic-second")
        assertEquals("synthetic-second", Files.readString(destination))
        assertEquals(privateMode, Files.getPosixFilePermissions(destination))
        assertFalse(
            Files.list(directory).use { files ->
                files.anyMatch { it.fileName.toString().startsWith(".clawfare-report-") }
            },
        )
    }

    @Test
    fun symlinkDestinationCannotOverwriteOrDiscloseOtherFiles() {
        requirePosix()
        val outside = directory.resolve("unrelated-private.json")
        Files.writeString(outside, "keep-me")
        val link = directory.resolve("report.json")
        Files.createSymbolicLink(link, outside.fileName)

        assertFailsWith<IOException> { PrivateReportFiles.writeText(link.toString(), "bad-content") }
        assertEquals("keep-me", Files.readString(outside))
        assertTrue(Files.isSymbolicLink(link))
    }

    @Test
    fun directoryDestinationAndMissingParentAreRejected() {
        requirePosix()
        assertFailsWith<IOException> { PrivateReportFiles.writeText(directory.toString(), "bad-content") }
        assertFailsWith<IOException> {
            PrivateReportFiles.writeText(directory.resolve("missing").resolve("file.csv").toString(), "bad-content")
        }
    }

    @Test
    fun realCliReportPathsUsePrivatePermissions() {
        requirePosix()
        ClawfareDatabase.connectInMemory()
        SampleSeeder.ensure()
        val csv = directory.resolve("itineraries.csv")
        val html = directory.resolve("report.html")
        val sample = directory.resolve("sample.json")

        assertEquals(
            0,
            CommandLine(ClawfareCommand()).execute("report", "csv", SampleData.ID, "--out", csv.toString()),
        )
        assertEquals(
            0,
            CommandLine(ClawfareCommand()).execute("report", "html", SampleData.ID, "--out", html.toString()),
        )
        assertEquals(
            0,
            CommandLine(ClawfareCommand()).execute("report", "sample-json", "--out", sample.toString()),
        )
        for (report in listOf(csv, html, sample)) {
            assertTrue(Files.size(report) > 0L)
            assertEquals(privateMode, Files.getPosixFilePermissions(report))
        }
        assertTrue(Files.readString(csv).contains("outbound_date"))
        assertTrue(Files.readString(html).contains("window.__SNAPSHOT__"))
        assertTrue(Files.readString(sample).contains(SampleData.ID))
    }
}
