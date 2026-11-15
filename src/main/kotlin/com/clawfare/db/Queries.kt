package com.clawfare.db

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

// ============================================================================
// Data Transfer Objects
// ============================================================================

/**
 * Investigation data class for transfer between layers.
 */
data class InvestigationDto(
    val slug: String,
    val origin: String,
    val destination: String,
    val departStart: String,
    val departEnd: String,
    val returnStart: String? = null,
    val returnEnd: String? = null,
    val cabinClass: String = "economy",
    val maxStops: Int = 1,
    // Config fields
    val maxPrice: Double? = null,
    val departAfter: String? = null,
    val departBefore: String? = null,
    // Trip constraints
    val minTripDays: Int? = null,
    val maxTripDays: Int? = null,
    val mustIncludeDate: String? = null,
    val maxLayoverMinutes: Int? = null,
    // Airline overrides (comma-separated IATA codes)
    val excludedAirlines: String? = null, // e.g. "LO,CX" — block these even if globally allowed
    val includedAirlines: String? = null, // e.g. "TK" — allow these even if not globally allowed
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String = Instant.now().toString(),
    /** Comma-separated origin airports. Null means [origin] only. */
    val origins: String? = null,
    /** Comma-separated destination airports. Null means [destination] only. */
    val destinations: String? = null,
    val checkedBags: Int = 0,
    val passengers: Int = 1,
    val currency: String = "GBP",
    val nights: Int? = null,
    val scenario: String? = null,
    val queryJson: String? = null,
    val presentationJson: String? = null,
)

/**
 * Flight data class for transfer between layers.
 */
data class FlightDto(
    val id: String,
    val investigationSlug: String,
    val shareLink: String? = null, // Deprecated: canonical link now in price_history.source_url
    val source: String,
    val tripType: String,
    val ticketStructure: String,
    // Deprecated price fields - canonical data now in price_history
    // Kept for backward compatibility with existing DB rows
    val priceAmount: Double = 0.0,
    val priceCurrency: String = "GBP",
    val priceMarket: String = "UK",
    val priceCheckedAt: String = "",
    val origin: String,
    val destination: String,
    val outboundJson: String,
    val returnJson: String? = null,
    val bookingClass: String? = null,
    val cabinMixed: Boolean = false,
    val stale: Boolean = false,
    val aircraftType: String? = null,
    val fareBrand: String? = null,
    val disqualified: String? = null,
    val notes: String? = null,
    val tags: String? = null,
    val capturedAt: String = Instant.now().toString(),
    val checkedBags: Int = 0,
    val passengers: Int = 1,
    val sourceLabel: String? = null,
    val livePrice: Boolean = false,
    val displayId: String? = null,
    val plannedReturnDate: String? = null,
    val linksJson: String? = null,
    val cabinClass: String? = null,
)

/**
 * Flight with its latest price - convenience view for CLI commands.
 */
data class FlightWithPrice(
    val flight: FlightDto,
    val priceAmount: Double,
    val priceCurrency: String,
    val priceMarket: String,
    val priceCheckedAt: String,
    val priceSource: String,
    val sourceUrl: String,
) {
    val id: String get() = flight.id
    val investigationSlug: String get() = flight.investigationSlug
    val shareLink: String get() = sourceUrl // Use sourceUrl from price_history
    val source: String get() = flight.source
    val tripType: String get() = flight.tripType
    val ticketStructure: String get() = flight.ticketStructure
    val origin: String get() = flight.origin
    val destination: String get() = flight.destination
    val outboundJson: String get() = flight.outboundJson
    val returnJson: String? get() = flight.returnJson
    val bookingClass: String? get() = flight.bookingClass
    val cabinMixed: Boolean get() = flight.cabinMixed
    val aircraftType: String? get() = flight.aircraftType
    val fareBrand: String? get() = flight.fareBrand
    val disqualified: String? get() = flight.disqualified
    val notes: String? get() = flight.notes
    val tags: String? get() = flight.tags
    val capturedAt: String get() = flight.capturedAt
}

/**
 * Price history entry data class.
 * sourceUrl is required for new entries - this is the canonical location for links.
 */
