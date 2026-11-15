package com.clawfare.search

import java.io.File

/**
 * Country exclusion on every intermediate airport, including an unrecognised
 * airport. An incomplete mapping never permits an unknown connection to slip
 * through a safety filter: unknown transit airports fail closed.
 *
 * Full coverage can be supplied from OurAirports public-domain airports.csv:
 * https://ourairports.com/data/ using CLAWFARE_AIRPORTS_CSV.
 */
object TransitRules {
    private val builtin =
        mapOf(
            "HKG" to "HK", "MFM" to "MO",
            "PEK" to "CN", "PKX" to "CN", "PVG" to "CN", "SHA" to "CN",
            "CAN" to "CN", "SZX" to "CN", "CTU" to "CN", "TFU" to "CN",
            "CKG" to "CN", "XIY" to "CN", "KMG" to "CN", "WUH" to "CN",
            "HGH" to "CN", "NKG" to "CN", "XMN" to "CN", "FOC" to "CN",
            "TAO" to "CN", "TSN" to "CN", "CSX" to "CN", "CGO" to "CN",
            "TNA" to "CN", "DLC" to "CN", "SHE" to "CN", "HRB" to "CN",
            "URC" to "CN", "LHW" to "CN", "LXA" to "CN", "SYX" to "CN",
            "HAK" to "CN", "NNG" to "CN", "NGB" to "CN", "WNZ" to "CN",
            "KHN" to "CN", "KWE" to "CN", "SWA" to "CN", "YNT" to "CN",
            "JHG" to "CN", "KHG" to "CN", "INC" to "CN", "HFE" to "CN",
            "LHR" to "GB", "LGW" to "GB", "MAN" to "GB",
            "CDG" to "FR", "ORY" to "FR", "FRA" to "DE", "MUC" to "DE",
            "AMS" to "NL", "CPH" to "DK", "HEL" to "FI",
            "ZRH" to "CH", "VIE" to "AT", "IST" to "TR",
            "ICN" to "KR", "GMP" to "KR", "NRT" to "JP", "HND" to "JP",
            "SIN" to "SG", "BKK" to "TH", "TPE" to "TW",
            "DOH" to "QA", "DXB" to "AE", "AUH" to "AE",
        )

    private val expanded: Map<String, String> by lazy {
        val path = System.getenv("CLAWFARE_AIRPORTS_CSV")?.takeIf { it.isNotBlank() } ?: return@lazy builtin
        val file = File(path)
        if (!file.isFile) return@lazy builtin
        builtin + parseOurAirports(file)
    }

    fun allows(
        itinerary: Itinerary,
        excludedAirports: List<String>,
        excludedCountries: List<String>,
        countries: Map<String, String> = expanded,
    ): Boolean {
        val explicit = excludedAirports.map { it.uppercase() }.toSet()
        val forbidden = excludedCountries.map { it.uppercase() }.toSet()
        val connections =
            (listOf(itinerary.outbound) + listOfNotNull(itinerary.returning)).flatMap { direction ->
                direction.legs.dropLast(1).map { it.to.uppercase() } + direction.layovers.map { it.airport.uppercase() }
            }
        return connections.none { code ->
            code in explicit || (forbidden.isNotEmpty() && (countries[code] == null || countries[code] in forbidden))
        }
    }

    fun parseOurAirports(file: File): Map<String, String> {
        file.bufferedReader().use { reader ->
            val headers = reader.readLine()?.let(::csvFields) ?: return emptyMap()
            val iata = headers.indexOf("iata_code")
            val country = headers.indexOf("iso_country")
            require(iata >= 0 && country >= 0) { "OurAirports CSV requires iata_code and iso_country." }
            val result = mutableMapOf<String, String>()
            reader.forEachLine { line ->
                val cols = csvFields(line)
                if (cols.size > maxOf(iata, country)) {
                    val code = cols[iata].uppercase()
                    val nation = cols[country].uppercase()
                    if (code.length == 3 && nation.length == 2) result[code] = nation
                }
            }
            return result
        }
    }

    private fun csvFields(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            when {
                line[i] == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                line[i] == '"' -> quoted = !quoted
                line[i] == ',' && !quoted -> {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(line[i])
            }
            i++
        }
        result += current.toString()
        return result
    }
}
