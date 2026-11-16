package com.clawfare.search

import kotlin.math.round

/** Pounds sterling per 1 unit is not stored; [PER_GBP] is how many units of that currency buy £1. */
object Fx {
    private const val FALLBACK_AS_OF =
        "14 Oct 2026 ECB reference rates (frankfurter.dev): £1 = $1.3267 = MX$24.365 = C$1.8864 = €1.185"
    private val fallbackRates: Map<String, Double> =
        mapOf(
            "GBP" to 1.0,
            "USD" to 1.3267,
            "MXN" to 24.365,
            "CAD" to 1.8864,
            "EUR" to 1.185,
        )

    private val savedSnapshot by lazy { FxRateStore.load() }

    @Volatile
    private var refreshedSnapshot: FxSnapshot? = null

    fun useSnapshot(snapshot: FxSnapshot) {
        refreshedSnapshot = snapshot
    }

    private fun snapshot(): FxSnapshot? = refreshedSnapshot ?: savedSnapshot

    val AS_OF: String
        get() = snapshot()?.let { "Frankfurter GBP reference rates for ${it.date}" } ?: FALLBACK_AS_OF

    val PER_GBP: Map<String, Double>
        get() = snapshot()?.perGbp ?: fallbackRates

    val MARKET_CURRENCY: Map<String, String> =
        mapOf(
            "uk" to "GBP",
            "us" to "USD",
            "mx" to "MXN",
            "ca" to "CAD",
            "es" to "EUR",
            "fr" to "EUR",
            "de" to "EUR",
            "nl" to "EUR",
            "dk" to "DKK",
        )

    fun currencyOf(marketOrCode: String): String = MARKET_CURRENCY[marketOrCode.lowercase()] ?: marketOrCode.uppercase()

    /** Whole pounds, matching the published pages. Null when the currency is unknown. */
    fun toGbpOrNull(
        amount: Double,
        marketOrCode: String,
    ): Double? {
        val per = PER_GBP[currencyOf(marketOrCode)]?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        if (!amount.isFinite() || amount < 0.0) return null
        return round(amount / per)
    }

    fun toGbp(
        amount: Double,
        marketOrCode: String,
    ): Int = (toGbpOrNull(amount, marketOrCode) ?: round(amount)).toInt()
}

data class SellerQuote(
    val vendor: String,
    val price: Double,
    val bags: String,
    val title: String? = null,
)