data class PriceHistoryDto(
    val id: Int? = null,
    val flightId: String,
    val amount: Double,
    val currency: String,
    val sourceUrl: String = "", // Required for new entries; empty = legacy migrated data
    val checkedAt: String = Instant.now().toString(),
    val source: String = "manual",
    val priceMarket: String = "UK",
)

// ============================================================================
// Investigation Queries
// ============================================================================

/**
 * Data access layer for investigations.
 */
object InvestigationQueries {
    /**
     * Create a new investigation.
     *
     * @return The created investigation
     * @throws IllegalStateException if slug already exists
     */
    fun create(dto: InvestigationDto): InvestigationDto =
        transaction {
            Investigations.insert {
                it[slug] = dto.slug
                it[origin] = dto.origin
                it[destination] = dto.destination
                it[departStart] = dto.departStart
                it[departEnd] = dto.departEnd
                it[returnStart] = dto.returnStart
                it[returnEnd] = dto.returnEnd
                it[cabinClass] = dto.cabinClass
                it[maxStops] = dto.maxStops
                it[maxPrice] = dto.maxPrice
                it[departAfter] = dto.departAfter
                it[departBefore] = dto.departBefore
                it[minTripDays] = dto.minTripDays
                it[maxTripDays] = dto.maxTripDays
                it[mustIncludeDate] = dto.mustIncludeDate
                it[maxLayoverMinutes] = dto.maxLayoverMinutes
                it[excludedAirlines] = dto.excludedAirlines
                it[includedAirlines] = dto.includedAirlines
                it[createdAt] = dto.createdAt
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
            dto
        }

    /**
     * Get investigation by slug.
     *
     * @return Investigation or null if not found
     */
    fun getBySlug(slug: String): InvestigationDto? =
        transaction {
            Investigations
                .selectAll()
                .where { Investigations.slug eq slug }
                .map { it.toInvestigationDto() }
                .singleOrNull()
        }

    /**
     * List all investigations.
     */
    fun listAll(): List<InvestigationDto> =
        transaction {
            Investigations
                .selectAll()
                .map { it.toInvestigationDto() }
        }

    /**
     * Update an existing investigation.
     *
     * @return Updated investigation or null if not found
     */
    fun update(dto: InvestigationDto): InvestigationDto? =
        transaction {
            val updated =
                Investigations.update({ Investigations.slug eq dto.slug }) {
                    it[origin] = dto.origin
                    it[destination] = dto.destination
                    it[departStart] = dto.departStart
                    it[departEnd] = dto.departEnd
                    it[returnStart] = dto.returnStart
                    it[returnEnd] = dto.returnEnd
                    it[cabinClass] = dto.cabinClass
                    it[maxStops] = dto.maxStops
                    it[maxPrice] = dto.maxPrice
                    it[departAfter] = dto.departAfter
                    it[departBefore] = dto.departBefore
                    it[minTripDays] = dto.minTripDays
                    it[maxTripDays] = dto.maxTripDays
                    it[mustIncludeDate] = dto.mustIncludeDate
                    it[maxLayoverMinutes] = dto.maxLayoverMinutes
                    it[updatedAt] = Instant.now().toString()
                }
            if (updated > 0) getBySlug(dto.slug) else null
        }

    /**
     * Update investigation config fields only.
     */
    fun updateConfig(
        slug: String,
        maxPrice: Double? = null,
        departAfter: String? = null,
        departBefore: String? = null,
        minTripDays: Int? = null,
        maxTripDays: Int? = null,
        mustIncludeDate: String? = null,
        maxLayoverMinutes: Int? = null,
        excludedAirlines: String? = null,
        includedAirlines: String? = null,
    ): Boolean =
        transaction {
            val updates =
                Investigations.update({ Investigations.slug eq slug }) {
                    maxPrice?.let { value -> it[Investigations.maxPrice] = value }
                    departAfter?.let { value -> it[Investigations.departAfter] = value }
                    departBefore?.let { value -> it[Investigations.departBefore] = value }
                    minTripDays?.let { value -> it[Investigations.minTripDays] = value }
                    maxTripDays?.let { value -> it[Investigations.maxTripDays] = value }
                    mustIncludeDate?.let { value -> it[Investigations.mustIncludeDate] = value }
                    maxLayoverMinutes?.let { value -> it[Investigations.maxLayoverMinutes] = value }
                    // Airline lists: always write (even null, to allow clearing)
                    it[Investigations.excludedAirlines] = excludedAirlines
                    it[Investigations.includedAirlines] = includedAirlines
                    it[updatedAt] = Instant.now().toString()
                }
            updates > 0
        }

    /**
     * Delete investigation by slug.
     * Note: This will fail if flights reference this investigation.
     *
     * @return true if deleted, false if not found
     */
    fun delete(slug: String): Boolean =
        transaction {
            Investigations.deleteWhere { Investigations.slug eq slug } > 0
        }

    private fun ResultRow.toInvestigationDto(): InvestigationDto =
        InvestigationDto(
            slug = this[Investigations.slug],
            origin = this[Investigations.origin],
            destination = this[Investigations.destination],
            departStart = this[Investigations.departStart],
            departEnd = this[Investigations.departEnd],
            returnStart = this[Investigations.returnStart],
            returnEnd = this[Investigations.returnEnd],
            cabinClass = this[Investigations.cabinClass],
            maxStops = this[Investigations.maxStops],
            maxPrice = this[Investigations.maxPrice],
            departAfter = this[Investigations.departAfter],
            departBefore = this[Investigations.departBefore],
            minTripDays = this[Investigations.minTripDays],
            maxTripDays = this[Investigations.maxTripDays],
            mustIncludeDate = this[Investigations.mustIncludeDate],
            maxLayoverMinutes = this[Investigations.maxLayoverMinutes],
            excludedAirlines = this[Investigations.excludedAirlines],
            includedAirlines = this[Investigations.includedAirlines],
            createdAt = this[Investigations.createdAt],
            updatedAt = this[Investigations.updatedAt],
            origins = this[Investigations.origins],
            destinations = this[Investigations.destinations],
            checkedBags = this[Investigations.checkedBags] ?: 0,
            passengers = this[Investigations.passengers] ?: 1,
            currency = this[Investigations.currency] ?: "GBP",
            nights = this[Investigations.nights],
            scenario = this[Investigations.scenario],
            queryJson = this[Investigations.queryJson],
            presentationJson = this[Investigations.presentationJson],
        )
}

// ============================================================================
// Flight Queries
// ============================================================================

/**
 * Data access layer for flights.
 */
object FlightQueries {
    /**
     * Create a new flight.
     *
     * @return The created flight
     */
    fun create(dto: FlightDto): FlightDto =
        transaction {
            Flights.insert {
                it[id] = dto.id
                it[investigationSlug] = dto.investigationSlug
                it[shareLink] = dto.shareLink
                it[flightSource] = dto.source
                it[tripType] = dto.tripType
                it[ticketStructure] = dto.ticketStructure
                // Deprecated fields - write for backward compat
                it[priceAmount] = dto.priceAmount
                it[priceCurrency] = dto.priceCurrency
                it[priceMarket] = dto.priceMarket
                it[priceCheckedAt] = dto.priceCheckedAt
                it[origin] = dto.origin
                it[destination] = dto.destination
                it[outboundJson] = dto.outboundJson
                it[returnJson] = dto.returnJson
                it[bookingClass] = dto.bookingClass
                it[cabinMixed] = if (dto.cabinMixed) 1 else 0
                it[stale] = if (dto.stale) 1 else 0
                it[aircraftType] = dto.aircraftType
                it[fareBrand] = dto.fareBrand
                it[disqualified] = dto.disqualified
                it[notes] = dto.notes
                it[tags] = dto.tags
                it[capturedAt] = dto.capturedAt
                it[checkedBags] = dto.checkedBags
                it[passengers] = dto.passengers
                it[sourceLabel] = dto.sourceLabel
                it[livePrice] = if (dto.livePrice) 1 else 0
                it[displayId] = dto.displayId
                it[plannedReturnDate] = dto.plannedReturnDate
                it[linksJson] = dto.linksJson
                it[cabinClass] = dto.cabinClass
            }
            dto
        }

