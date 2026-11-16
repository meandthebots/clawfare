package com.clawfare.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FareCoreTest {
    @Test
    fun querySerializesAcrossTargets() {
        val query =
            SearchQuery(
                origins = listOf("LHR"),
                destinations = listOf("TPA"),
                outboundFrom = "2026-12-18",
                nights = 8,
                providers = listOf("serpapi"),
                cacheOnly = true,
            )
        val json = AppJson.encodeToString(SearchQuery.serializer(), query)
        assertTrue(json.contains("LHR"))
        assertEquals(query, AppJson.decodeFromString(SearchQuery.serializer(), json))
    }

    @Test
    fun filterAndCabinRulesAreShared() {
        assertTrue(isTransatlantic("LHR", "TPA"))
        assertFalse(isTransatlantic("LHR", "AMS"))
        assertTrue(isEuropeanOperator("BA"))
        assertTrue(isEuropeanOperator("British Airways"))
        assertEquals("premium_economy", recognizedCabinKeyOf("Premium Economy"))
        assertNull(recognizedCabinKeyOf("unknown cabin"))
        assertTrue(cabinAtLeast("business", "premium_economy"))
        assertFalse(cabinAtLeast("economy", "premium_economy"))
    }

    @Test
    fun fxConversionNeverAssumesMissingRatesArePounds() {
        assertEquals(200.0, FareMath.toGbpOrNull(250.0, 1.25))
        assertNull(FareMath.toGbpOrNull(250.0, null))
        assertNull(FareMath.toGbpOrNull(250.0, 0.0))
        assertNull(FareMath.toGbpOrNull(Double.POSITIVE_INFINITY, 1.25))
        assertNull(FareMath.toGbpOrNull(-1.0, 1.25))
        assertEquals(440.0, FareMath.partyTotal(150.0, 2, 140.0, 1))
    }

    @Test
    fun redactsSecretsInSharedCode() {
        val original = "token=secretABC Authorization: Bearer token12345"
        val safe = redact(original, listOf("secretABC"))
        assertFalse("secretABC" in safe)
        assertFalse("token12345" in safe)
    }
}
