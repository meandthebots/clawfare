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
    }
}