    /**
     * Get flight by ID.
     *
     * @return Flight or null if not found
     */
    fun getById(id: String): FlightDto? =
        transaction {
            Flights
                .selectAll()
                .where { Flights.id eq id }
                .map { it.toFlightDto() }
                .singleOrNull()
        }

    /**
     * Get flight by ID prefix.
     * Returns the flight if exactly one matches the prefix.
     *
     * @return Flight or null if not found or ambiguous
     */
    fun getByIdPrefix(prefix: String): FlightDto? =
        transaction {
            val matches =
                Flights
                    .selectAll()
                    .where { Flights.id like "$prefix%" }
                    .map { it.toFlightDto() }

            if (matches.size == 1) matches.first() else null
        }

    /**
     * Get flight by share link.
     *
     * @return Flight or null if not found
     */
    fun getBySourceUrl(sourceUrl: String): FlightDto? =
        transaction {
            val priceEntry =
                PriceHistory
                    .selectAll()
                    .where { PriceHistory.sourceUrl eq sourceUrl }
                    .limit(1)
                    .firstOrNull()

            priceEntry?.let { getById(it[PriceHistory.flightId]) }
        }

    /**
     * Get flight by share link (DEPRECATED - use getBySourceUrl instead).
     * This looks up by the legacy share_link field on flights.
     *
     * @return Flight or null if not found
     */
    fun getByShareLink(shareLink: String): FlightDto? =
        transaction {
            // First try price_history (new canonical location)
            val priceEntry =
                PriceHistory
                    .selectAll()
                    .where { PriceHistory.sourceUrl eq shareLink }
                    .limit(1)
                    .firstOrNull()

            if (priceEntry != null) {
                return@transaction getById(priceEntry[PriceHistory.flightId])
            }

            // Fall back to legacy flights.share_link
            Flights
                .selectAll()
                .where { Flights.shareLink eq shareLink }
                .map { it.toFlightDto() }
                .singleOrNull()
        }

