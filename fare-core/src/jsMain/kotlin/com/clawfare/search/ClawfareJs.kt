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

    fun toGbpOrNull(
        amount: Double,
        unitsPerGbp: Double,
    ): Double? = FareMath.toGbpOrNull(amount, unitsPerGbp)
}
