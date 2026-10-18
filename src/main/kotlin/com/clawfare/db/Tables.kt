package com.clawfare.db

import org.jetbrains.exposed.sql.Table

/**
 * Exposed table definition for investigations.
 * An investigation tracks a flight search with date ranges and parameters.
 */
object Investigations : Table("investigations") {
    val slug = text("slug")
    val origin = text("origin")
    val destination = text("destination")
    val departStart = text("depart_start")
    val departEnd = text("depart_end")
    val returnStart = text("return_start").nullable()
    val returnEnd = text("return_end").nullable()
    val cabinClass = text("cabin_class").default("economy")
    val maxStops = integer("max_stops").default(1)

    // Config fields
    val maxPrice = double("max_price").nullable()
    val departAfter = text("depart_after").nullable()
    val departBefore = text("depart_before").nullable()

    // Trip constraints
    val minTripDays = integer("min_trip_days").nullable()
    val maxTripDays = integer("max_trip_days").nullable()
    val mustIncludeDate = text("must_include_date").nullable()
    val maxLayoverMinutes = integer("max_layover_minutes").nullable()

    // Airline overrides
    val excludedAirlines = text("excluded_airlines").nullable()
    val includedAirlines = text("included_airlines").nullable()
    val createdAt = text("created_at")
    val updatedAt = text("updated_at")

    // Multi-airport routes. origin/destination stay the primary codes.
    val origins = text("origins").nullable()
    val destinations = text("destinations").nullable()
    val checkedBags = integer("checked_bags").nullable()
    val passengers = integer("passengers").nullable()
    val currency = text("currency").nullable()
    val nights = integer("nights").nullable()
    val scenario = text("scenario").nullable()
    val queryJson = text("query_json").nullable()
    val presentationJson = text("presentation_json").nullable()

    override val primaryKey = PrimaryKey(slug)
}

/**
 * Exposed table definition for flights.
 * A flight is a specific itinerary captured during an investigation.
 * Prices are stored in price_history, not on the flight record.
 */
object Flights : Table("flights") {
    val id = text("id")
    val investigationSlug = text("investigation_slug").references(Investigations.slug)
    val shareLink = text("share_link").nullable() // Deprecated: canonical link in price_history.source_url
    val flightSource = text("source")
    val tripType = text("trip_type")
    val ticketStructure = text("ticket_structure")

    // Deprecated price fields - kept for backward compat with existing DB rows
    // Canonical price data is now in price_history table
    val priceAmount = double("price_amount").nullable()
    val priceCurrency = text("price_currency").nullable()
    val priceMarket = text("price_market").nullable()
    val priceCheckedAt = text("price_checked_at").nullable()

    val origin = text("origin")
    val destination = text("destination")

    val outboundJson = text("outbound_json")
    val returnJson = text("return_json").nullable()

    val bookingClass = text("booking_class").nullable()
    val cabinMixed = integer("cabin_mixed").default(0)
    val stale = integer("stale").default(0)
    val aircraftType = text("aircraft_type").nullable()
    val fareBrand = text("fare_brand").nullable()
    val disqualified = text("disqualified").nullable()
    val notes = text("notes").nullable()
    val tags = text("tags").nullable()

    val capturedAt = text("captured_at")
    val checkedBags = integer("checked_bags").nullable()
    val passengers = integer("passengers").nullable()
    val sourceLabel = text("source_label").nullable()
    val livePrice = integer("live_price").nullable()
    val displayId = text("display_id").nullable()
    val plannedReturnDate = text("planned_return_date").nullable()
    val linksJson = text("links_json").nullable()
    val cabinClass = text("cabin_class").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Seller quotes for one itinerary: fare, bag fee, all-in price, and a risk note.
 * One row holds the round-trip quote beside the two one-ways.
 */
object SellerOffers : Table("seller_offers") {
    val id = integer("id").autoIncrement()
    val investigationSlug = text("investigation_slug").references(Investigations.slug)
    val flightId = text("flight_id").references(Flights.id)
    val ordinal = integer("ordinal").default(0)
    val vendor = text("vendor")
    val market = text("market")
    val marketLabel = text("market_label").nullable()
    val currency = text("currency")
    val fareBrand = text("fare_brand").nullable()
    val trust = text("trust").nullable()
    val trustLabel = text("trust_label").nullable()
    val notes = text("notes").nullable()
    val roundTrip = double("round_trip").nullable()
    val oneWayOut = double("one_way_out").nullable()
    val oneWayBack = double("one_way_back").nullable()
    val roundTripWithBag = double("round_trip_with_bag").nullable()
    val oneWaysWithBag = double("one_ways_with_bag").nullable()
    val allInGbp = double("all_in_gbp").nullable()
    val fareAmount = double("fare_amount").nullable()
    val bagFee = double("bag_fee").nullable()
    val how = text("how").nullable()
    val bags = text("bags").nullable()
    val mix = integer("mix").default(0)
    val fresh = integer("fresh").default(1)
    val priceChangeGbp = integer("price_change_gbp").nullable()
    val isNew = integer("is_new").default(0)
    val fetchedAt = text("fetched_at").nullable()
    val linksJson = text("links_json").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Raw provider responses. A cache for re-ingest, not the fare record.
 * Secrets are stripped before the body is stored.
 */
object ProviderCache : Table("provider_cache") {
    val provider = text("provider")
    val cacheKey = text("cache_key")
    val paramsJson = text("params_json")
    val body = text("body")
    val fetchedAt = text("fetched_at")

    override val primaryKey = PrimaryKey(provider, cacheKey)
}

/**
 * Tokens needed to repeat a Google Flights return or booking-options call.
 */
object FlightProviderTokens : Table("flight_provider_tokens") {
    val flightId = text("flight_id").references(Flights.id)
    val provider = text("provider")
    val departureToken = text("departure_token").nullable()
    val bookingToken = text("booking_token").nullable()
    val paramsJson = text("params_json").nullable()

    override val primaryKey = PrimaryKey(flightId)
}

/**
 * Exposed table definition for price history.
 * Tracks all price observations for a flight over time.
 * Each observation has a source URL (where we saw the price) and source (which system).
 * This is now the canonical location for prices and links.
 */
object PriceHistory : Table("price_history") {
    val id = integer("id").autoIncrement()
    val flightId = text("flight_id").references(Flights.id)
    val amount = double("amount")
    val currency = text("currency")
    val sourceUrl = text("source_url") // The link where this price was observed
    val checkedAt = text("checked_at")
    val priceSource = text("source").default("manual")
    val priceMarket = text("price_market").default("UK")

    override val primaryKey = PrimaryKey(id)
}