    /**
     * List all flights for an investigation.
     */
    fun listByInvestigation(investigationSlug: String): List<FlightDto> =
        transaction {
            Flights
                .selectAll()
                .where { Flights.investigationSlug eq investigationSlug }
                .map { it.toFlightDto() }
        }

    /**
     * List all flights.
     */
    fun listAll(): List<FlightDto> =
        transaction {
            Flights
                .selectAll()
                .map { it.toFlightDto() }
        }

    /**
     * Update an existing flight (metadata only, not prices).
     *
     * @return Updated flight or null if not found
     */
    fun update(dto: FlightDto): FlightDto? =
        transaction {
            val updated =
                Flights.update({ Flights.id eq dto.id }) {
                    it[investigationSlug] = dto.investigationSlug
                    it[shareLink] = dto.shareLink
                    it[flightSource] = dto.source
                    it[tripType] = dto.tripType
                    it[ticketStructure] = dto.ticketStructure
                    // Deprecated price fields - write for backward compat
                    it[priceAmount] = dto.priceAmount
                    it[priceCurrency] = dto.priceCurrency
                    it[priceMarket] = dto.priceMarket
                    it[priceCheckedAt] = dto.priceCheckedAt
                    it[origin] = dto.origin
                    it[destination] = dto.destination
                    it[outboundJson] = dto.outboundJson
                    it[returnJson] = dto.returnJson
                    it[bookingClass] = dto.bookingClass
                    it[cabinMixed] = if (dto.cabinMixed) 1 else 0
                    it[stale] = if (dto.stale) 1 else 0
                    it[aircraftType] = dto.aircraftType
                    it[fareBrand] = dto.fareBrand
                    it[disqualified] = dto.disqualified
                    it[notes] = dto.notes
                    it[tags] = dto.tags
                    it[checkedBags] = dto.checkedBags
                    it[passengers] = dto.passengers
                    it[sourceLabel] = dto.sourceLabel
                    it[livePrice] = if (dto.livePrice) 1 else 0
                    it[displayId] = dto.displayId
                    it[plannedReturnDate] = dto.plannedReturnDate
                    it[linksJson] = dto.linksJson
                    it[cabinClass] = dto.cabinClass
                }
            if (updated > 0) getById(dto.id) else null
        }

