package com.clawfare.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallBudgetTest {
    private val selected =
        listOf(
            PlannedProvider(
                "serpapi",
                configured = true,
                calls =
                    listOf(
                        PlannedCall("LHR → TPA", cached = true),
                        PlannedCall("LGW → TPA", cached = false),
                    ),
            ),
            PlannedProvider(
                "searchapi",
                configured = false,
                calls = listOf(PlannedCall("LHR → SRQ", cached = false)),
            ),
        )

    @Test
    fun countsCachedLiveAndUnconfiguredWithoutSpending() {
        val budget =
            projectCallBudget(
                SearchQuery(tripType = "round_trip", returnChoices = 3),
                selected,
            )
        assertEquals(1, budget.liveCalls)
        assertEquals(1, budget.cachedCalls)
        assertEquals(1, budget.skippedCalls)
        assertEquals(9, budget.returnLookupsUpperBound)
        assertEquals(10, budget.maximumNetworkCalls)
        assertTrue(budget.lines[0].contains("cached"))
        assertTrue(budget.lines[1].contains("will call"))
        assertTrue(budget.lines[2].contains("skipped, no API key"))
    }

    @Test
    fun cacheOnlyNeverRequiresProviderBudget() {
        val budget =
            projectCallBudget(
                SearchQuery(cacheOnly = true, tripType = "round_trip", returnChoices = 8),
                selected,
            )
        assertEquals(0, budget.liveCalls)
        assertEquals(0, budget.returnLookupsUpperBound)
        assertEquals(1, budget.cachedCalls)
        assertEquals(2, budget.skippedCalls)
        assertTrue(budget.lines[1].contains("not in cache"))
    }

    @Test
    fun oneWayAndDisabledReturnExpansionCannotBookReturnLookups() {
        for (query in listOf(SearchQuery(tripType = "one_way"), SearchQuery(fetchReturns = false))) {
            val budget = projectCallBudget(query, selected)
            assertEquals(0, budget.returnLookupsUpperBound)
        }
    }

    @Test
    fun providerKindsOtherThanGoogleDoNotExpandReturns() {
        val budget =
            projectCallBudget(
                SearchQuery(returnChoices = 8),
                listOf(PlannedProvider("duffel", true, listOf(PlannedCall("LHR to TPA", false)))),
            )
        assertEquals(1, budget.maximumNetworkCalls)
        assertEquals(0, budget.returnLookupsUpperBound)
    }
}
