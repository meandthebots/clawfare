package com.clawfare

import com.clawfare.search.BatchConfig
import com.clawfare.search.BatchScenario
import com.clawfare.search.CallSpec
import com.clawfare.search.DiskCache
import com.clawfare.search.FareProvider
import com.clawfare.search.FareSink
import com.clawfare.search.FareWatch
import com.clawfare.search.ProviderOutcome
import com.clawfare.search.QuotaView
import com.clawfare.search.ResponseCache
import com.clawfare.search.ResultSet
import com.clawfare.search.SampleData
import com.clawfare.search.SearchQuery
import com.clawfare.search.SearchService
import com.clawfare.search.StoredResult
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FareWatchTest {
    private val query =
        SearchQuery(
            origins = listOf("LHR"),
            destinations = listOf("HND"),
            outboundFrom = "2027-08-14",
            tripType = "one_way",
            providers = listOf("serpapi"),
            adults = 2,
            checkedBags = 0,
        )
    private val config =
        BatchConfig(
            "watch-fixture",
            listOf(BatchScenario("direct", query, 0.0, 0.0, 0.0, 0.0)),
            maxLiveCalls = 1,
            maxDailyLiveCalls = 3,
            minQuotaReserve = 5,
            requireNumericQuota = true,
        )

    private class Scripted : FareProvider {
        override val id = "serpapi"
        override val displayName = "Scripted"
        override val configured = true
        override val priceKind = "live"
        var remaining = 50
        var price = 1600.0
        var calls = 0
        var fareBrand = "Basic"
        var currency = "GBP"
        var originalPrice: Double? = null

        override fun quota(): QuotaView = QuotaView(id, true, remaining = remaining)

        override fun baseCalls(query: SearchQuery): List<CallSpec> = listOf(CallSpec(id, "scripted", mapOf("date" to query.outboundFrom)))

        override fun search(
            query: SearchQuery,
            cache: ResponseCache,
            allowNetwork: Boolean,
        ): ProviderOutcome {
            calls++
            val fare =
                SampleData.florida().itineraries.first().copy(
                    id = "scripted",
                    pricePerPerson = price,
                    priceOriginal = originalPrice,
                    priceCurrency = currency,
                    fareBrand = fareBrand,
                    source = id,
                    sourceLabel = "live",
                    fetchedAt = Instant.now().toString(),
                    livePrice = true,
                    passengers = 2,
                )
            return ProviderOutcome(itineraries = listOf(fare), liveCalls = 1)
        }
    }

    private class MemorySink : FareSink {
        val records = mutableMapOf<String, StoredResult>()

        override fun save(stored: StoredResult): ResultSet {
            records[stored.result.id] = stored
            return stored.result
        }

        override fun load(id: String): StoredResult? = records[id]

        override fun list(): List<ResultSet> = records.values.map { it.result }
    }

    @Test
    fun oneShotWatchOnlyAlertsOnNewOrMaterialPriceDrops() {
        val source = Scripted()
        val sink = MemorySink()
        val dir =
            File.createTempFile("watch-run", "").apply {
                delete()
                mkdirs()
            }
        val service = SearchService(listOf(source), DiskCache(dir), sink)

        fun cycle() =
            FareWatch.runOnce(
                config,
                service,
                sink,
                dir.resolve("watch.lock"),
                dir.resolve("audit.ndjson"),
                dir.resolve("alerts.ndjson"),
                thresholdGbp = 100.0,
                confirmed = true,
            )

        val initial = cycle()
        assertEquals(1, initial.alerts.size)
        assertEquals("new_quote", initial.alerts.single().reason)
        assertEquals("fare_and_cash_refund_unverified", initial.alerts.single().verification)
        val repeat = cycle()
        assertTrue(repeat.alerts.isEmpty())
        source.price = 1450.0
        val lower = cycle()
        assertEquals(1, lower.alerts.size)
        assertEquals(300.0, lower.alerts.single().changeGbp)
        assertEquals(3, source.calls)
        assertEquals(3, dir.resolve("audit.ndjson").readLines().size)
        assertEquals(3, dir.resolve("batch-budget.ndjson").readLines().size)
        assertEquals(2, dir.resolve("alerts.ndjson").readLines().size)
        assertFailsWith<IllegalArgumentException> { cycle() }
        assertEquals(3, source.calls)
    }

    @Test
    fun differentFareBrandIsNotReportedAsAPriceDropAndIdsAreDurable() {
        val source = Scripted()
        val sink = MemorySink()
        val dir =
            File.createTempFile("watch-products", "").apply {
                delete()
                mkdirs()
            }
        val service = SearchService(listOf(source), DiskCache(dir), sink)
        val journal = dir.resolve("alerts.ndjson")
        val privateConfig = config.copy(maxDailyLiveCalls = 4)

        fun run() =
            FareWatch.runOnce(
                privateConfig,
                service,
                sink,
                dir.resolve("watch.lock"),
                dir.resolve("audit.ndjson"),
                journal,
                100.0,
                confirmed = true,
            )

        val initial = run().alerts.single()
        source.fareBrand = "Flex"
        source.price = 1200.0
        val changed = run().alerts.single()
        assertEquals("new_quote", changed.reason)
        assertEquals(null, changed.changeGbp)
        assertTrue(initial.alertId != changed.alertId)

        // Rewinding saved search history cannot resurrect a delivered alert.
        sink.records.clear()
        val repeated = run()
        assertTrue(repeated.alerts.isEmpty())
        assertEquals(2, journal.readLines().size)
    }

    @Test
    fun unchangedOriginalCurrencyFareSuppressesFxOnlyRevaluation() {
        val source = Scripted()
        source.currency = "EUR"
        source.originalPrice = 1400.0
        val sink = MemorySink()
        val dir =
            File.createTempFile("watch-fx", "").apply {
                delete()
                mkdirs()
            }
        val service = SearchService(listOf(source), DiskCache(dir), sink)

        fun run() =
            FareWatch.runOnce(
                config,
                service,
                sink,
                dir.resolve("watch.lock"),
                dir.resolve("audit.ndjson"),
                dir.resolve("alerts.ndjson"),
                100.0,
                confirmed = true,
            )

        assertEquals(1, run().alerts.size)
        source.price = 1250.0
        val fxOnly = run()
        assertTrue(fxOnly.alerts.isEmpty())
        assertEquals(1, fxOnly.suppressedFxOnlyChanges)
    }

    @Test
    fun malformedDailyLedgerFailsClosedBeforePaidSearch() {
        val source = Scripted()
        val sink = MemorySink()
        val directory = File.createTempFile("watch-ledger", "")
        directory.delete()
        directory.mkdirs()
        directory.resolve("batch-budget.ndjson").writeText("not valid JSON")
        val service = SearchService(listOf(source), DiskCache(directory), sink)
        assertFailsWith<Exception> {
            FareWatch.runOnce(
                config,
                service,
                sink,
                directory.resolve("watch.lock"),
                directory.resolve("audit.ndjson"),
                directory.resolve("alerts.ndjson"),
                100.0,
                confirmed = true,
            )
        }
        assertEquals(0, source.calls)
    }

    @Test
    fun unavailableNumericQuotaFailsBeforeAnyFlightCall() {
        val source = Scripted()
        source.remaining = 1
        val sink = MemorySink()
        val dir =
            File.createTempFile("watch-cap", "").apply {
                delete()
                mkdirs()
            }
        val service = SearchService(listOf(source), DiskCache(dir), sink)
        assertFailsWith<IllegalArgumentException> {
            FareWatch.runOnce(
                config,
                service,
                sink,
                dir.resolve("watch.lock"),
                dir.resolve("audit.ndjson"),
                dir.resolve("alerts.ndjson"),
                100.0,
                confirmed = true,
            )
        }
        assertEquals(0, source.calls)
    }
}