    /**
     * Delete flight by ID.
     * Note: This will also delete associated price history.
     *
     * @return true if deleted, false if not found
     */
    fun delete(id: String): Boolean =
        transaction {
            PriceHistory.deleteWhere { flightId eq id }
            SellerOffers.deleteWhere { flightId eq id }
            FlightProviderTokens.deleteWhere { flightId eq id }
            Flights.deleteWhere { Flights.id eq id } > 0
        }

    /**
     * Delete all flights for an investigation.
     *
     * @return Number of flights deleted
     */
    fun deleteByInvestigation(investigationSlug: String): Int =
        transaction {
            // Get all flight IDs first
            val flightIds =
                Flights
                    .selectAll()
                    .where { Flights.investigationSlug eq investigationSlug }
                    .map { it[Flights.id] }

            // Delete price history for each flight
            flightIds.forEach { flightId ->
                PriceHistory.deleteWhere { PriceHistory.flightId eq flightId }
                SellerOffers.deleteWhere { SellerOffers.flightId eq flightId }
                FlightProviderTokens.deleteWhere { FlightProviderTokens.flightId eq flightId }
            }

            // Delete flights
            Flights.deleteWhere { Flights.investigationSlug eq investigationSlug }
        }

    private fun ResultRow.toFlightDto(): FlightDto =
        FlightDto(
            id = this[Flights.id],
            investigationSlug = this[Flights.investigationSlug],
            shareLink = this[Flights.shareLink],
            source = this[Flights.flightSource],
            tripType = this[Flights.tripType],
            ticketStructure = this[Flights.ticketStructure],
            // Deprecated price fields - read from DB for backward compat
            priceAmount = this[Flights.priceAmount] ?: 0.0,
            priceCurrency = this[Flights.priceCurrency] ?: "GBP",
            priceMarket = this[Flights.priceMarket] ?: "UK",
            priceCheckedAt = this[Flights.priceCheckedAt] ?: "",
            origin = this[Flights.origin],
            destination = this[Flights.destination],
            outboundJson = this[Flights.outboundJson],
            returnJson = this[Flights.returnJson],
            bookingClass = this[Flights.bookingClass],
            cabinMixed = this[Flights.cabinMixed] == 1,
            stale = this[Flights.stale] == 1,
            aircraftType = this[Flights.aircraftType],
            fareBrand = this[Flights.fareBrand],
            disqualified = this[Flights.disqualified],
            notes = this[Flights.notes],
            tags = this[Flights.tags],
            capturedAt = this[Flights.capturedAt],
            checkedBags = this[Flights.checkedBags] ?: 0,
            passengers = this[Flights.passengers] ?: 1,
            sourceLabel = this[Flights.sourceLabel],
            livePrice = this[Flights.livePrice] == 1,
            displayId = this[Flights.displayId],
            plannedReturnDate = this[Flights.plannedReturnDate],
            linksJson = this[Flights.linksJson],
            cabinClass = this[Flights.cabinClass],
        )

    /**
     * Get a flight with its latest price.
     */
    fun getWithPrice(id: String): FlightWithPrice? {
        val flight = getById(id) ?: return null
        val latestPrice = PriceHistoryQueries.getLatest(id) ?: return null
        return FlightWithPrice(
            flight = flight,
            priceAmount = latestPrice.amount,
            priceCurrency = latestPrice.currency,
            priceMarket = latestPrice.priceMarket,
            priceCheckedAt = latestPrice.checkedAt,
            priceSource = latestPrice.source,
            sourceUrl = latestPrice.sourceUrl,
        )
    }

    /**
     * Get a flight with its latest price by ID prefix.
     */
    fun getWithPriceByPrefix(prefix: String): FlightWithPrice? {
        val flight = getByIdPrefix(prefix) ?: return null
        val latestPrice = PriceHistoryQueries.getLatest(flight.id) ?: return null
        return FlightWithPrice(
            flight = flight,
            priceAmount = latestPrice.amount,
            priceCurrency = latestPrice.currency,
            priceMarket = latestPrice.priceMarket,
            priceCheckedAt = latestPrice.checkedAt,
            priceSource = latestPrice.source,
            sourceUrl = latestPrice.sourceUrl,
        )
    }

