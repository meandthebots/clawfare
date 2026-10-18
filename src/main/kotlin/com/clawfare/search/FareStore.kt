package com.clawfare.search

import com.clawfare.db.FlightDto
import com.clawfare.db.FlightProviderTokens
import com.clawfare.db.FlightQueries
import com.clawfare.db.InvestigationDto
import com.clawfare.db.InvestigationQueries
import com.clawfare.db.Investigations
import com.clawfare.db.PriceHistoryDto
import com.clawfare.db.PriceHistoryQueries
import com.clawfare.db.SellerOffers
import com.clawfare.model.FlightEntry
import com.clawfare.model.FlightLeg
import com.clawfare.model.FlightSegment
import com.clawfare.model.FlightValidator
import com.clawfare.model.StoredLayover
import com.clawfare.model.TicketStructure
import com.clawfare.model.TripType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

/**
 * Writes provider results and the Florida sample into the investigation database,
 * and reads them back out as the view model the web UI already renders.
 */
object FareStore : FareSink {
    private val flightJson =
        kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }
    private val maps = MapSerializer(String.serializer(), String.serializer())
    private val links = ListSerializer(BookLink.serializer())
    private val tags = ListSerializer(String.serializer())

    override fun save(stored: StoredResult): ResultSet {
        val slug = stored.result.id
        val skipped = mutableListOf<String>()
        transaction {
            writeInvestigation(stored)
            for (itinerary in stored.result.itineraries) {
                upsert(slug, itinerary, "longhaul", stored, skipped)
            }
            for (itinerary in stored.result.addons) {
                upsert(slug, itinerary, "addon", stored, skipped)
            }
            if (stored.result.vendors.isNotEmpty()) {
                val target = stored.result.vendorItineraryId
                val flightId =
                    if (target != null) {
                        flightByDisplay(slug, target)?.id
                    } else {
                        FlightQueries.listByInvestigation(slug).firstOrNull()?.id
                    }
                if (flightId != null) {
                    replaceOffers(slug, flightId, stored.result.vendors)
                }
            }
            if (skipped.isNotEmpty()) {
                val inv = InvestigationQueries.getBySlug(slug)
                if (inv != null) {
                    val presentation = decodePresentation(inv.presentationJson)
                    val merged =
                        presentation.copy(
                            warnings = (presentation.warnings + skipped).distinct(),
                        )
                    Investigations.update({ Investigations.slug eq slug }) {
                        it[presentationJson] = AppJson.encodeToString(Presentation.serializer(), merged)
                    }
                }
            }
        }
        return project(slug) ?: stored.result
    }

    override fun load(id: String): StoredResult? {
        val result = project(id) ?: return null
        return StoredResult(result, loadTokens(id))
    }

    override fun list(): List<ResultSet> =
        InvestigationQueries.listAll().map { inv ->
            val count = FlightQueries.listByInvestigation(inv.slug).size
            val presentation = decodePresentation(inv.presentationJson)
            ResultSet(
                id = inv.slug,
                title = presentation.title.ifBlank { inv.slug },
                subtitle = "$count itineraries · ${inv.currency}",
                fetchedAt = presentation.fetchedAt,
                currency = inv.currency,
                scenarioLabel = inv.scenario,
            )
        }

    fun project(slug: String): ResultSet? {
        val inv = InvestigationQueries.getBySlug(slug) ?: return null
        val presentation = decodePresentation(inv.presentationJson)
        val flights = FlightQueries.listByInvestigation(slug)
        val longHaul = mutableListOf<Itinerary>()
        val addons = mutableListOf<Itinerary>()
        for (flight in flights) {
            val itinerary = toItinerary(flight) ?: continue
            if ("addon" in readTags(flight.tags)) addons += itinerary else longHaul += itinerary
        }
        val ordered =
            if (inv.scenario == "florida") {
                longHaul
            } else {
                longHaul.sortedWith(compareBy({ it.outboundDate }, { it.pricePerPerson ?: Double.MAX_VALUE }))
            }
        val cells =
            if (inv.scenario == "florida") {
                buildList {
                    for (primary in ordered) {
                        add(Feasibility.evaluate(primary, null))
                        for (addon in addons) add(Feasibility.evaluate(primary, addon))
                    }
                }
            } else {
                emptyList()
            }
        val query = inv.queryJson?.let { runCatching { AppJson.decodeFromString(SearchQuery.serializer(), it) }.getOrNull() }
        return ResultSet(
            id = inv.slug,
            title = presentation.title.ifBlank { inv.slug },
            subtitle = presentation.subtitle,
            fetchedAt = presentation.fetchedAt,
            currency = "GBP",
            checkedBags = inv.checkedBags,
            passengers = inv.passengers,
            itineraries = ordered,
            addons = addons,
            cells = cells,
            vendors = loadOffers(slug),
            vendorIntro = presentation.vendorIntro,
            headerNotes = presentation.headerNotes,
            suggestedFilters = presentation.suggestedFilters,
            warnings = presentation.warnings,
            scenarioLabel = inv.scenario,
            query = query,
            vendorItineraryId = vendorTarget(slug),
        )
    }

    fun storedQuery(slug: String): SearchQuery? {
        val raw = InvestigationQueries.getBySlug(slug)?.queryJson ?: return null
        return runCatching { AppJson.decodeFromString(SearchQuery.serializer(), raw) }.getOrNull()
    }

    /**
     * Copy one itinerary onto another investigation. Used by the "track" button.
     */
    fun track(
        sourceSlug: String,
        itineraryId: String,
        targetSlug: String,
    ): String {
        val source = project(sourceSlug) ?: error("Investigation '$sourceSlug' was not found")
        val chosen =
            (source.itineraries + source.addons).find { it.id == itineraryId }
                ?: error("Itinerary '$itineraryId' was not found")
        val clean = targetSlug.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-')
        require(clean.isNotEmpty()) { "Give the investigation a short name, like florida-dec." }
        val subset =
            source.copy(
                id = clean,
                title = clean,
                subtitle = chosen.airlineSummary,
                itineraries = listOf(chosen),
                addons = emptyList(),
                cells = emptyList(),
                vendors = emptyList(),
                scenarioLabel = null,
                headerNotes = emptyList(),
                vendorIntro = emptyList(),
                query = null,
            )
        save(StoredResult(subset))
        return clean
    }

    private fun writeInvestigation(stored: StoredResult) {
        val result = stored.result
        val slug = result.id
        val existing = InvestigationQueries.getBySlug(slug)
        val query = result.query
        val routeFlights = result.itineraries.ifEmpty { result.addons }
        val originCodes =
            (query?.origins ?: routeFlights.map { it.origin })
                .map { it.uppercase() }
                .filter { it.length == 3 }
                .distinct()
        val destinationCodes =
            (query?.destinations ?: routeFlights.map { it.destination })
                .map { it.uppercase() }
                .filter { it.length == 3 }
                .distinct()
        val primaryOrigin = originCodes.firstOrNull() ?: existing?.origin ?: "LHR"
        val primaryDestination = destinationCodes.firstOrNull() ?: existing?.destination ?: "TPA"
        val outs = routeFlights.map { it.outboundDate }.filter { it.length == 10 }.sorted()
        val rets = routeFlights.mapNotNull { it.returnDate }.filter { it.length == 10 }.sorted()
        val departFrom = query?.outboundFrom?.takeIf { it.length == 10 } ?: outs.firstOrNull() ?: existing?.departStart ?: "1970-01-01"
        val departTo = query?.outboundTo?.takeIf { it.length == 10 } ?: outs.lastOrNull() ?: departFrom
        val presentation =
            Presentation(
                title = result.title,
                subtitle = result.subtitle,
                headerNotes = result.headerNotes,
                vendorIntro = result.vendorIntro,
                suggestedFilters = result.suggestedFilters,
                warnings = result.warnings,
                fetchedAt = result.fetchedAt,
            )
        val now = Instant.now().toString()
        val dto =
            InvestigationDto(
                slug = slug,
                origin = primaryOrigin,
                destination = primaryDestination,
                departStart = departFrom,
                departEnd = departTo,
                returnStart = query?.returnFrom ?: rets.firstOrNull() ?: existing?.returnStart,
                returnEnd = query?.returnTo ?: rets.lastOrNull() ?: existing?.returnEnd,
                cabinClass = query?.cabin ?: existing?.cabinClass ?: "economy",
                maxStops = if (query?.nonstopOnly == true) 0 else 2,
                maxPrice = existing?.maxPrice,
                departAfter = existing?.departAfter,
                departBefore = existing?.departBefore,
                minTripDays = existing?.minTripDays,
                maxTripDays = existing?.maxTripDays,
                mustIncludeDate = existing?.mustIncludeDate,
                maxLayoverMinutes = existing?.maxLayoverMinutes,
                excludedAirlines = existing?.excludedAirlines,
                includedAirlines = existing?.includedAirlines,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                origins = originCodes.joinToString(",").ifBlank { null },
                destinations = destinationCodes.joinToString(",").ifBlank { null },
                checkedBags = result.checkedBags,
                passengers = result.passengers,
                currency = "GBP",
                nights = query?.nights,
                scenario = result.scenarioLabel ?: existing?.scenario,
                queryJson = query?.let { AppJson.encodeToString(SearchQuery.serializer(), it) } ?: existing?.queryJson,
                presentationJson = AppJson.encodeToString(Presentation.serializer(), presentation),
            )
        if (existing == null) {
            InvestigationQueries.create(dto)
        } else {
            Investigations.update({ Investigations.slug eq slug }) {
                it[origin] = dto.origin
                it[destination] = dto.destination
                it[departStart] = dto.departStart
                it[departEnd] = dto.departEnd
                it[returnStart] = dto.returnStart
                it[returnEnd] = dto.returnEnd
                it[cabinClass] = dto.cabinClass
                it[maxStops] = dto.maxStops
                it[updatedAt] = dto.updatedAt
                it[origins] = dto.origins
                it[destinations] = dto.destinations
                it[checkedBags] = dto.checkedBags
                it[passengers] = dto.passengers
                it[currency] = dto.currency
                it[nights] = dto.nights
                it[scenario] = dto.scenario
                it[queryJson] = dto.queryJson
                it[presentationJson] = dto.presentationJson
            }
        }
    }

    private fun upsert(
        slug: String,
        itinerary: Itinerary,
        role: String,
        stored: StoredResult,
        skipped: MutableList<String>,
    ) {
        val built = buildFlight(slug, itinerary, role, stored.result.checkedBags)
        if (built == null) {
            skipped += "Skipped ${itinerary.id}: no positive fare or no flights to store."
            return
        }
        val (dto, entry, current, previous) = built
        val errors = FlightValidator.validate(entry)
        if (!errors.isValid) {
            skipped += "Skipped ${itinerary.id}: ${errors.errors.joinToString("; ")}"
            return
        }
        val existing = FlightQueries.getById(dto.id)
        if (existing == null) {
            FlightQueries.create(dto)
            if (previous != null) PriceHistoryQueries.create(previous)
            PriceHistoryQueries.create(current)
        } else {
            FlightQueries.update(dto)
            val latest = PriceHistoryQueries.getLatest(dto.id)
            val changed =
                latest == null ||
                    latest.amount != current.amount ||
                    latest.currency != current.currency ||
                    latest.checkedAt != current.checkedAt
            if (changed) PriceHistoryQueries.create(current)
        }
        val token = stored.tokens[itinerary.id]
        if (token != null) writeToken(dto.id, token)
    }

    private fun buildFlight(
        slug: String,
        itinerary: Itinerary,
        role: String,
        checkedBags: Int,
    ): Built? {
        val amount = itinerary.priceOriginal ?: itinerary.pricePerPerson ?: return null
        if (amount <= 0.0 || itinerary.outbound.legs.isEmpty()) return null
        val currency = itinerary.priceCurrency.uppercase().ifBlank { "GBP" }
        val share = bookingShareLink(itinerary)
        val id = FlightValidator.generateId(share)
        val outbound = toSegment(itinerary.outbound)
        val returning = itinerary.returning?.takeIf { it.legs.isNotEmpty() }?.let { toSegment(it) }
        val returnMissing = returning == null && !itinerary.returnDate.isNullOrBlank()
        val trip = if (returning != null) TripType.ROUND_TRIP else TripType.ONE_WAY
        val checkedAt = itinerary.fetchedAt ?: Instant.now().toString()
        val tagList = tagList(itinerary, role)
        val cabinKeys = (outbound.legs + (returning?.legs ?: emptyList())).mapNotNull { it.cabinClass }.distinct()
        val entry =
            FlightEntry(
                id = id,
                shareLink = share,
                source = itinerary.source.ifBlank { "manual" },
                tripType = trip,
                ticketStructure = TicketStructure.SINGLE,
                priceCurrency = currency,
                priceAmount = amount,
                priceMarket = marketOf(currency),
                capturedAt = checkedAt,
                priceCheckedAt = checkedAt,
                origin = outbound.departAirport,
                destination = outbound.arriveAirport,
                outbound = outbound,
                returnSegment = returning,
                notes = itinerary.notes.ifBlank { null },
                tags = tagList,
                bookingClass = itinerary.cabinKey.ifBlank { null },
                cabinMixed = cabinKeys.size > 1,
            )
        val dto =
            FlightDto(
                id = id,
                investigationSlug = slug,
                shareLink = share,
                source = entry.source,
                tripType = if (trip == TripType.ROUND_TRIP) "round_trip" else "one_way",
                ticketStructure = "single",
                priceAmount = amount,
                priceCurrency = currency,
                priceMarket = entry.priceMarket,
                priceCheckedAt = checkedAt,
                origin = entry.origin,
                destination = entry.destination,
                outboundJson = flightJson.encodeToString(FlightSegment.serializer(), outbound),
                returnJson = returning?.let { flightJson.encodeToString(FlightSegment.serializer(), it) },
                bookingClass = entry.bookingClass,
                cabinMixed = entry.cabinMixed == true,
                notes = entry.notes,
                tags = flightJson.encodeToString(tags, tagList),
                capturedAt = checkedAt,
                checkedBags = checkedBags,
                passengers = itinerary.passengers,
                sourceLabel = itinerary.sourceLabel.ifBlank { null },
                livePrice = itinerary.livePrice,
                displayId = itinerary.id,
                plannedReturnDate = if (returnMissing) itinerary.returnDate else null,
                linksJson = flightJson.encodeToString(links, itinerary.links),
                cabinClass = itinerary.cabinKey.ifBlank { null },
            )
        val current =
            PriceHistoryDto(
                flightId = id,
                amount = amount,
                currency = currency,
                sourceUrl = share,
                checkedAt = checkedAt,
                source = entry.source,
                priceMarket = entry.priceMarket,
            )
        val previous =
            itinerary.prevPrice?.takeIf { it > 0.0 }?.let { prev ->
                PriceHistoryDto(
                    flightId = id,
                    amount = prev,
                    currency = "GBP",
                    sourceUrl = share,
                    checkedAt = earlier(checkedAt),
                    source = entry.source,
                    priceMarket = "UK",
                )
            }
        return Built(dto, entry, current, previous)
    }

    private fun toItinerary(flight: FlightDto): Itinerary? {
        val outbound =
            runCatching { flightJson.decodeFromString(FlightSegment.serializer(), flight.outboundJson) }.getOrNull() ?: return null
        val returning = flight.returnJson?.let { runCatching { flightJson.decodeFromString(FlightSegment.serializer(), it) }.getOrNull() }
        val history = PriceHistoryQueries.getByFlightId(flight.id)
        val latest = history.lastOrNull() ?: return null
        val previous = history.dropLast(1).lastOrNull()
        val tagList = readTags(flight.tags)
        val returnMissing = "return-missing" in tagList || (returning == null && !flight.plannedReturnDate.isNullOrBlank())
        val outLegs = outbound.legs.map { toViewLeg(it) }
        val retLegs = returning?.legs?.map { toViewLeg(it) }
        val built =
            assembleItinerary(
                id = flight.displayId ?: flight.id,
                pricePerPerson = latest.amount,
                priceCurrency = latest.currency,
                passengers = flight.passengers,
                cabinRaw = flight.cabinClass ?: flight.bookingClass ?: "economy",
                outboundLegs = outLegs,
                returnLegs = retLegs,
                returnMissing = returnMissing,
                source = flight.source,
                sourceLabel = flight.sourceLabel ?: "live",
                livePrice = flight.livePrice,
                fetchedAt = latest.checkedAt,
                prevPrice = previous?.let { Fx.toGbpOrNull(it.amount, it.currency) },
                notes = flight.notes.orEmpty(),
                assumedReturn = "assumed-return" in tagList,
                outboundDuration = outbound.durationMinutes,
                returnDuration = returning?.durationMinutes,
                apiLayoversOut = (outbound.layovers ?: emptyList()).map { Layover(it.airport, it.minutes) },
                apiLayoversRet = (returning?.layovers ?: emptyList()).map { Layover(it.airport, it.minutes) },
                returnDateHint = flight.plannedReturnDate,
            )
        val storedLinks = flight.linksJson?.let { runCatching { flightJson.decodeFromString(links, it) }.getOrNull() }.orEmpty()
        return if (storedLinks.isEmpty()) built else built.copy(links = storedLinks)
    }

    private fun toSegment(direction: Direction): FlightSegment {
        val legs = direction.legs.map { toStoredLeg(it) }
        val first = legs.first()
        val last = legs.last()
        val derived = computeLayovers(direction.legs)
        val api = direction.layovers
        val keepApi =
            api.isNotEmpty() &&
                (
                    derived.size != api.size ||
                        derived.zip(api).any { (left, right) -> left.minutes != right.minutes || left.airport != right.airport }
                )
        val layoverMinutes = (if (keepApi) api else derived).sumOf { it.minutes }
        val duration =
            direction.durationMinutes?.takeIf { it > 0 }
                ?: (legs.sumOf { it.durationMinutes } + layoverMinutes).takeIf { it > 0 }
                ?: 1
        return FlightSegment(
            departAirport = first.departAirport,
            departTime = first.departTime,
            arriveAirport = last.arriveAirport,
            arriveTime = last.arriveTime,
            durationMinutes = duration,
            stops = (legs.size - 1).coerceAtLeast(0),
            legs = legs,
            layovers = if (keepApi) api.map { StoredLayover(it.airport, it.minutes) } else null,
        )
    }

    private fun toStoredLeg(leg: Leg): FlightLeg {
        val code = leg.airlineCode.uppercase().ifBlank { GoogleFlightsLink.splitFlight(leg.flightNumber, "").first }
        val depart = stamp(leg.departDate, leg.departTime)
        val arrive = stamp(leg.arriveDate.ifBlank { leg.departDate }, leg.arriveTime.ifBlank { leg.departTime })
        val clockMinutes =
            minutesBetween(
                leg.departDate,
                leg.departTime,
                leg.arriveDate.ifBlank { leg.departDate },
                leg.arriveTime.ifBlank { leg.departTime },
            )
        val duration =
            when {
                leg.durationMinutes != null && leg.durationMinutes > 0 -> leg.durationMinutes
                clockMinutes != null && clockMinutes > 0 -> clockMinutes
                else -> 1
            }
        return FlightLeg(
            flightNumber = leg.flightNumber.ifBlank { code },
            airline = leg.airline.ifBlank { AIRLINE_NAMES[code] ?: code },
            airlineCode = code,
            cabinClass = leg.cabin?.let { cabinLabelOf(cabinKeyOf(it)) },
            departAirport = leg.from.uppercase(),
            departTime = depart,
            arriveAirport = leg.to.uppercase(),
            arriveTime = arrive,
            durationMinutes = duration,
            aircraft = leg.aircraft,
            operatingCarrier = leg.operatingCarrier.ifBlank { null },
            operatingCarrierCode = leg.operatingCarrier.takeIf { it.length in 2..3 && it.all { ch -> ch.isLetter() } }?.uppercase(),
        )
    }

    private fun toViewLeg(leg: FlightLeg): Leg =
        Leg(
            flightNumber = leg.flightNumber,
            airline = leg.airline,
            airlineCode = leg.airlineCode,
            operatingCarrier = leg.operatingCarrier ?: leg.airline,
            from = leg.departAirport,
            to = leg.arriveAirport,
            departTime = normalizeTime(leg.departTime.substringAfter('T').take(5)),
            arriveTime = normalizeTime(leg.arriveTime.substringAfter('T').take(5)),
            departDate = leg.departTime.take(10),
            arriveDate = leg.arriveTime.take(10),
            durationMinutes = leg.durationMinutes,
            cabin = leg.cabinClass,
            aircraft = leg.aircraft,
        )

    private fun replaceOffers(
        slug: String,
        flightId: String,
        vendors: List<VendorRow>,
    ) {
        SellerOffers.deleteWhere { SellerOffers.flightId eq flightId }
        vendors.forEachIndexed { index, row ->
            val fare = row.roundTrip ?: row.oneWayOut
            val bag =
                when {
                    row.roundTrip != null && row.roundTripWithBag != null -> row.roundTripWithBag - row.roundTrip
                    row.oneWaysWithBag != null && row.oneWayOut != null && row.oneWayBack != null ->
                        row.oneWaysWithBag - row.oneWayOut - row.oneWayBack
                    else -> null
                }
            SellerOffers.insert {
                it[investigationSlug] = slug
                it[SellerOffers.flightId] = flightId
                it[ordinal] = index
                it[vendor] = row.vendor
                it[market] = row.market
                it[marketLabel] = row.marketLabel
                it[currency] = row.currency
                it[fareBrand] = row.fareBrand
                it[trust] = row.trust
                it[trustLabel] = row.trustLabel
                it[notes] = row.note
                it[roundTrip] = row.roundTrip
                it[oneWayOut] = row.oneWayOut
                it[oneWayBack] = row.oneWayBack
                it[roundTripWithBag] = row.roundTripWithBag
                it[oneWaysWithBag] = row.oneWaysWithBag
                it[allInGbp] = row.allInGbp
                it[fareAmount] = fare
                it[bagFee] = bag
                it[how] = row.how
                it[bags] = row.bags
                it[mix] = if (row.mix) 1 else 0
                it[fresh] = if (row.fresh) 1 else 0
                it[priceChangeGbp] = row.priceChangeGbp
                it[isNew] = if (row.isNew) 1 else 0
                it[fetchedAt] = row.fetchedAt
                it[linksJson] = flightJson.encodeToString(links, row.links)
            }
        }
    }

    private fun loadOffers(slug: String): List<VendorRow> =
        transaction {
            SellerOffers
                .selectAll()
                .where { SellerOffers.investigationSlug eq slug }
                .map { it.toVendor() }
                .sortedBy { it.second }
                .map { it.first }
        }

    private fun ResultRow.toVendor(): Pair<VendorRow, Int> {
        val stored = this[SellerOffers.linksJson]?.let { runCatching { flightJson.decodeFromString(links, it) }.getOrNull() }.orEmpty()
        val row =
            VendorRow(
                vendor = this[SellerOffers.vendor],
                market = this[SellerOffers.market],
                marketLabel = this[SellerOffers.marketLabel] ?: this[SellerOffers.market].uppercase(),
                currency = this[SellerOffers.currency],
                fareBrand = this[SellerOffers.fareBrand].orEmpty(),
                trust = this[SellerOffers.trust] ?: "caution",
                trustLabel = this[SellerOffers.trustLabel] ?: "OTA · caution",
                note = this[SellerOffers.notes].orEmpty(),
                roundTrip = this[SellerOffers.roundTrip],
                oneWayOut = this[SellerOffers.oneWayOut],
                oneWayBack = this[SellerOffers.oneWayBack],
                roundTripWithBag = this[SellerOffers.roundTripWithBag],
                oneWaysWithBag = this[SellerOffers.oneWaysWithBag],
                allInGbp = this[SellerOffers.allInGbp],
                how = this[SellerOffers.how].orEmpty(),
                bags = this[SellerOffers.bags].orEmpty(),
                mix = this[SellerOffers.mix] == 1,
                fresh = this[SellerOffers.fresh] == 1,
                priceChangeGbp = this[SellerOffers.priceChangeGbp],
                isNew = this[SellerOffers.isNew] == 1,
                fetchedAt = this[SellerOffers.fetchedAt],
                links = stored,
            )
        return row to this[SellerOffers.ordinal]
    }

    private fun writeToken(
        flightId: String,
        token: FlightTokens,
    ) {
        FlightProviderTokens.deleteWhere { FlightProviderTokens.flightId eq flightId }
        FlightProviderTokens.insert {
            it[FlightProviderTokens.flightId] = flightId
            it[provider] = token.provider
            it[departureToken] = token.departureToken
            it[bookingToken] = token.bookingToken
            it[paramsJson] = AppJson.encodeToString(maps, token.params)
        }
    }

    private fun loadTokens(slug: String): Map<String, FlightTokens> {
        val flights = FlightQueries.listByInvestigation(slug)
        if (flights.isEmpty()) return emptyMap()
        val byId = flights.associateBy { it.id }
        return transaction {
            FlightProviderTokens
                .selectAll()
                .mapNotNull { row ->
                    if (row[FlightProviderTokens.flightId] !in byId) return@mapNotNull null
                    val flight = byId[row[FlightProviderTokens.flightId]] ?: return@mapNotNull null
                    val key = flight.displayId ?: flight.id
                    key to
                        FlightTokens(
                            provider = row[FlightProviderTokens.provider],
                            departureToken = row[FlightProviderTokens.departureToken],
                            bookingToken = row[FlightProviderTokens.bookingToken],
                            params =
                                row[FlightProviderTokens.paramsJson]?.let {
                                    runCatching { AppJson.decodeFromString(maps, it) }.getOrDefault(emptyMap())
                                } ?: emptyMap(),
                        )
                }.toMap()
        }
    }

    private fun vendorTarget(slug: String): String? =
        transaction {
            SellerOffers
                .selectAll()
                .where { SellerOffers.investigationSlug eq slug }
                .firstOrNull()
                ?.get(SellerOffers.flightId)
        }?.let { flightId ->
            FlightQueries.getById(flightId)?.displayId ?: flightId
        }

    private fun flightByDisplay(
        slug: String,
        displayId: String,
    ): FlightDto? = FlightQueries.listByInvestigation(slug).find { it.displayId == displayId || it.id == displayId }

    private fun decodePresentation(raw: String?): Presentation =
        raw?.let { runCatching { AppJson.decodeFromString(Presentation.serializer(), it) }.getOrNull() } ?: Presentation(title = "")

    private fun readTags(raw: String?): List<String> =
        raw?.let { runCatching { flightJson.decodeFromString(tags, it) }.getOrNull() } ?: emptyList()

    private data class Built(
        val dto: FlightDto,
        val entry: FlightEntry,
        val current: PriceHistoryDto,
        val previous: PriceHistoryDto?,
    )
}