object VendorMath {
    /**
     * Estimate checked-bag cost only when the provider supplied enough details.
     * Returns null for unknown fees: replacing them with a US-Mexico fallback
     * incorrectly favors vendors on unrelated routes.
     * A "1st checked bag" charge is interpreted per passenger and direction.
     */
    fun knownBagFee(
        baggageText: String,
        directions: Int,
        checkedBags: Int,
        passengers: Int = 1,
    ): Double? {
        if (checkedBags <= 0) return 0.0
        val text = baggageText.lowercase()
        val freeCount =
            Regex("""\b([1-4])\s+(?:free\s+)?checked\s+bags?\b""")
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        if (freeCount != null && checkedBags <= freeCount && "free" in text) return 0.0
        if (checkedBags == 1 && ("free checked" in text || "includes a free checked bag" in text)) return 0.0

        val ordinal = mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4)
        val fees = mutableMapOf<Int, Double>()
        for (match in BAG_LISTED.findAll(baggageText)) {
            val index = ordinal[match.groupValues[1].lowercase()] ?: match.groupValues[1].take(1).toIntOrNull() ?: continue
            val token = match.groupValues[2].lowercase()
            val value = if (token == "free" || token == "included") 0.0 else token.replace(",", "").toDoubleOrNull()
            if (value != null) fees[index] = value
        }
        if ((1..checkedBags).any { it !in fees }) return null
        return (1..checkedBags).sumOf { fees.getValue(it) } * directions * passengers
    }

    /** Compatibility helper for callers requiring a numeric amount; unknown is zero, not a quoted fee. */
    fun bagFee(
        market: String,
        baggageText: String,
        directions: Int,
        checkedBags: Int,
        passengers: Int = 1,
    ): Double = knownBagFee(baggageText, directions, checkedBags, passengers) ?: 0.0

    fun rows(
        quotes: Map<String, MarketQuotes>,
        checkedBags: Int,
        links: Map<String, List<BookLink>> = emptyMap(),
        fetchedAt: String? = null,
        passengers: Int = 1,
    ): List<VendorRow> {
        val out = mutableListOf<VendorRow>()
        for ((market, q) in quotes) {
            val names = (q.roundTrip.keys + q.outbound.keys + q.inbound.keys).toSortedSet()
            for (name in names) {
                out +=
                    row(
                        market,
                        name,
                        q.roundTrip[name],
                        q.outbound[name],
                        q.inbound[name],
                        checkedBags,
                        false,
                        null,
                        links[market].orEmpty(),
                        fetchedAt,
                        passengers,
                    )
            }
            val outBest =
                q.outbound
                    .filterValues { knownBagFee(it.bags, 1, checkedBags, passengers) != null }
                    .minByOrNull { it.value.price + knownBagFee(it.value.bags, 1, checkedBags, passengers)!! }
            val backBest =
                q.inbound
                    .filterValues { knownBagFee(it.bags, 1, checkedBags, passengers) != null }
                    .minByOrNull { it.value.price + knownBagFee(it.value.bags, 1, checkedBags, passengers)!! }
            if (outBest != null && backBest != null) {
                val mixName = "Mix (any vendor): ${outBest.key} out + ${backBest.key} back"
                val worst = worseTrust(trustOf(outBest.key).second, trustOf(backBest.key).second)
                out +=
                    row(
                        market,
                        mixName,
                        null,
                        outBest.value,
                        backBest.value,
                        checkedBags,
                        true,
                        worst,
                        links[market].orEmpty(),
                        fetchedAt,
                        passengers,
                    )
            }
        }
        return out.sortedWith(compareBy({ it.allInGbp == null }, { it.allInGbp ?: Double.MAX_VALUE }))
    }

    private fun row(
        market: String,
        vendor: String,
        rt: SellerQuote?,
        ow: SellerQuote?,
        back: SellerQuote?,
        checkedBags: Int,
        mix: Boolean,
        trustOverride: String?,
        links: List<BookLink>,
        fetchedAt: String?,
        passengers: Int,
    ): VendorRow {
        val base =
            vendor.substringBefore(" — ").substringBefore(" out +").let {
                if (vendor.startsWith("Mix")) vendor.substringAfter(": ").substringBefore(" out +") else it
            }
        val (type, trust) = if (trustOverride != null) "OTA" to trustOverride else trustOf(if (mix) base else vendor.substringBefore(" — "))
        val rtb = rt?.let { quote -> knownBagFee(quote.bags, 2, checkedBags, passengers)?.let { quote.price + it } }
        val sb =
            if (ow != null && back != null) {
                val outFee = knownBagFee(ow.bags, 1, checkedBags, passengers)
                val backFee = knownBagFee(back.bags, 1, checkedBags, passengers)
                if (outFee != null && backFee != null) ow.price + outFee + back.price + backFee else null
            } else {
                null
            }
        val (allIn, how) = allIn(market, rtb, sb)
        val bags = (rt ?: ow ?: back)?.bags.orEmpty()
        val brand = (rt ?: ow ?: back)?.title
        return VendorRow(
            vendor = vendor,
            market = market,
            marketLabel = MARKET_LABELS[market] ?: market.uppercase(),
            currency = Fx.currencyOf(market),
            fareBrand = brand ?: if (mix) "2 separate tickets" else "",
            trust = trust,
            trustLabel = TRUST_LABELS[trust] ?: trust,
            note = noteOf(if (mix) base else vendor.substringBefore(" — ")),
            roundTrip = rt?.price,
            oneWayOut = ow?.price,
            oneWayBack = back?.price,
            roundTripWithBag = rtb,
            oneWaysWithBag = sb,
            allInGbp = allIn,
            how = how,
            bags = bags,
            mix = mix,
            fresh = true,
            fetchedAt = fetchedAt,
            links = links,
        )
    }

    fun allIn(
        market: String,
        roundTripWithBag: Double?,
        oneWaysWithBag: Double?,
    ): Pair<Double?, String> {
        val options =
            listOfNotNull(
                roundTripWithBag?.let { Fx.toGbpOrNull(it, market)?.let { gbp -> gbp to "RT" } },
                oneWaysWithBag?.let { Fx.toGbpOrNull(it, market)?.let { gbp -> gbp to "2×1-way" } },
            )
        if (options.isEmpty()) return null to ""
        val best = options.minBy { it.first }
        return best.first to best.second
    }

    fun trustOf(vendor: String): Pair<String, String> {
        val base = vendor.substringBefore(" — ").trim()
        TRUST.entries.firstOrNull { base.equals(it.key, true) || base.startsWith(it.key, true) }?.let { return it.value }
        return "OTA" to "caution"
    }

    fun noteOf(vendor: String): String {
        val base = vendor.substringBefore(" — ").trim()
        return NOTES.entries
            .firstOrNull { base.equals(it.key, true) || base.startsWith(it.key, true) }
            ?.value
            .orEmpty()
    }

    private fun worseTrust(
        a: String,
        b: String,
    ): String {
        val rank = listOf("airline", "trusted", "caution", "risky")
        return if (rank.indexOf(a) >= rank.indexOf(b)) a else b
    }

    private val BAG_LISTED =
        Regex("""(?i)\b(1st|2nd|3rd|4th|first|second|third|fourth)\s+checked\s+bag\s*:\s*(?:[£$€]\s*)?([\d,]+(?:\.\d+)?|free|included)\b""")

    val MARKET_LABELS =
        mapOf(
            "uk" to "🇬🇧 UK · GBP",
            "us" to "🇺🇸 US · USD",
            "mx" to "🇲🇽 Mexico · MXN",
            "ca" to "🇨🇦 Canada · CAD",
            "es" to "🇪🇸 Spain · EUR",
        )
    val TRUST_LABELS =
        mapOf(
            "airline" to "Airline",
            "trusted" to "Trusted OTA",
            "caution" to "OTA · caution",
            "risky" to "Risky OTA",
        )

    private val TRUST: Map<String, Pair<String, String>> =
        buildMap {
            put("Aeromexico", "Airline" to "airline")
            put("Delta", "Airline" to "airline")
            put("British Airways", "Airline" to "airline")
            put("Virgin Atlantic", "Airline" to "airline")
            put("American Airlines", "Airline" to "airline")
            put("United", "Airline" to "airline")
            put("KLM", "Airline" to "airline")
            put("Air France", "Airline" to "airline")
            put("JetBlue", "Airline" to "airline")
            for (n in listOf(
                "Opodo",
                "eDreams",
                "Booking.com",
                "Expedia",
                "Orbitz",
                "Travelocity",
                "Priceline",
                "Trip.com",
                "lastminute.com",
                "FlightHub",
                "Justfly.com",
                "Despegar",
                "Best Day",
                "eDestinos",
                "KILROY",
            )) {
                put(n, "OTA" to "trusted")
            }
            for (n in listOf(
                "Kiwi.com",
                "CheapOair",
                "OneTravel",
                "Mytrip",
                "Gotogate",
                "Flightnetwork",
                "BudgetAir",
                "EaseMyTrip",
                "Travel Trolley",
                "eSky",
                "Turna.com",
                "lucky2go",
                "kiss&fly",
                "Travel Up",
                "travelfrom",
                "business-class.com",
                "Flights Pro",
            )) {
                put(n, "OTA" to "caution")
            }
            for (n in listOf("Adam Vacations", "ly.com", "Oojo x Dreampass", "SmartFares")) {
                put(n, "OTA" to "risky")
            }
        }

    private val NOTES: Map<String, String> =
        mapOf(
            "Adam Vacations" to "BBB F, many complaints",
            "ly.com" to "Trustpilot 1.7; missing-ticket/refund complaints",
            "Oojo x Dreampass" to "BBB refund complaints",
            "SmartFares" to "many BBB refund complaints",
            "BudgetAir" to "Trustpilot 2.3",
            "Gotogate" to "Trustpilot 2.9",
            "Mytrip" to "Trustpilot 3.0",
            "Flightnetwork" to "Trustpilot 3.1",
            "Kiwi.com" to "Trustpilot 3.9; strict self-service",
            "Opodo" to "Trustpilot 4.3",
            "eDreams" to "Trustpilot 4.3",
            "Justfly.com" to "Trustpilot 4.2",
            "FlightHub" to "Trustpilot 4.0 (.com); flighthub.ca 1.4",
            "Trip.com" to "Trustpilot 4.4",
            "CheapOair" to "Fareportal; mixed reviews",
            "OneTravel" to "Fareportal; mixed reviews",
        )
}

data class MarketQuotes(
    val roundTrip: Map<String, SellerQuote> = emptyMap(),
    val outbound: Map<String, SellerQuote> = emptyMap(),
    val inbound: Map<String, SellerQuote> = emptyMap(),
)
