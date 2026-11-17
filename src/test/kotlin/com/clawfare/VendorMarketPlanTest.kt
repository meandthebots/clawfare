package com.clawfare

import com.clawfare.search.VendorMarketPlan
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VendorMarketPlanTest {
    @Test
    fun emptyAndBlankMarketsStillReserveDefaultUkCalls() {
        for (raw in listOf(emptyList(), listOf("  ", ""))) {
            val plan = VendorMarketPlan.parse(raw)
            assertEquals(listOf("uk"), plan.markets)
            assertEquals(7, plan.networkCallsUpperBound)
            assertFalse(plan.withinWebCap(0))
            assertFalse(plan.withinWebCap(6))
            assertTrue(plan.withinWebCap(7))
        }
    }

    @Test
    fun normalizationAndEstimateUseSameActualMarketCardinality() {
        val plan = VendorMarketPlan.parse(listOf(" UK ", "uk", "Fr", "FR", ""))
        assertEquals(listOf("uk", "fr"), plan.markets)
        assertEquals(14, plan.networkCallsUpperBound)
        assertFalse(plan.withinWebCap(13))
        assertTrue(plan.withinWebCap(14))
        assertTrue(plan.withinWebCap(16))
    }

    @Test
    fun atMostTwoEffectiveMarketsEvenIfQuotaIsLarger() {
        val duplicate = VendorMarketPlan.parse(listOf("uk", "UK", "uk"))
        assertEquals(7, duplicate.networkCallsUpperBound)
        assertTrue(duplicate.withinWebCap(7))

        val three = VendorMarketPlan.parse(listOf("uk", "fr", "de"))
        assertEquals(21, three.networkCallsUpperBound)
        assertFalse(three.withinWebCap(100))
        assertFalse(three.withinWebCap(Int.MAX_VALUE))
    }
}