object SampleSeeder {
    fun ensure(): ResultSet {
        val existing = FareStore.project(SampleData.ID)
        if (existing != null && existing.itineraries.isNotEmpty()) return existing
        val sample = SampleData.florida().copy(vendorItineraryId = "H")
        return FareStore.save(StoredResult(sample))
    }
}

private fun tagList(
    itinerary: Itinerary,
    role: String,
): List<String> {
    val out = mutableListOf(role)
    if (itinerary.sourceLabel.isNotBlank()) out += itinerary.sourceLabel
    if (itinerary.returnMissing || (itinerary.returning == null && !itinerary.returnDate.isNullOrBlank())) {
        out += "return-missing"
    }
    if (itinerary.assumedReturn) out += "assumed-return"
    return out.distinct()
}

private fun bookingShareLink(itinerary: Itinerary): String {
    val exact = itinerary.links.firstOrNull { it.kind == "exact" || it.kind == "partial" }?.url
    if (!exact.isNullOrBlank() && "tfs=" in exact) {
        val tfs = exact.substringAfter("tfs=").substringBefore("&")
        return "https://www.google.com/travel/flights/booking?tfs=$tfs"
    }
    val from = itinerary.outbound.legs.firstOrNull()
    val generated =
        if (from == null) {
            "https://www.google.com/travel/flights/booking?tfs=${sha256(itinerary.id).take(20)}"
        } else {
            linksFor(
                itinerary.cabinKey,
                itinerary.outbound.legs,
                itinerary.returning?.legs,
                itinerary.returnDate,
            ).firstOrNull { it.kind == "exact" || it.kind == "partial" }?.url
        }
    if (!generated.isNullOrBlank() && "tfs=" in generated) {
        val tfs = generated.substringAfter("tfs=").substringBefore("&")
        return "https://www.google.com/travel/flights/booking?tfs=$tfs"
    }
    return "https://www.google.com/travel/flights/booking?tfs=${sha256(itinerary.id)}"
}

private fun stamp(
    date: String,
    time: String,
): String {
    val day = if (date.length >= 10 && date[4] == '-') date.take(10) else "1970-01-01"
    return day + "T" + normalizeTime(time) + ":00Z"
}

private fun earlier(checkedAt: String): String =
    runCatching { Instant.parse(checkedAt).minusSeconds(7 * 24 * 3600).toString() }
        .getOrDefault("2000-01-01T00:00:00Z")

private fun marketOf(currency: String): String =
    when (currency.uppercase()) {
        "USD" -> "US"
        "EUR" -> "EU"
        "MXN" -> "MX"
        "CAD" -> "CA"
        "JPY" -> "JP"
        else -> "UK"
    }
