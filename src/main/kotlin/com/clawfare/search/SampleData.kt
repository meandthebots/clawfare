package com.clawfare.search

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object SampleData {
    const val ID: String = "florida-dec-2026"
    private const val FETCHED: String = "2026-10-11T18:00:00Z"

    fun florida(): ResultSet {
        val raw = AppJson.decodeFromString(DataFile.serializer(), resource("fixtures/data.json"))
        val rows =
            AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(VendorFile.serializer()),
                resource("fixtures/rows.json"),
            )
        val longHaul = raw.lh.map { it.toItinerary() }
        val addons = raw.mx.map { it.toItinerary() }
        val cells =
            buildList {
                for (lh in longHaul) {
                    add(Feasibility.evaluate(lh, null))
                    for (mx in addons) add(Feasibility.evaluate(lh, mx))
                }
            }
        val linkByMarket = listOf("uk", "us", "mx", "ca", "es").associateWith { mexicoLinks(it) }
        val vendors = rows.map { it.toRow(linkByMarket[it.mk].orEmpty()) }
        return ResultSet(
            id = ID,
            title = "December 2026 Florida trip — itinerary grid",
            subtitle =
                "London → Tampa / Sarasota for two, plus a Tampa ⇄ Mexico City add-on. " +
                    "Sample saved from Google Flights (long-haul 11 Oct 2026, sellers 15 Oct 2026).",
            fetchedAt = FETCHED,
            currency = "GBP",
            checkedBags = 1,
            passengers = 2,
            itineraries = longHaul,
            addons = addons,
            cells = cells,
            vendors = vendors,
            vendorIntro = vendorNotes(),
            headerNotes = headerNotes(),
            suggestedFilters =
                FilterSpec(
                    maxStops = 1,
                    maxLayoverMinutes = 300,
                    excludeReturnDates = listOf("2026-12-24", "2026-12-25"),
                ),
            scenarioLabel = "florida",
        )
    }

    /** The richer segment shape written by the old selector (`selected.json`). */
    fun selectedItineraries(): List<Itinerary> {
        val raw = AppJson.decodeFromString(SelectedFile.serializer(), resource("fixtures/selected.json"))
        return raw.lh.mapIndexedNotNull { index, row -> row.toItinerary(index + 1) }
    }

    fun resource(path: String): String =
        SampleData::class.java.classLoader
            .getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()
            ?: error("Missing classpath resource $path")

    private fun headerNotes() =
        listOf(
            NoteCard(
                "How to read it",
                "Rows are long-haul options, per person and for the couple. Columns are her Tampa ⇄ Mexico City nonstops. A cell is 2 × the long-haul fare + 1 × Mexico. Click a cell for every flight number, time, layover, and a booking link.",
            ),
            NoteCard(
                "Feasibility",
                "The Mexico trip has to start the day after they land in Tampa, a pre-Christmas trip has to be back by 24 Dec, and it has to finish before the flight home. The switches above are the published cut: no 2-stop itineraries, no connection over 5 hours, no flight home on 24 or 25 Dec.",
            ),
            NoteCard(
                "Data",
                "Saved Google Flights checks, not a live search. Long-haul fares 11 Oct 2026 (GBP, per person, taxes in). Seller table 15 Oct 2026, with some markets carried forward from 14 Oct where noted. Nothing is booked — re-check before paying.",
                warn = true,
            ),
        )

    private fun vendorNotes() =
        listOf(
            NoteCard(
                "Bag rule",
                "Aeromexico Basic, Classic and AM Plus fares on US–Mexico routes include no checked bag. The AM Plus page lists transborder (United States) at US\$30 per direction; that fallback is used when Google did not show a vendor-specific first-bag fee (about £23 / MX\$551 / C\$43 / €27). A fee Google did list (Delta, Expedia, and others) is used instead. Premier includes two bags. Delta-marketed tickets follow Delta’s contract of carriage.",
            ),
            NoteCard(
                "Reputation",
                "Airline = buy direct. Trusted OTA = a large brand with Trustpilot around 4 or better. Caution = mixed or thin reviews, only worth it for a real saving. Risky = a poor BBB rating or a trail of missing-ticket and refund complaints (Adam Vacations, ly.com, Oojo, SmartFares). After any OTA booking, check the ticket on the airline’s site and add the bag there. Rates are ECB references from 14 Oct 2026; a card will differ. Nothing is booked.",
            ),
        )
}

internal fun sampleDay(token: String): String {
    val t = token.trim()
    if (t.length >= 10 && t[4] == '-') return t.take(10)
    if (t.contains("Jan", true)) return "2027-01-%02d".format(t.filter { it.isDigit() }.toInt())
    if (t.contains("Dec", true)) return "2026-12-%02d".format(t.filter { it.isDigit() }.toInt())
    val day = t.toInt()
    return if (day < 10) "2027-01-%02d".format(day) else "2026-12-%02d".format(day)
}

