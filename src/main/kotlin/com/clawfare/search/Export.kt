package com.clawfare.search

object Export {
    fun itineraryCsv(
        result: ResultSet,
        filter: FilterSpec,
    ): String {
        val rows = result.itineraries.filter { Filters.matches(it, filter) }
        val header =
            listOf(
                "id",
                "origin",
                "destination",
                "outbound_date",
                "return_date",
                "nights",
                "cabin",
                "stops_out",
                "stops_return",
                "price_pp_gbp",
                "total_gbp",
                "passengers",
                "currency_original",
                "price_original",
                "airlines",
                "outbound_flights",
                "return_flights",
                "layovers",
                "max_layover_min",
                "travel_min",
                "eu_premium",
                "source",
                "source_label",
                "live_price",
                "fetched_at",
                "google_flights",
            )
        return buildString {
            append(header.joinToString(","))
            append('\n')
            for (it in rows) {
                val link =
                    it.links
                        .firstOrNull { l -> l.kind == "exact" || l.kind == "partial" }
                        ?.url
                        .orEmpty()
                val values =
                    listOf(
                        it.id,
                        it.origin,
                        it.destination,
                        it.outboundDate,
                        it.returnDate.orEmpty(),
                        it.nights?.toString().orEmpty(),
                        it.cabin,
                        it.stopsOut.toString(),
                        it.stopsRet?.toString().orEmpty(),
                        it.pricePerPerson?.toString().orEmpty(),
                        it.pricePerPerson?.let { p -> (p * it.passengers).toString() }.orEmpty(),
                        it.passengers.toString(),
                        it.priceCurrency,
                        it.priceOriginal?.toString().orEmpty(),
                        it.airlineSummary,
                        flights(it.outbound),
                        flights(it.returning),
                        it.layoverMinutes.joinToString(" "),
                        it.maxLayoverMinutes?.toString().orEmpty(),
                        it.travelMinutes?.toString().orEmpty(),
                        it.euPremium.toString(),
                        it.source,
                        it.sourceLabel,
                        it.livePrice.toString(),
                        it.fetchedAt.orEmpty(),
                        link,
                    )
                append(values.joinToString(",") { csv(it) })
                append('\n')
            }
        }
    }

    fun vendorCsv(result: ResultSet): String {
        val header =
            listOf(
                "vendor",
                "market",
                "currency",
                "trust",
                "note",
                "fare",
                "round_trip",
                "one_way_out",
                "one_way_back",
                "round_trip_with_bag",
                "one_ways_with_bag",
                "all_in_gbp",
                "how",
                "bags",
                "mix",
                "fresh",
                "fetched_at",
                "link_rt",
                "link_out",
                "link_back",
            )
        return buildString {
            append(header.joinToString(","))
            append('\n')
            for (row in result.vendors) {
                val values =
                    listOf(
                        row.vendor,
                        row.market,
                        row.currency,
                        row.trust,
                        row.note,
                        row.fareBrand,
                        row.roundTrip?.toString().orEmpty(),
                        row.oneWayOut?.toString().orEmpty(),
                        row.oneWayBack?.toString().orEmpty(),
                        row.roundTripWithBag?.toString().orEmpty(),
                        row.oneWaysWithBag?.toString().orEmpty(),
                        row.allInGbp?.toString().orEmpty(),
                        row.how,
                        row.bags,
                        row.mix.toString(),
                        row.fresh.toString(),
                        row.fetchedAt.orEmpty(),
                        row.links
                            .find { it.kind == "rt" }
                            ?.url
                            .orEmpty(),
                        row.links
                            .find { it.kind == "out" }
                            ?.url
                            .orEmpty(),
                        row.links
                            .find { it.kind == "back" }
                            ?.url
                            .orEmpty(),
                    )
                append(values.joinToString(",") { csv(it) })
                append('\n')
            }
        }
    }

    /** Drop the result into the app page so the file renders with no server. */
    fun html(
        page: String,
        result: ResultSet,
    ): String {
        val json = AppJson.encodeToString(ResultSet.serializer(), result).replace("<", "\\u003c")
        val injected = page.replace("window.__SNAPSHOT__ = null", "window.__SNAPSHOT__ = $json")
        check(injected != page) { "App page is missing the snapshot marker." }
        return injected
    }

    private fun flights(direction: Direction?): String =
        direction
            ?.legs
            ?.joinToString(" | ") { leg ->
                "${leg.flightNumber} ${leg.from} ${leg.departTime}-${leg.to} ${leg.arriveTime} (${leg.operatingCarrier})"
            }.orEmpty()

    private fun csv(value: String): String {
        val safe = if (value.firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else {
            safe
        }
    }
}
