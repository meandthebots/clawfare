package com.clawfare

import com.clawfare.cli.ClawfareCommand
import com.clawfare.db.ClawfareDatabase
import com.clawfare.db.FlightDto
import com.clawfare.db.FlightWithPrice
import com.clawfare.db.InvestigationDto
import com.clawfare.db.InvestigationQueries
import com.clawfare.export.TopNGenerator
import com.clawfare.search.CachedResponse
import com.clawfare.search.DuffelProvider
import com.clawfare.search.FilterSpec
import com.clawfare.search.Filters
import com.clawfare.search.FreshResponseCache
import com.clawfare.search.FxRateStore
import com.clawfare.search.HttpFetcher
import com.clawfare.search.HttpResult
import com.clawfare.search.MarketQuotes
import com.clawfare.search.ResponseCache
import com.clawfare.search.SampleData
import com.clawfare.search.SearchQuery
import com.clawfare.search.SellerQuote
import com.clawfare.search.VendorMath
import com.clawfare.search.isEuropeanOperator
import com.clawfare.web.Server
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MuseAuditRegressionTest {
    @Test
    fun webEstimatesExactlyTheMarketsThatItConfirms() {
        val page = Server.page()
        assertTrue(page.contains("var vendorMarkets = [\"uk\", \"us\"];"))
        assertEquals(2, Regex("markets: vendorMarkets").findAll(page).count())
        assertFalse(page.contains("markets: [\"uk\"], cacheOnly"))
    }

    @Test
    fun feesScaleByBagDirectionAndPassengerWithUnknownsUnranked() {
        assertEquals(0.0, VendorMath.knownBagFee("1st checked bag: free", 2, 1, 2))
        assertEquals(160.0, VendorMath.knownBagFee("1st checked bag: 40", 2, 1, 2))
        assertEquals(600.0, VendorMath.knownBagFee("1st checked bag: 50, 2nd checked bag: 100", 2, 2, 2))
        assertNull(VendorMath.knownBagFee("1st checked bag: 40", 2, 2, 2))
        assertNull(VendorMath.knownBagFee("1 free carry-on", 2, 1, 2))
        val quotes =
            mapOf(
                "uk" to
                    MarketQuotes(
                        roundTrip =
                            mapOf(
                                "unknown" to SellerQuote("unknown", 800.0, "1 free carry-on"),
                                "known" to SellerQuote("known", 840.0, "1st checked bag: 40"),
                            ),
                    ),
            )
        val rows = VendorMath.rows(quotes, checkedBags = 1, passengers = 2)
        assertEquals("known", rows.first().vendor)
        assertNotNull(rows.first().allInGbp)
        assertNull(rows.last().allInGbp)
    }

    @Test
    fun lotCodesharesAreBlockedBeforeDisplayAndAreNotPreferredEuropeanOperators() {
        assertFalse(isEuropeanOperator("LOT Polish Airlines"))
        assertFalse(isEuropeanOperator("LO"))
        assertEquals(listOf("LO"), SearchQuery().excludedAirlines)
        val original = SampleData.florida().itineraries.first()
        val first = original.outbound.legs.first()
        val lo = first.copy(airlineCode = "BA", operatingCarrier = "LOT Polish Airlines")
        val candidate = original.copy(outbound = original.outbound.copy(legs = listOf(lo)))
        assertFalse(Filters.matches(candidate, FilterSpec(airlineBlock = listOf("LO"))))
        assertTrue(Filters.matches(candidate, FilterSpec(airlineBlock = emptyList())))
    }

    @Test
    fun duffelEstimateKeysIncludePassengerCount() {
        val network =
            object : HttpFetcher {
                override fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    body: String?,
                ): HttpResult = error("Unexpected network call")
            }
        val query =
            SearchQuery(
                origins = listOf("CDG"),
                destinations = listOf("HND"),
                outboundFrom = "2027-03-24",
                tripType = "one_way",
                adults = 2,
            )
        val calls = DuffelProvider("duffel_test_key", network).baseCalls(query)
        assertEquals("2", calls.single().params["adults"])
    }

    @Test
    fun onlyFreshLiveResponsesCountAsCachedWithoutDiscardingCalendarCache() {
        val now = Instant.parse("2026-11-15T21:00:00Z")
        val old = CachedResponse("2026-10-01T12:00:00Z", "serpapi", emptyMap(), "{}")
        val current = CachedResponse("2026-11-15T20:00:00Z", "serpapi", emptyMap(), "{}")
        var saved = old
        val backing =
            object : ResponseCache {
                override fun get(
                    provider: String,
                    params: Map<String, String>,
                ): CachedResponse = saved

                override fun put(
                    provider: String,
                    params: Map<String, String>,
                    body: String,
                    secrets: List<String>,
                ) {
                    saved = current
                }
            }
        val cache = FreshResponseCache(backing, Duration.ofHours(2), Clock.fixed(now, ZoneOffset.UTC))
        assertNull(cache.get("serpapi", emptyMap()))
        assertNotNull(cache.get("travelpayouts", emptyMap()))
        cache.put("serpapi", emptyMap(), "{}", emptyList())
        assertNotNull(cache.get("serpapi", emptyMap()))
    }

    @Test
    fun topNExcludesMocksAndComparesCurrencies() {
        fun priced(
            id: String,
            amount: Double,
            currency: String,
            label: String,
        ): FlightWithPrice {
            val flight =
                FlightDto(
                    id = id,
                    investigationSlug = "fixture",
                    source = "serpapi",
                    tripType = "one_way",
                    ticketStructure = "single",
                    origin = "LHR",
                    destination = "HND",
                    outboundJson = "{}",
                    sourceLabel = label,
                )
            return FlightWithPrice(flight, amount, currency, "UK", "2026-11-15", "serpapi", "https://example.com")
        }
        val ranked =
            TopNGenerator.rankComparable(
                listOf(
                    priced("gbp", 800.0, "GBP", "live"),
                    priced("usd", 800.0, "USD", "live"),
                    priced("unknown", 1.0, "ZZZ", "live"),
                    priced("mock", 1.0, "GBP", "mock"),
                ),
                10,
            )
        assertEquals(listOf("usd", "gbp"), ranked.map { it.id })
    }

    @Test
    fun investigationUpdateWritesEveryModernSearchField() {
        ClawfareDatabase.connectInMemory()
        val first =
            InvestigationDto(
                slug = "muse-update",
                origin = "LHR",
                destination = "HND",
                departStart = "2027-03-21",
                departEnd = "2027-03-26",
            )
        InvestigationQueries.create(first)
        val updated =
            InvestigationQueries.update(
                first.copy(
                    excludedAirlines = "LO,AI",
                    origins = "LHR,CDG,FRA",
                    destinations = "HND,NRT",
                    checkedBags = 3,
                    passengers = 2,
                    currency = "EUR",
                    nights = 5,
                    scenario = "stopover",
                    queryJson = """{"adults":2}""",
                    presentationJson = """{"title":"Europe"}""",
                ),
            )
        assertNotNull(updated)
        assertEquals("LO,AI", updated.excludedAirlines)
        assertEquals("LHR,CDG,FRA", updated.origins)
        assertEquals("HND,NRT", updated.destinations)
        assertEquals(3, updated.checkedBags)
        assertEquals(2, updated.passengers)
        assertEquals("EUR", updated.currency)
        assertEquals("stopover", updated.scenario)
    }

    @Test
    fun datedFxReferenceResponseCanBeParsedWithoutNetwork() {
        val snapshot =
            FxRateStore.parse(
                """{"amount":1.0,"base":"GBP","date":"2026-11-13","rates":{"USD":1.32,"EUR":1.17,"DKK":8.78}}""",
            )
        assertEquals("2026-11-13", snapshot.date)
        assertEquals(1.0, snapshot.perGbp["GBP"])
        assertEquals(8.78, snapshot.perGbp["DKK"])
        assertEquals(0, CommandLine(ClawfareCommand()).execute("fare", "fx-refresh", "--help"))
    }

    @Test
    fun legacyCommandsSmokeTestAgainstPopulatedInvestigation() {
        ClawfareDatabase.connectInMemory()
        InvestigationQueries.create(
            InvestigationDto(
                slug = "old-commands",
                origin = "LHR",
                destination = "HND",
                departStart = "2027-03-20",
                departEnd = "2027-03-26",
            ),
        )
        assertEquals(0, CommandLine(ClawfareCommand()).execute("inv", "config", "old-commands", "--exclude-airline", "LO"))
        assertEquals("LO", InvestigationQueries.getBySlug("old-commands")!!.excludedAirlines)
        assertEquals(0, CommandLine(ClawfareCommand()).execute("inv", "config", "old-commands", "--show"))
        assertEquals(0, CommandLine(ClawfareCommand()).execute("flight", "validate", "old-commands"))
        assertEquals(1, CommandLine(ClawfareCommand()).execute("flight", "import-db", "/does-not-exist.db", "--dry-run"))
    }
}
