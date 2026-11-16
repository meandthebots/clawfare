package com.clawfare

import com.clawfare.search.BatchConfig
import com.clawfare.search.BatchScenario
import com.clawfare.search.BatchSweep
import com.clawfare.search.CallSpec
import com.clawfare.search.Direction
import com.clawfare.search.DiskCache
import com.clawfare.search.FareProvider
import com.clawfare.search.Leg
import com.clawfare.search.NoopSink
import com.clawfare.search.ProviderOutcome
import com.clawfare.search.QuotaView
import com.clawfare.search.ResponseCache
import com.clawfare.search.SampleData
import com.clawfare.search.SearchQuery
import com.clawfare.search.SearchService
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BatchCoverageTest {
    private class FakeProvider : FareProvider {
        override val id = "serpapi"
        override val displayName = "Offline fixture"
        override val configured = true
        override val priceKind = "live"
        var calls = 0

        override fun quota(): QuotaView = QuotaView(id, true, remaining = 100)

        override fun baseCalls(query: SearchQuery): List<CallSpec> = listOf(CallSpec(id, "fixture", mapOf("date" to query.outboundFrom)))

        override fun search(
            query: SearchQuery,
            cache: ResponseCache,
            allowNetwork: Boolean,
        ): ProviderOutcome {
            calls++
            val first =
                Leg(
                    flightNumber = "AY 100",
                    airline = "Finnair",
                    airlineCode = "AY",
                    operatingCarrier = "Finnair",
                    from = "LHR",
                    to = "HKG",
                    departTime = "09:00",
                    arriveTime = "12:00",
                    departDate = query.outboundFrom,
                    arriveDate = query.outboundFrom,
                    cabin = "Business",
                )
            val next = first.copy(from = "HKG", to = "HND")
            val offer =
                SampleData.florida().itineraries.first().copy(
                    id = "synthetic-blocked",
                    outbound = Direction(legs = listOf(first, next)),
                    returning = null,
                    returnMissing = false,
                    source = id,
                    sourceLabel = "live",
                )
            return ProviderOutcome(itineraries = listOf(offer), liveCalls = 1)
        }
    }

    @Test
    fun neverSearchedVersusAllFilteredAndResumeWithoutAnotherCall() {
        val root =
            File.createTempFile("coverage", "").apply {
                delete()
                mkdirs()
            }
        val journal = root.resolve("attempts.ndjson")
        val query =
            SearchQuery(
                origins = listOf("LHR"),
                destinations = listOf("HND"),
                outboundFrom = "2028-06-10",
                tripType = "one_way",
                cabin = "business",
                adults = 2,
                providers = listOf("serpapi"),
                excludedTransitCountries = listOf("CN", "HK", "MO"),
            )
        val config = BatchConfig("test-coverage", listOf(BatchScenario("only", query)), maxLiveCalls = 1)
        val provider = FakeProvider()
        val service = SearchService(listOf(provider), DiskCache(root), NoopSink)

        val before = BatchSweep.coverage(config, journal)
        assertEquals(1, before.size)
        assertEquals("not_searched", before.single().status)

        val run = BatchSweep.run(config, service, confirmed = true, auditFile = journal)
        assertEquals(1, run.executed)
        assertEquals(1, provider.calls)
        assertEquals(1, journal.readLines().size)

        val after = BatchSweep.coverage(config, journal).single()
        assertEquals("filtered_all", after.status)
        assertEquals(1, after.returned)
        assertEquals(0, after.eligible)
        assertEquals(1, after.reasons["excluded_transit_country"])

        val repeat = BatchSweep.run(config, service, confirmed = true, auditFile = journal, resume = true)
        assertEquals(0, repeat.executed)
        assertEquals(1, repeat.resumedSkipped)
        assertEquals(1, provider.calls)

        journal.writeText("corrupted journal")
        assertFailsWith<Exception> { BatchSweep.run(config, service, confirmed = true, auditFile = journal, resume = true) }
        assertEquals(1, provider.calls)
    }
}
