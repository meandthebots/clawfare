package com.clawfare

import com.clawfare.search.BatchConfig
import com.clawfare.search.BatchRow
import com.clawfare.search.BatchScenario
import com.clawfare.search.BatchSweep
import com.clawfare.search.Direction
import com.clawfare.search.FareSink
import com.clawfare.search.Itinerary
import com.clawfare.search.Leg
import com.clawfare.search.ResultSet
import com.clawfare.search.SearchQuery
import com.clawfare.search.StoredResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatchCostScopeTest {
    private val query =
        SearchQuery(
            origins = listOf("LHR"),
            destinations = listOf("HND"),
            outboundFrom = "2028-06-01",
            outboundTo = "2028-06-01",
            tripType = "one_way",
            adults = 2,
            cabin = "business",
            providers = emptyList(),
        )

    private val fare =
        Itinerary(
            id = "synthetic",
            pricePerPerson = 1500.0,
            passengers = 2,
            source = "serpapi",
            sourceLabel = "live",
            livePrice = true,
            outboundDate = "2028-06-01",
            outbound =
                Direction(
                    legs =
                        listOf(
                            Leg(
                                "XX 123",
                                "Synthetic Airline",
                                "XX",
                                "Synthetic Airline",
                                "LHR",
                                "HND",
                                "11:00",
                                "08:00",
                                "2028-06-01",
                                "2028-06-02",
                                cabin = "Business",
                            ),
                        ),
                ),
        )

    private fun row(
        scenario: BatchScenario,
        ticket: Itinerary = fare,
    ): BatchRow {
        val config = BatchConfig("costs", listOf(scenario), maxLiveCalls = 0)
        val key = BatchSweep.expand(config).single().signature
        val sink =
            object : FareSink {
                override fun save(stored: StoredResult): ResultSet = stored.result

                override fun load(id: String): StoredResult? =
                    if (id == key) {
                        StoredResult(ResultSet(id, "synthetic", itineraries = listOf(ticket)))
                    } else {
                        null
                    }

                override fun list(): List<ResultSet> = emptyList()
            }
        return BatchSweep.compare(config, sink).single()
    }

    @Test
    fun defaultScopeComparesAirfaresWithoutInventingHotelAndTransferCharges() {
        val observation = row(BatchScenario("direct", query))
        assertEquals("airfare_only", observation.costScope)
        assertEquals(3000.0, observation.allInGbp)
        assertTrue(observation.expenseEvidence.contains("excluded components are not assumed free"))
        assertEquals(3000.0, observation.partyFareGbp)

        val ignored = row(BatchScenario("direct", query, hotelsGbp = 900.0, costScope = "airfare_only"))
        assertEquals(3000.0, ignored.allInGbp)
        assertEquals("airfare_only", ignored.costScope)
    }

    @Test
    fun separatePositioningAndBaggageMustBePricedIfTheScopeRequiresThem() {
        val missing = row(BatchScenario("hub", query, costScope = "airfare_positioning"))
        assertNull(missing.allInGbp)
        assertTrue(missing.expenseEvidence.contains("Incomplete"))
        val positioned = row(BatchScenario("hub", query, positioningGbp = 300.0, costScope = "airfare_positioning"))
        assertEquals(3300.0, positioned.allInGbp)
        assertEquals("airfare_positioning", positioned.costScope)
        val bags = row(BatchScenario("direct", query, baggageGbp = 140.0))
        assertEquals(3140.0, bags.allInGbp)
        assertEquals("airfare_baggage", bags.costScope)
        val both =
            row(
                BatchScenario(
                    "hub",
                    query,
                    positioningGbp = 300.0,
                    baggageGbp = 140.0,
                    costScope = "airfare_positioning_baggage",
                ),
            )
        assertEquals(3440.0, both.allInGbp)
        assertNull(row(BatchScenario("hub", query, positioningGbp = 300.0, costScope = "airfare_positioning_baggage")).allInGbp)
    }

    @Test
    fun oldConfigsWithHotelsAndTransfersStillHaveOriginalMeaning() {
        val scenario =
            BatchScenario(
                "legacy",
                query,
                positioningGbp = 300.0,
                hotelsGbp = 400.0,
                groundTransfersGbp = 75.0,
                baggageGbp = 140.0,
            )
        val legacy = row(scenario)
        assertEquals("legacy_full_trip", legacy.costScope)
        assertEquals(3915.0, legacy.allInGbp)
        assertNull(row(scenario.copy(hotelsGbp = null)).allInGbp)
        assertNull(row(scenario, ticket = fare.copy(sourceLabel = "cached")).allInGbp)
    }

    @Test
    fun unknownScopeCannotStartPaidSearchAndBadAmountsNeverRank() {
        assertFailsWith<IllegalArgumentException> {
            BatchSweep.expand(BatchConfig("invalid", listOf(BatchScenario("direct", query, costScope = "guess"))))
        }
        assertNull(row(BatchScenario("hub", query, positioningGbp = -100.0)).allInGbp)
        assertNull(row(BatchScenario("hub", query, positioningGbp = Double.NaN)).allInGbp)
    }
}
