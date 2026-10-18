package com.clawfare.search

import kotlin.math.round

/** Pounds sterling per 1 unit is not stored; [PER_GBP] is how many units of that currency buy £1. */
object Fx {
    const val AS_OF: String = "14 Oct 2026 ECB reference rates (frankfurter.dev): £1 = $1.3267 = MX\$24.365 = C\$1.8864 = €1.185"
    val PER_GBP: Map<String, Double> =
        mapOf(
            "GBP" to 1.0,
            "USD" to 1.3267,
            "MXN" to 24.365,
            "CAD" to 1.8864,
            "EUR" to 1.185,
        )
    val MARKET_CURRENCY: Map<String, String> =
        mapOf(
            "uk" to "GBP",
            "us" to "USD",
            "mx" to "MXN",
            "ca" to "CAD",
            "es" to "EUR",
        )

    fun currencyOf(marketOrCode: String): String =
        when (marketOrCode.lowercase()) {
            "uk" -> "GBP"
            "us" -> "USD"
            "mx" -> "MXN"
            "ca" -> "CAD"
            "es" -> "EUR"
            else -> marketOrCode.uppercase()
        }

    /** Whole pounds, matching the published pages. Null when the currency is unknown. */
    fun toGbpOrNull(
        amount: Double,
        marketOrCode: String,
    ): Double? {
        val per = PER_GBP[currencyOf(marketOrCode)] ?: return null
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
     * First checked bag, in the market currency.
     * An explicit "1st checked bag" figure from the provider wins. Otherwise Aeromexico's
     * US–Mexico transborder rate of US$30 per direction is used (the assumption on the sample page).
     * [directions] is 2 for a round trip and 1 for a one-way.
     */
    fun bagFee(
        market: String,
        baggageText: String,
        directions: Int,
        checkedBags: Int,
    ): Double {
        if (checkedBags <= 0) return 0.0
        val text = baggageText.lowercase()
        if ("free checked" in text) return 0.0
        val listed = BAG_LISTED.find(baggageText)
        if (listed != null) return listed.groupValues[1].replace(",", "").toDouble()
        val usd = 30.0 * directions * checkedBags.coerceAtMost(1)
        val marketPerGbp = Fx.PER_GBP[Fx.currencyOf(market)] ?: Fx.PER_GBP.getValue("USD")
        val usdPerGbp = Fx.PER_GBP.getValue("USD")
        return round(usd * marketPerGbp / usdPerGbp)
    }

    fun rows(
        quotes: Map<String, MarketQuotes>,
        checkedBags: Int,
        links: Map<String, List<BookLink>> = emptyMap(),
        fetchedAt: String? = null,
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
                    )
            }
            if (q.outbound.isNotEmpty() && q.inbound.isNotEmpty()) {
                val outBest = q.outbound.minBy { it.value.price + bagFee(market, it.value.bags, 1, checkedBags) }
                val backBest = q.inbound.minBy { it.value.price + bagFee(market, it.value.bags, 1, checkedBags) }
                val mixName = "Mix (any vendor): ${outBest.key} out + ${backBest.key} back"
                val worst = worseTrust(trustOf(outBest.key).second, trustOf(backBest.key).second)
                out +=
                    row(market, mixName, null, outBest.value, backBest.value, checkedBags, true, worst, links[market].orEmpty(), fetchedAt)
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
    ): VendorRow {
        val base =
            vendor.substringBefore(" — ").substringBefore(" out +").let {
                if (vendor.startsWith("Mix")) vendor.substringAfter(": ").substringBefore(" out +") else it
            }
        val (type, trust) = if (trustOverride != null) "OTA" to trustOverride else trustOf(if (mix) base else vendor.substringBefore(" — "))
        val rtb = rt?.let { it.price + bagFee(market, it.bags, 2, checkedBags) }
        val sb =
            if (ow != null && back != null) {
                ow.price + bagFee(market, ow.bags, 1, checkedBags) + back.price + bagFee(market, back.bags, 1, checkedBags)
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
                roundTripWithBag?.let { Fx.toGbp(it, market).toDouble() to "RT" },
                oneWaysWithBag?.let { Fx.toGbp(it, market).toDouble() to "2×1-way" },
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

    private val BAG_LISTED = Regex("""1st checked bag:\s*([\d,]+)""")

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