private fun compactLeg(
    parts: List<String>,
    cabinFallback: String,
): Leg {
    val fn = parts[0]
    val code = fn.substringBefore(" ").uppercase()
    val airline = parts.getOrElse(7) { AIRLINE_NAMES[code] ?: code }
    val operator = parts.getOrElse(8) { airline }
    val cabin = parts.getOrNull(9)?.takeIf { it.isNotBlank() }?.let { cabinLabelOf(cabinKeyOf(it)) } ?: cabinFallback
    return Leg(
        flightNumber = fn,
        airline = airline,
        airlineCode = code,
        operatingCarrier = operator,
        from = parts[1],
        to = parts[3],
        departTime = normalizeTime(parts[2]),
        arriveTime = normalizeTime(parts[4]),
        departDate = sampleDay(parts[5]),
        arriveDate = sampleDay(parts[6]),
        cabin = cabin,
    )
}

@Serializable
private data class DataFile(
    val lh: List<LhRow>,
    val mx: List<MxRow>,
)

@Serializable
private data class LhRow(
    val id: Int,
    val key: String = "",
    val out: Int,
    val ret: Int,
    val cabin: String,
    val airport: String = "",
    val airline: String = "",
    val pp: Double,
    val outStops: Int = 0,
    val retStops: Int? = null,
    val outSegs: List<List<String>> = emptyList(),
    val retSegs: List<List<String>>? = null,
    val retMissing: Boolean = false,
    val prev: Double? = null,
) {
    fun toItinerary(): Itinerary {
        val outLegs = outSegs.map { compactLeg(it, cabin) }
        val retLegs = retSegs?.map { compactLeg(it, cabin) }
        val missing = retMissing || retLegs.isNullOrEmpty()
        return assembleItinerary(
            id = id.toString(),
            pricePerPerson = pp,
            priceCurrency = "GBP",
            passengers = 2,
            cabinRaw = cabin,
            outboundLegs = outLegs,
            returnLegs = if (missing) null else retLegs,
            returnMissing = missing,
            source = "sample",
            sourceLabel = "sample",
            livePrice = false,
            fetchedAt = SampleData.run { "2026-10-11T18:00:00Z" },
            prevPrice = prev,
            notes = if (missing) "Return was not retrieved when this sample was saved." else "",
            returnDateHint = sampleDay(ret.toString()),
        )
    }
}

@Serializable
private data class MxRow(
    val id: String,
    val start: Int,
    val end: Int,
    val nights: Int = 0,
    val pp: Double,
    val prev: Double? = null,
    val outSegs: List<List<String>> = emptyList(),
    val retVerified: Boolean = false,
) {
    fun toItinerary(): Itinerary {
        val outLegs = outSegs.map { compactLeg(it, "Economy") }
        val endDate = sampleDay(end.toString())
        val ret =
            Leg(
                flightNumber = "AM 1740",
                airline = "Aeromexico",
                airlineCode = "AM",
                operatingCarrier = "Aeromexico",
                from = "MEX",
                to = "TPA",
                departTime = "15:40",
                arriveTime = "19:49",
                departDate = endDate,
                arriveDate = endDate,
                cabin = "Economy",
            )
        return assembleItinerary(
            id = id,
            pricePerPerson = pp,
            priceCurrency = "GBP",
            passengers = 1,
            cabinRaw = "economy",
            outboundLegs = outLegs,
            returnLegs = listOf(ret),
            returnMissing = false,
            source = "sample",
            sourceLabel = "sample",
            livePrice = false,
            fetchedAt = "2026-10-11T18:00:00Z",
            prevPrice = prev,
            notes =
                if (retVerified) {
                    ""
                } else {
                    "Return AM 1740 MEX 15:40 → TPA 19:49 is the flight seen on the earlier check; " +
                        "it was not re-fetched with this sample."
                },
            assumedReturn = !retVerified,
        )
    }
}

