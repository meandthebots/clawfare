package com.clawfare.search

import kotlin.test.Test
import kotlin.test.assertEquals

class ClawfareJsTest {
    @Test
    fun exportedInteropUsesTheSameKotlinNormalizer() {
        val raw =
            AppJson.encodeToString(
                SearchQuery.serializer(),
                SearchQuery(origins = listOf("lhr, lgw"), destinations = listOf("tpa"), tripType = "one_way"),
            )
        val normalized = AppJson.decodeFromString(SearchQuery.serializer(), ClawfareJs.normalizeSearchJson(raw))
        assertEquals(listOf("LHR", "LGW"), normalized.origins)
        assertEquals(listOf("TPA"), normalized.destinations)
        assertEquals("one_way", normalized.tripType)
        assertEquals(200.0, ClawfareJs.toGbpOrNull(250.0, 1.25))
        val providersJson =
            AppJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(PlannedProvider.serializer()),
                listOf(
                    PlannedProvider(
                        id = "serpapi",
                        configured = true,
                        calls = listOf(PlannedCall("LHR to TPA", cached = false)),
                    ),
                ),
            )
        val budget =
            AppJson.decodeFromString(
                CallBudget.serializer(),
                ClawfareJs.projectBudgetJson(raw, providersJson),
            )
        assertEquals(1, budget.liveCalls)
        assertEquals(0, budget.returnLookupsUpperBound)

    }
}
