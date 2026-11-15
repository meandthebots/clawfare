package com.clawfare.search

/** European airports used to tell a transatlantic leg from a domestic one. */
val EUROPE_AIRPORTS: Set<String> =
    setOf(
        "LHR",
        "LGW",
        "LCY",
        "STN",
        "LTN",
        "MAN",
        "BHX",
        "EDI",
        "GLA",
        "BRS",
        "NCL",
        "LPL",
        "DUB",
        "ORK",
        "SNN",
        "KEF",
        "CDG",
        "ORY",
        "NCE",
        "LYS",
        "MRS",
        "AMS",
        "RTM",
        "EIN",
        "BRU",
        "CRL",
        "ZRH",
        "GVA",
        "BSL",
        "IST",
        "SAW",
        "MAD",
        "BCN",
        "AGP",
        "PMI",
        "LIS",
        "OPO",
        "FAO",
        "FRA",
        "MUC",
        "BER",
        "HAM",
        "DUS",
        "CGN",
        "STR",
        "WAW",
        "KRK",
        "PRG",
        "BUD",
        "VIE",
        "CPH",
        "OSL",
        "ARN",
        "HEL",
        "GOT",
        "FCO",
        "MXP",
        "LIN",
        "BGY",
        "VCE",
        "NAP",
        "ATH",
        "LUX",
        "OSL",
        "BGO",
        "RIX",
        "TLL",
        "VNO",
        "SOF",
        "OTP",
        "ZAG",
        "LJU",
        "BEG",
    )

private val EUROPEAN_CODES =
    setOf(
        "BA",
        "LH",
        "AF",
        "KL",
        "LX",
        "AY",
        "SK",
        "IB",
        "AZ",
        "TP",
        "EI",
        "VS",
        "DY",
        "FI",
        "WK",
        "SN",
        "LO",
        "DE",
        "OS",
        "A3",
        "EN",
        "2L",
    )

private val EUROPEAN_NAMES =
    setOf(
        "icelandair",
        "air france",
        "british airways",
        "virgin atlantic",
        "swiss",
        "iberia",
        "tap air portugal",
        "tap portugal",
        "klm",
        "edelweiss air",
        "edelweiss",
        "level",
        "brussels airlines",
        "lot",
        "discover airlines",
        "lufthansa",
        "condor",
        "aer lingus",
        "finnair",
        "helvetic",
        "air dolomiti",
        "portugalia",
        "ita airways",
        "norwegian",
        "austrian",
        "aegean",
        "sas",
    )

fun isEuropeanAirport(code: String): Boolean = code.uppercase() in EUROPE_AIRPORTS

fun isTransatlantic(
    from: String,
    to: String,
): Boolean = isEuropeanAirport(from) != isEuropeanAirport(to)

fun isEuropeanOperator(raw: String?): Boolean {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return false
    if (s.uppercase() in EUROPEAN_CODES) return true
    val n = s.lowercase()
    return EUROPEAN_NAMES.any { n == it || n.startsWith("$it ") || n.startsWith(it) }
}

fun cabinKeyOf(raw: String?): String =
    when {
        raw.isNullOrBlank() -> "economy"
        raw.contains("prem", true) -> "premium_economy"
        raw.contains("bus", true) -> "business"
        raw.contains("first", true) -> "first"
        raw.equals("w", true) -> "premium_economy"
        raw.equals("j", true) -> "business"
        raw.equals("f", true) -> "first"
        else -> "economy"
    }

fun cabinLabelOf(key: String): String =
    when (key) {
        "premium_economy" -> "Premium economy"
        "business" -> "Business"
        "first" -> "First"
        else -> "Economy"
    }

private val CABIN_RANK = listOf("economy", "premium_economy", "business", "first")

fun cabinAtLeast(
    actual: String?,
    minimum: String,
): Boolean {
    val a = CABIN_RANK.indexOf(cabinKeyOf(actual)).let { if (it < 0) 0 else it }
    val m = CABIN_RANK.indexOf(cabinKeyOf(minimum)).let { if (it < 0) 0 else it }
    return a >= m
}

/** Marketing sites for the booking column. Direct links, not tracked affiliate URLs. */
val AIRLINE_SITES: Map<String, Pair<String, String>> =
    mapOf(
        "BA" to ("British Airways" to "https://www.britishairways.com/"),
        "VS" to ("Virgin Atlantic" to "https://www.virginatlantic.com/"),
        "KL" to ("KLM" to "https://www.klm.co.uk/"),
        "AF" to ("Air France" to "https://wwws.airfrance.co.uk/"),
        "DL" to ("Delta" to "https://www.delta.com/"),
        "B6" to ("JetBlue" to "https://www.jetblue.com/"),
        "UA" to ("United" to "https://www.united.com/"),
        "AA" to ("American Airlines" to "https://www.aa.com/"),
        "AC" to ("Air Canada" to "https://www.aircanada.com/"),
        "AM" to ("Aeromexico" to "https://aeromexico.com/"),
        "LH" to ("Lufthansa" to "https://www.lufthansa.com/"),
        "IB" to ("Iberia" to "https://www.iberia.com/"),
        "AY" to ("Finnair" to "https://www.finnair.com/"),
        "EI" to ("Aer Lingus" to "https://www.aerlingus.com/"),
        "FI" to ("Icelandair" to "https://www.icelandair.com/"),
        "SK" to ("SAS" to "https://www.flysas.com/"),
        "LX" to ("SWISS" to "https://www.swiss.com/"),
        "TP" to ("TAP Air Portugal" to "https://www.flytap.com/"),
        "NH" to ("ANA" to "https://www.ana.co.jp/"),
        "JL" to ("Japan Airlines" to "https://www.jal.co.jp/"),
    )

val AIRLINE_NAMES: Map<String, String> =
    AIRLINE_SITES.mapValues { it.value.first } +
        mapOf(
            "LO" to "LOT",
            "SN" to "Brussels Airlines",
            "DE" to "Condor",
            "WK" to "Edelweiss",
            "OS" to "Austrian",
            "A3" to "Aegean",
            "DY" to "Norwegian",
        )
