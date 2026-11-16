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
