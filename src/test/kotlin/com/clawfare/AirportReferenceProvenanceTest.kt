package com.clawfare

import com.clawfare.search.AirportReferenceLoader
import com.clawfare.search.TransitRules
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AirportReferenceProvenanceTest {
    private val trusted = mapOf("HKG" to "HK", "MFM" to "MO", "PVG" to "CN", "HEL" to "FI")

    private fun csv(body: String): File =
        File.createTempFile("airport-country", ".csv").apply {
            writeText("iata_code,iso_country,name\n$body")
            deleteOnExit()
        }

    @Test
    fun pinnedOfflineDatasetHasDeterministicHashAndSafeMetadata() {
        val source = csv("HEL,FI,Helsinki\nHKG,HK,Hong Kong\nXYZ,US,Fixture\nXYZ,US,Fixture Duplicate\n")
        val first = AirportReferenceLoader.fromFile(source, trusted, asOf = "2026-11-15")
        assertEquals("local_ourairports", first.metadata.source)
        assertEquals("2026-11-15", first.metadata.asOf)
        assertEquals(5, first.metadata.iataAirportCount)
        assertEquals("US", first.countries["XYZ"])
        assertTrue(first.metadata.sha256!!.matches(Regex("[0-9a-f]{64}")))
        assertFalse(first.metadata.toString().contains(source.absolutePath))
        val pinned = AirportReferenceLoader.fromFile(source, trusted, expectedSha256 = first.metadata.sha256)
        assertEquals(first.countries, pinned.countries)
        source.appendText("ABC,GB,Extra\n")
        assertFailsWith<IllegalArgumentException> {
            AirportReferenceLoader.fromFile(source, trusted, expectedSha256 = first.metadata.sha256)
        }
    }

    @Test
    fun missingSourceInvalidDateAndContradictoryCountriesFailClosed() {
        val missing = File.createTempFile("airport-missing", ".csv")
        missing.delete()
        assertFailsWith<IllegalArgumentException> { AirportReferenceLoader.fromFile(missing, trusted) }
        assertFailsWith<Exception> { AirportReferenceLoader.fromFile(csv("HEL,FI,Helsinki\n"), trusted, asOf = "tomorrow") }
        assertFailsWith<IllegalArgumentException> {
            AirportReferenceLoader.fromFile(csv("HKG,GB,Contradiction\n"), trusted)
        }
        assertFailsWith<IllegalArgumentException> {
            AirportReferenceLoader.fromFile(csv("FOO,FR,One\nFOO,CN,Two\n"), trusted)
        }
        assertFailsWith<IllegalArgumentException> {
            AirportReferenceLoader.fromFile(csv("HEL,FI,Valid\n"), trusted, expectedSha256 = "not a hash")
        }
    }

    @Test
    fun normalizedAirportCountryCoveragePreservesUnknowns() {
        // The built-in map remains sufficient for these known transit stations.
        val coverage = TransitRules.countryCoverage(listOf(" hkg ", "MFM", "PVG", "HEL", "ZZZ", "zzz"))
        assertEquals(4, coverage.known)
        assertEquals(listOf("ZZZ"), coverage.unknownIata)
        assertTrue(coverage.metadata.iataAirportCount >= 4)
    }
}
