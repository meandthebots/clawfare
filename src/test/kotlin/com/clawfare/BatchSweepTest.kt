package com.clawfare

import com.clawfare.search.BatchConfig
import com.clawfare.search.BatchScenario
import com.clawfare.search.BatchSweep
import com.clawfare.search.CallSpec
import com.clawfare.search.DiskCache
import com.clawfare.search.FareProvider
import com.clawfare.search.FareSink
import com.clawfare.search.GoogleFlightsProvider
import com.clawfare.search.GoogleFlightsStyle
import com.clawfare.search.HttpFetcher
import com.clawfare.search.HttpResult
import com.clawfare.search.NoopSink
import com.clawfare.search.ProviderOutcome
import com.clawfare.search.QuotaView
import com.clawfare.search.ResponseCache
import com.clawfare.search.ResultSet
import com.clawfare.search.SampleData
import com.clawfare.search.SearchQuery
import com.clawfare.search.SearchService
import com.clawfare.search.StoredResult
import com.clawfare.search.TransitRules
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatchSweepTest {
    private val dates =
        SearchQuery(
            origins = listOf("LHR", "CDG", "FRA", "MUC", "AMS", "CPH"),
            destinations = listOf("HND", "NRT"),
            outboundFrom = "2027-09-12",
            outboundTo = "2027-09-14",
            tripType = "one_way",
            cabin = "business",
            adults = 2,
            providers = listOf("serpapi"),
            excludedTransitCountries = listOf("CN", "HK", "MO"),
        )

    private fun stubProvider(): FareProvider =
        object : FareProvider {
            override val id = "serpapi"
            override val displayName = "Stub"
            override val configured = true
            override val priceKind = "live"

            override fun quota(): QuotaView = QuotaView(id, true)

            override fun baseCalls(query: SearchQuery): List<CallSpec> =
                listOf(CallSpec(id, "scripted", mapOf("origin" to query.origins.first(), "day" to query.outboundFrom)))

            override fun search(
                query: SearchQuery,
                cache: ResponseCache,
                allowNetwork: Boolean,
            ): ProviderOutcome =
                ProviderOutcome(
                    listOf(
                        SampleData.florida().itineraries.first().copy(
                            id = "fixture-${query.outboundFrom}",
                            source = "serpapi",
                            sourceLabel = "live",
                            livePrice = true,
                            pricePerPerson = 1750.0,
                            passengers = 2,
                        ),
                    ),
                )
        }

    private fun service(provider: FareProvider = stubProvider()): SearchService {
        val dir =
            File.createTempFile("batch-cache", "").apply {
                delete()
                mkdirs()
            }
        return SearchService(listOf(provider), DiskCache(dir), NoopSink)
    }

    @Test
    fun matrixExpandsSixOriginsTimesTwoDestinationsTimesThreeDays() {
        val config = BatchConfig("sweep", listOf(BatchScenario("direct", dates)), maxLiveCalls = 40)
        val expanded = BatchSweep.expand(config)
        assertEquals(36, expanded.size)
        assertEquals(36, expanded.map { it.signature }.toSet().size)
        assertTrue(
            expanded.all { entry ->
                val storedId = com.clawfare.search.normalizeQuery(entry.query).investigation
                entry.signature == storedId
            },
        )
        assertTrue(expanded.all { it.query.origins.size == 1 && it.query.destinations.size == 1 })
        val estimated = BatchSweep.estimate(config, service())
        assertEquals(36, estimated.liveCalls)
        assertEquals(0, estimated.extraReturnCalls)
        assertEquals(36, estimated.upperBound)
    }

    @Test
    fun matrixNeverSpendsPastHardUpperBound() {
        val config = BatchConfig("budget", listOf(BatchScenario("limited", dates)), maxLiveCalls = 4)
        val network = service()
        assertFailsWith<IllegalArgumentException> {
            BatchSweep.run(config, network, confirmed = true)
        }
        assertFailsWith<IllegalArgumentException> {
            BatchSweep.run(config.copy(maxLiveCalls = 40), network, confirmed = false)
        }
    }

    @Test
    fun transitCountryRestrictionsFailClosedIncludingUnknownAndCodeshares() {
        val base = SampleData.florida().itineraries.first()
        val original = base.outbound.legs.first()
        val first = original.copy(from = "LHR", to = "HKG")
        val second = first.copy(from = "HKG", to = "HND")
        val viaHongKong = base.copy(outbound = base.outbound.copy(legs = listOf(first, second)))
        assertFalse(TransitRules.allows(viaHongKong, emptyList(), listOf("CN", "HK", "MO")))
        assertFalse(TransitRules.allows(viaHongKong, listOf("HKG"), emptyList()))
        val unknown = base.copy(outbound = base.outbound.copy(legs = listOf(first.copy(to = "ZZZ"), second.copy(from = "ZZZ"))))
        assertFalse(TransitRules.allows(unknown, emptyList(), listOf("CN", "HK", "MO")))
        assertTrue(TransitRules.allows(viaHongKong, emptyList(), emptyList()))
    }

    @Test
    fun correctOriginMarketIsUsedByGoogleFlights() {
        val noHttp =
            object : HttpFetcher {
                override fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    body: String?,
                ): HttpResult = error("No network expected")
            }
        val provider = GoogleFlightsProvider("serpapi", "Test", "key", GoogleFlightsStyle.SERPAPI, noHttp)
        val q = dates.copy(origins = listOf("FRA"), destinations = listOf("HND"), market = "de", currency = "EUR")
        val params = provider.baseCalls(q).first().params
        assertEquals("de", params["gl"])
        assertEquals("EUR", params["currency"])
        assertEquals("2", params["adults"])
    }

    @Test
    fun allInTotalsIncludeWholePartyStopoverCostsOrRemainUnknown() {
        val scenario =
            BatchScenario(
                "short-break",
                dates.copy(origins = listOf("CDG"), destinations = listOf("HND"), outboundTo = "2027-09-12"),
                positioningGbp = 250.0,
                hotelsGbp = 320.0,
                groundTransfersGbp = 60.0,
                baggageGbp = 40.0,
            )
        val config = BatchConfig("totals", listOf(scenario), 0)
        val entry = BatchSweep.expand(config).single()
        val fare =
            SampleData.florida().itineraries.first().copy(
                source = "serpapi",
                sourceLabel = "live",
                pricePerPerson = 1500.0,
                passengers = 2,
            )
        val sink =
            object : FareSink {
                override fun save(stored: StoredResult): ResultSet = stored.result

                override fun load(id: String): StoredResult? =
                    if (id == entry.signature) StoredResult(ResultSet(id, "fixture", itineraries = listOf(fare))) else null

                override fun list(): List<ResultSet> = emptyList()
            }
        assertEquals(3670.0, BatchSweep.compare(config, sink).single().allInGbp)
        val incomplete =
            BatchSweep.compare(
                config.copy(scenarios = listOf(scenario.copy(hotelsGbp = null))),
                sink,
            )
        assertNull(incomplete.single().allInGbp)
    }

    @Test
    fun publicDomainAirportCsvMapsCodesToCountry() {
        val file = File.createTempFile("ourairports", ".csv")
        file.writeText("id,ident,name,iso_country,iata_code\n1,ZBAA,\"Beijing, Capital\",CN,PEK\n2,EGLL,Heathrow,GB,LHR\n")
        val mapping = TransitRules.parseOurAirports(file)
        assertEquals("CN", mapping["PEK"])
        assertEquals("GB", mapping["LHR"])
    }
}