    /**
     * List all flights for an investigation with their latest prices.
     */
    fun listWithPrices(investigationSlug: String): List<FlightWithPrice> {
        val flights = listByInvestigation(investigationSlug)
        return flights.mapNotNull { flight ->
            val latestPrice = PriceHistoryQueries.getLatest(flight.id)
            if (latestPrice != null) {
                FlightWithPrice(
                    flight = flight,
                    priceAmount = latestPrice.amount,
                    priceCurrency = latestPrice.currency,
                    priceMarket = latestPrice.priceMarket,
                    priceCheckedAt = latestPrice.checkedAt,
                    priceSource = latestPrice.source,
                    sourceUrl = latestPrice.sourceUrl,
                )
            } else {
                null
            }
        }
    }
}

// ============================================================================
// Price History Queries
// ============================================================================

/**
 * Data access layer for price history.
 */
object PriceHistoryQueries {
    /**
     * Record a price check.
     *
     * @return The created price history entry
     */
    fun create(dto: PriceHistoryDto): PriceHistoryDto =
        transaction {
            val insertedId =
                PriceHistory.insert {
                    it[flightId] = dto.flightId
                    it[amount] = dto.amount
                    it[currency] = dto.currency
                    it[sourceUrl] = dto.sourceUrl
                    it[checkedAt] = dto.checkedAt
                    it[priceSource] = dto.source
                    it[priceMarket] = dto.priceMarket
                } get PriceHistory.id

            dto.copy(id = insertedId)
        }

    /**
     * Get all price history for a flight, ordered by check time.
     */
    fun getByFlightId(flightId: String): List<PriceHistoryDto> =
        transaction {
            PriceHistory
                .selectAll()
                .where { PriceHistory.flightId eq flightId }
                .orderBy(PriceHistory.checkedAt)
                .map { it.toPriceHistoryDto() }
        }

    /**
     * Get the latest price entry for a flight.
     */
    fun getLatest(flightId: String): PriceHistoryDto? =
        transaction {
            PriceHistory
                .selectAll()
                .where { PriceHistory.flightId eq flightId }
                .orderBy(PriceHistory.checkedAt, org.jetbrains.exposed.sql.SortOrder.DESC)
                .limit(1)
                .map { it.toPriceHistoryDto() }
                .singleOrNull()
        }

    /**
     * Get all price history for an investigation (via flight join).
     */
    fun getByInvestigation(investigationSlug: String): List<PriceHistoryDto> =
        transaction {
            (PriceHistory innerJoin Flights)
                .selectAll()
                .where { Flights.investigationSlug eq investigationSlug }
                .orderBy(PriceHistory.checkedAt)
                .map { it.toPriceHistoryDto() }
        }

    /**
     * Delete all price history for a flight.
     *
     * @return Number of entries deleted
     */
    fun deleteByFlightId(flightId: String): Int =
        transaction {
            PriceHistory.deleteWhere { PriceHistory.flightId eq flightId }
        }

    /**
     * Get a single price history entry by its ID.
     */
    fun getById(id: Int): PriceHistoryDto? =
        transaction {
            PriceHistory
                .selectAll()
                .where { PriceHistory.id eq id }
                .map { it.toPriceHistoryDto() }
                .singleOrNull()
        }

    /**
     * Delete a specific price history entry by its ID.
     *
     * @return true if an entry was deleted
     */
    fun deleteById(id: Int): Boolean =
        transaction {
            PriceHistory.deleteWhere { PriceHistory.id eq id } > 0
        }

    private fun ResultRow.toPriceHistoryDto(): PriceHistoryDto =
        PriceHistoryDto(
            id = this[PriceHistory.id],
            flightId = this[PriceHistory.flightId],
            amount = this[PriceHistory.amount],
            currency = this[PriceHistory.currency],
            sourceUrl = this[PriceHistory.sourceUrl],
            checkedAt = this[PriceHistory.checkedAt],
            source = this[PriceHistory.priceSource],
            priceMarket = this[PriceHistory.priceMarket],
        )
}