@Serializable
private data class VendorFile(
    val mk: String,
    val vendor: String,
    val cab: String = "",
    val trust: String = "caution",
    val note: String = "",
    val rt: Double? = null,
    val out: Double? = null,
    val back: Double? = null,
    val rtb: Double? = null,
    val sb: Double? = null,
    val allin: Double? = null,
    val how: String = "",
    val bags: String = "",
    val mix: Boolean = false,
    val fresh: Boolean = false,
    val chg: Int? = null,
    @SerialName("new") val isNew: Boolean = false,
) {
    fun toRow(links: List<BookLink>) =
        VendorRow(
            vendor = vendor,
            market = mk,
            marketLabel = VendorMath.MARKET_LABELS[mk] ?: mk.uppercase(),
            currency = Fx.currencyOf(mk),
            fareBrand = cab,
            trust = trust,
            trustLabel = VendorMath.TRUST_LABELS[trust] ?: trust,
            note = note,
            roundTrip = rt,
            oneWayOut = out,
            oneWayBack = back,
            roundTripWithBag = rtb,
            oneWaysWithBag = sb,
            allInGbp = allin,
            how = how,
            bags = bags,
            mix = mix,
            fresh = fresh,
            priceChangeGbp = chg,
            isNew = isNew,
            fetchedAt = if (fresh) "2026-10-15T01:16:00+02:00" else "2026-10-14T22:50:00+02:00",
            links = links,
        )
}

private fun mexicoLinks(market: String): List<BookLink> {
    val (gl, cur) =
        when (market) {
            "us" -> "us" to "USD"
            "mx" -> "mx" to "MXN"
            "ca" -> "ca" to "CAD"
            "es" -> "es" to "EUR"
            else -> "uk" to "GBP"
        }
    val out =
        Leg("AM 1741", "Aeromexico", "AM", "Aeromexico", "TPA", "MEX", "09:23", "11:50", "2026-12-25", "2026-12-25", cabin = "Economy")
    val back =
        Leg("AM 1740", "Aeromexico", "AM", "Aeromexico", "MEX", "TPA", "15:40", "19:49", "2026-12-29", "2026-12-29", cabin = "Economy")
    return listOf(
        BookLink(
            "rt",
            "RT",
            GoogleFlightsLink.roundTrip("2026-12-25", "2026-12-29", "TPA", "MEX", "economy", listOf(out), listOf(back), cur, gl),
        ),
        BookLink("out", "Out", GoogleFlightsLink.oneWay("2026-12-25", "TPA", "MEX", "economy", listOf(out), cur, gl)),
        BookLink("back", "Back", GoogleFlightsLink.oneWay("2026-12-29", "MEX", "TPA", "economy", listOf(back), cur, gl)),
    )
}

@Serializable
private data class SelectedFile(
    val lh: List<SelectedLh> = emptyList(),
)

@Serializable
private data class SelectedLh(
    val key: String = "",
    val dep: String? = null,
    val cabin: String? = null,
    val price: Double? = null,
    val price_out_stage: Double? = null,
    val outSegs: List<SelectedSeg> = emptyList(),
    val retSegs: List<SelectedSeg>? = null,
    val missing: Boolean = false,
) {
    fun toItinerary(index: Int): Itinerary? {
        if (missing || outSegs.isEmpty()) return null
        val cabinRaw = cabin ?: "economy"
        val outLegs = outSegs.map { it.toLeg(cabinRaw) }
        val retLegs = retSegs?.map { it.toLeg(cabinRaw) }
        return assembleItinerary(
            id = key.ifBlank { index.toString() },
            pricePerPerson = price ?: price_out_stage,
            priceCurrency = "GBP",
            passengers = 1,
            cabinRaw = cabinRaw,
            outboundLegs = outLegs,
            returnLegs = retLegs,
            returnMissing = retLegs.isNullOrEmpty(),
            source = "sample",
            sourceLabel = "sample",
            livePrice = false,
            fetchedAt = "2026-10-11T18:00:00Z",
        )
    }
}

@Serializable
private data class SelectedSeg(
    val fn: String,
    val airline: String = "",
    val op: String? = null,
    val frm: String,
    val dep: String,
    val ddate: String,
    val to: String,
    val arr: String,
    val adate: String,
    val cls: String? = null,
) {
    fun toLeg(cabinFallback: String): Leg {
        val code = fn.substringBefore(" ").uppercase()
        val timeOf = { raw: String -> if (raw.length >= 16 && raw[4] == '-') raw.substring(11).take(5) else raw }
        val dateOf = { rawTime: String, rawDate: String ->
            if (rawDate.length >= 10 && rawDate[4] == '-') {
                rawDate.take(10)
            } else if (rawTime.length >= 10 && rawTime[4] == '-') {
                rawTime.take(10)
            } else {
                sampleDay(rawDate)
            }
        }
        return Leg(
            flightNumber = fn,
            airline = airline.ifBlank { AIRLINE_NAMES[code] ?: code },
            airlineCode = code,
            operatingCarrier = op ?: airline.ifBlank { code },
            from = frm,
            to = to,
            departTime = normalizeTime(timeOf(dep)),
            arriveTime = normalizeTime(timeOf(arr)),
            departDate = dateOf(dep, ddate),
            arriveDate = dateOf(arr, adate),
            cabin = cabinLabelOf(cabinKeyOf(cls ?: cabinFallback)),
        )
    }
}
