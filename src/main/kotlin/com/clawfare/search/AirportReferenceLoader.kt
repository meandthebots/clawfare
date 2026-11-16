package com.clawfare.search

import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Provenance for an offline airport-country mapping. The optional date is
 * operator-supplied; hashing cannot authenticate the source or its freshness.
 */
data class AirportCountryMetadata(
    val source: String,
    val iataAirportCount: Int,
    val sha256: String? = null,
    val asOf: String? = null,
)

data class AirportCountryReference(
    val countries: Map<String, String>,
    val metadata: AirportCountryMetadata,
)

data class AirportCountryCoverage(
    val known: Int,
    val unknownIata: List<String>,
    val metadata: AirportCountryMetadata,
)

/** A locally pinned OurAirports CSV; no downloads or network requests. */
object AirportReferenceLoader {
    fun fromFile(
        file: File,
        builtin: Map<String, String>,
        expectedSha256: String? = null,
        asOf: String? = null,
    ): AirportCountryReference {
        require(file.isFile && file.canRead()) { "Configured airport-country CSV is missing or unreadable." }
        require(file.length() in 1..(50L * 1024L * 1024L)) { "Airport-country CSV is empty or too large." }
        // Hash and parse the *same bytes* so replacement during parsing cannot
        // create a mapping different from the recorded fingerprint.
        val bytes = file.readBytes()
        val digest =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
        expectedSha256?.takeIf { it.isNotBlank() }?.let { pinned ->
            require(pinned.matches(Regex("[0-9a-fA-F]{64}"))) { "Expected airport-country SHA-256 is invalid." }
            require(digest.equals(pinned, ignoreCase = true)) { "Airport-country CSV SHA-256 does not match the pin." }
        }
        val date = asOf?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it).toString() }
        val extra = bytes.inputStream().bufferedReader().use { TransitRules.parseOurAirports(it) }
        require(extra.isNotEmpty()) { "Airport-country CSV contains no valid IATA-country mapping." }
        for ((iata, country) in extra) {
            require(builtin[iata] == null || builtin[iata] == country) {
                "Airport-country CSV conflicts with protected built-in country for $iata."
            }
        }
        val merged = builtin + extra
        return AirportCountryReference(
            countries = merged,
            metadata =
                AirportCountryMetadata(
                    source = "local_ourairports",
                    iataAirportCount = merged.size,
                    sha256 = digest,
                    asOf = date,
                ),
        )
    }
}
