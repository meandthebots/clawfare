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
