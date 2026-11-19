@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package com.clawfare.search

import kotlin.js.JsExport

/**
 * Deliberately small JavaScript/TypeScript boundary for a future hosted
 * request handler. JSON strings avoid exporting platform-specific objects.
 *
 * This module does not implement HTTP, access provider keys, or make requests.
 */
@JsExport
object ClawfareJs {
    fun normalizeSearchJson(queryJson: String): String {
        val query = AppJson.decodeFromString(SearchQuery.serializer(), queryJson)
        return AppJson.encodeToString(SearchQuery.serializer(), normalizeQuery(query))
    }

    /**
     * Returns normalized search settings, validated local date pairs and an
     * optional error. No provider request or cost reservation occurs here.
     */
    fun planSearchJson(queryJson: String): String {
        val query = AppJson.decodeFromString(SearchQuery.serializer(), queryJson)
        return AppJson.encodeToString(FlightSearchPlan.serializer(), planFlightSearch(query))
    }

    /**
     * Derive a purely informational provider-call upper bound. The HTTP
     * adapter must still enforce authenticated consent and durable reservation.
     */
    fun projectBudgetJson(
        queryJson: String,
        providersJson: String,
    ): String {
        val query = normalizeQuery(AppJson.decodeFromString(SearchQuery.serializer(), queryJson))
        val providers =
            AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(PlannedProvider.serializer()),
                providersJson,
            )
        return AppJson.encodeToString(CallBudget.serializer(), projectCallBudget(query, providers))
    }

    /**
     * Parse a provider response on the hosted SERVER using the exact same
     * offer/segment parser as JVM. Contains opaque tokens: never return this
     * JSON to visitors or persist it as a public investigation snapshot.
     */
    fun parseGoogleFlightsOffersJson(responseJson: String): String =
        AppJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ParsedOffer.serializer()),
            parseGoogleFlightsOffers(responseJson),
        )

    /**
     * Portable token-free fare normalization for the hosted SERVER.
     * Opaque ParsedOffer booking/departure tokens are intentionally omitted.
     */
    fun buildGoogleFareDraftJson(requestJson: String): String {
        val request = AppJson.decodeFromString(GoogleFareDraftRequest.serializer(), requestJson)
        val draft =
            buildGoogleFareDraft(
                id = request.id,
                provider = request.provider,
                query = request.query,
                outbound = request.outbound,
                inbound = request.inbound,
                fetchedAt = request.fetchedAt,
                servedFromCache = request.servedFromCache,
                returnDateHint = request.returnDateHint,
            )
        return AppJson.encodeToString(GoogleFareDraft.serializer(), draft)
    }

    /**
     * Pure audited eligibility results. The caller supplies a trusted
     * airport-country reference; unknown transit countries fail closed.
     * This bridge contains no network, seller/refund/booking or token access.
     */
    fun evaluateOfferEligibilityJson(requestJson: String): String {
        val input = AppJson.decodeFromString(EligibilityEvaluationRequest.serializer(), requestJson)
        return AppJson.encodeToString(
            EligibilityEvaluation.serializer(),
            evaluatePortableEligibility(input),
        )
    }

    /** Canonical UI ResultSet assembly; returns token-free fare and coverage evidence. */
    fun assembleHostedOneWayJson(requestJson: String): String {
        val request = AppJson.decodeFromString(HostedFareResultRequest.serializer(), requestJson)
        return AppJson.encodeToString(
            HostedFareAssembly.serializer(), assembleHostedOneWayResult(request),
        )
    }

    fun toGbpOrNull(
        amount: Double,
        unitsPerGbp: Double,
    ): Double? = FareMath.toGbpOrNull(amount, unitsPerGbp)
}

/**
 * Explicit top-level entrypoints for bundlers. Kotlin/JS executable
 * dead-code elimination can discard unused object methods when no main()
 * references the singleton; top-level @JsExport functions are stable roots.
 * They delegate to the same Kotlin implementation and accept only JSON or
 * primitive values, never provider credentials.
 */
@JsExport
fun normalizeSearchForHost(queryJson: String): String = ClawfareJs.normalizeSearchJson(queryJson)

@JsExport
fun planSearchForHost(queryJson: String): String = ClawfareJs.planSearchJson(queryJson)

@JsExport
fun projectBudgetForHost(
    queryJson: String,
    providersJson: String,
): String = ClawfareJs.projectBudgetJson(queryJson, providersJson)

@JsExport
fun toGbpForHost(
    amount: Double,
    unitsPerGbp: Double,
): Double? = ClawfareJs.toGbpOrNull(amount, unitsPerGbp)

/** Server-only provider parsing. Opaque departure/booking tokens are private. */
@JsExport
fun parseGoogleFlightsOffersForHost(responseJson: String): String =
    ClawfareJs.parseGoogleFlightsOffersJson(responseJson)

/** Token-free Kotlin draft for a provider response; not a public ResultSet. */
@JsExport
fun buildGoogleFareDraftForHost(requestJson: String): String =
    ClawfareJs.buildGoogleFareDraftJson(requestJson)

/** Exact same carrier, transit, operator and cabin rules as the JVM. */
@JsExport
fun evaluateOfferEligibilityForHost(requestJson: String): String =
    ClawfareJs.evaluateOfferEligibilityJson(requestJson)

/** Server-only, one-way Kotlin ResultSet construction; no opaque provider tokens. */
@JsExport
fun assembleHostedOneWayForHost(requestJson: String): String =
    ClawfareJs.assembleHostedOneWayJson(requestJson)
