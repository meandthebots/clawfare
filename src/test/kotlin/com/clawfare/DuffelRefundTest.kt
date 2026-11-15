package com.clawfare

import com.clawfare.db.ClawfareDatabase
import com.clawfare.search.AppJson
import com.clawfare.search.DiskCache
import com.clawfare.search.DuffelProvider
import com.clawfare.search.FareStore
import com.clawfare.search.FilterSpec
import com.clawfare.search.Filters
import com.clawfare.search.HttpFetcher
import com.clawfare.search.HttpResult
import com.clawfare.search.ResultSet
import com.clawfare.search.SearchQuery
import com.clawfare.search.StoredResult
import com.clawfare.search.parseDuffelRefundEvidence
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DuffelRefundTest {
    private val observed = "2026-11-15T12:00:00Z"
    private val future = "2027-04-02T12:00:00Z"

    private fun offer(
        allowed: String = "true",
        penalty: String = "\"0.00\"",
        method: String = "",
        expiry: String = future,
        mode: String = "true",
    ): JsonObject {
        val raw =
            """{"id":"off_12345","live_mode":$mode,"expires_at":"$expiry",
            "available_passenger_count":2,
            "conditions":{"refund_before_departure":{"allowed":$allowed,"penalty_amount":$penalty,"penalty_currency":"GBP"}},
            "total_amount":"4400.00","total_currency":"GBP","slices":[{"segments":[{
            "origin":{"iata_code":"LHR"},"destination":{"iata_code":"HND"},
            "departing_at":"2027-03-23T12:00:00","arriving_at":"2027-03-24T09:00:00",
            "marketing_carrier":{"iata_code":"BA","name":"British Airways"},
            "operating_carrier":{"iata_code":"BA","name":"British Airways"},
            "marketing_carrier_flight_number":"5",
            "passengers":[{"cabin_class":"business","baggages":[{"type":"checked","quantity":2}]}]
            }]}]}"""
        return AppJson.parseToJsonElement(raw) as JsonObject
    }

    @Test
    fun zeroPenaltyDoesNotImplyCashRefundToOriginalPayment() {
        val evidence = parseDuffelRefundEvidence(offer(), observed)
        assertEquals("no_airline_penalty", evidence.refundStatus)
        assertEquals("unknown", evidence.method)
        assertFalse(evidence.isVerifiedFullCashRefund(true, 2, Instant.parse(observed)))
        assertTrue(evidence.baggageEvidence.contains("2 checked"))
        assertEquals(2, evidence.availablePassengers)
        val explicitlyProven =
            evidence.copy(
                method = "original_payment",
                servicingFeeAmount = 0.0,
            )
        assertTrue(explicitlyProven.isVerifiedFullCashRefund(true, 2, Instant.parse(observed)))
        assertFalse(explicitlyProven.isVerifiedFullCashRefund(false, 2, Instant.parse(observed)))
        assertFalse(explicitlyProven.isVerifiedFullCashRefund(true, 3, Instant.parse(observed)))
        assertFalse(explicitlyProven.isVerifiedFullCashRefund(true, 2, Instant.parse("2027-05-01T12:00:00Z")))
    }

    @Test
    fun disallowedUnknownAndPositivePenaltyNeverPass() {
        val denied = parseDuffelRefundEvidence(offer(allowed = "false"), observed)
        assertEquals("nonrefundable", denied.refundStatus)
        val unknownPenalty = parseDuffelRefundEvidence(offer(penalty = "null"), observed)
        assertEquals("conditional_or_penalty_unknown", unknownPenalty.refundStatus)
        val charged = parseDuffelRefundEvidence(offer(penalty = "\"50.00\""), observed)
        assertEquals("conditional_or_penalty_unknown", charged.refundStatus)
        val missing = parseDuffelRefundEvidence(offer(allowed = "null", penalty = "null"), observed)
        assertEquals("unverified", missing.refundStatus)
    }

    @Test
    fun sandboxNeverAppearsLiveAndLiveRequiresExplicitOptIn() {
        val fetchedUrls = mutableListOf<String>()
        val response = """{"data":{"offers":[${AppJson.encodeToString(JsonObject.serializer(), offer())}]}}"""
        val http =
            object : HttpFetcher {
                override fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    body: String?,
                ): HttpResult {
                    fetchedUrls += "$method $url"
                    return HttpResult(200, response)
                }
            }
        val q =
            SearchQuery(
                origins = listOf("LHR"),
                destinations = listOf("HND"),
                outboundFrom = "2027-03-23",
                tripType = "one_way",
                adults = 2,
                cabin = "business",
            )

        fun emptyCache(): DiskCache {
            val temp = File.createTempFile("duffel-cache", "")
            temp.delete()
            temp.mkdirs()
            return DiskCache(temp)
        }

        val sandbox = DuffelProvider("duffel_test_fixture", http, liveEnabled = false)
        val sharedCache = emptyCache()
        val testFare = sandbox.search(q, sharedCache, allowNetwork = true).itineraries.single()
        assertEquals("test", sandbox.baseCalls(q).single().params["duffel_mode"])
        assertEquals("mock", testFare.sourceLabel)
        assertFalse(Filters.matches(testFare, FilterSpec(verifiedCashRefundOnly = true)))
        val disabled = DuffelProvider("duffel_live_fixture", http, liveEnabled = false)
        assertFalse(disabled.configured)
        assertEquals("mock", disabled.priceKind)
        val live = DuffelProvider("duffel_live_fixture", http, liveEnabled = true)
        assertTrue(live.configured)
        val actualFare = live.search(q, sharedCache, allowNetwork = true).itineraries.single()
        assertEquals("live", live.baseCalls(q).single().params["duffel_mode"])
        assertEquals("live", actualFare.sourceLabel)
        assertEquals(2200.0, actualFare.pricePerPerson)
        assertEquals("no_airline_penalty", actualFare.refundEvidence?.refundStatus)
        assertFalse(Filters.matches(actualFare, FilterSpec(verifiedCashRefundOnly = true)))
        // Reusing the same cache must NOT promote sandbox prices to live.
        assertEquals(2, fetchedUrls.size)
        val cachedAgain = live.search(q, sharedCache, allowNetwork = true).itineraries.single()
        assertEquals("cached", cachedAgain.sourceLabel)
        assertFalse(cachedAgain.livePrice)
        assertEquals(2, fetchedUrls.size)
        assertTrue(fetchedUrls.all { it.endsWith("/air/offer_requests?return_offers=true") })
        assertTrue(fetchedUrls.all { it.startsWith("POST ") })
    }

    @Test
    fun offerModeAndExpiryCannotTurnSandboxPricesIntoBookableOffers() {
        fun searchThis(offer: JsonObject): com.clawfare.search.Itinerary {
            val payload = """{"data":{"offers":[${AppJson.encodeToString(JsonObject.serializer(), offer)}]}}"""
            val http =
                object : HttpFetcher {
                    override fun request(
                        method: String,
                        url: String,
                        headers: Map<String, String>,
                        body: String?,
                    ): HttpResult = HttpResult(200, payload)
                }
            val q =
                SearchQuery(
                    origins = listOf("LHR"),
                    destinations = listOf("HND"),
                    outboundFrom = "2027-03-23",
                    tripType = "one_way",
                    adults = 2,
                    cabin = "business",
                )
            val cacheRoot = File.createTempFile("duffel-mode-cache", "")
            cacheRoot.delete()
            cacheRoot.mkdirs()
            return DuffelProvider("duffel_live_fixture", http, true)
                .search(q, DiskCache(cacheRoot), true)
                .itineraries
                .single()
        }

        val sandboxResponse = searchThis(offer(mode = "false"))
        assertEquals("mock", sandboxResponse.sourceLabel)
        assertFalse(sandboxResponse.livePrice)

        val expiredResponse = searchThis(offer(expiry = "2020-01-01T00:00:00Z"))
        assertEquals("expired", expiredResponse.sourceLabel)
        assertFalse(expiredResponse.livePrice)

        val missingMode = searchThis(offer(mode = "null"))
        assertEquals("mock", missingMode.sourceLabel)
        assertFalse(missingMode.livePrice)
    }

    @Test
    fun liveRefundEvidenceSurvivesSqliteSaveAndReopen() {
        ClawfareDatabase.connectInMemory()
        val q =
            SearchQuery(
                origins = listOf("LHR"),
                destinations = listOf("HND"),
                outboundFrom = "2027-03-23",
                tripType = "one_way",
                adults = 2,
            )
        val http =
            object : HttpFetcher {
                override fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    body: String?,
                ): HttpResult = HttpResult(200, """{"data":{"offers":[${AppJson.encodeToString(JsonObject.serializer(), offer())}]}}""")
            }
        val directory =
            File.createTempFile("duffel-db", "").apply {
                delete()
                mkdirs()
            }
        val flight = DuffelProvider("duffel_live_fixture", http, true).search(q, DiskCache(directory), true).itineraries.single()
        FareStore.save(StoredResult(ResultSet(id = "duffel-refund", title = "Duffel refund check", itineraries = listOf(flight))))
        val restored = FareStore.project("duffel-refund")!!.itineraries.single()
        assertEquals("no_airline_penalty", restored.refundEvidence?.refundStatus)
        assertNotNull(restored.refundEvidence?.offerId)
        assertFalse(Filters.matches(restored, FilterSpec(verifiedCashRefundOnly = true)))
    }

    @Test
    fun reopeningOldStoredLiveQuoteDowngradesItsStatus() {
        ClawfareDatabase.connectInMemory()
        val q =
            SearchQuery(
                origins = listOf("LHR"),
                destinations = listOf("HND"),
                outboundFrom = "2027-03-23",
                tripType = "one_way",
                adults = 2,
                cabin = "business",
            )
        val payload = """{"data":{"offers":[${AppJson.encodeToString(JsonObject.serializer(), offer())}]}}"""
        val http =
            object : HttpFetcher {
                override fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    body: String?,
                ): HttpResult = HttpResult(200, payload)
            }
        val cacheRoot = File.createTempFile("duffel-stale", "")
        cacheRoot.delete()
        cacheRoot.mkdirs()
        val fresh = DuffelProvider("duffel_live_fixture", http, true).search(q, DiskCache(cacheRoot), true).itineraries.single()
        val historical = fresh.copy(fetchedAt = "2020-01-01T10:00:00Z")
        FareStore.save(StoredResult(ResultSet(id = "stale-live-quote", title = "stale quote", itineraries = listOf(historical))))
        val reopened = FareStore.project("stale-live-quote")!!.itineraries.single()
        assertEquals("cached", reopened.sourceLabel)
        assertFalse(reopened.livePrice)
    }

    @Test
    fun exactOfferRefreshUsesOnlyGetAndNeverBooks() {
        val methods = mutableListOf<String>()
        val http =
            object : HttpFetcher {
                override fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    body: String?,
                ): HttpResult {
                    methods += "$method $url"
                    return HttpResult(200, """{"data":${AppJson.encodeToString(JsonObject.serializer(), offer())}}""")
                }
            }
        val provider = DuffelProvider("duffel_live_fixture", http, true)
        val snapshot = provider.refreshOfferWithPricing("off_12345")
        assertEquals("no_airline_penalty", snapshot.refund.refundStatus)
        assertEquals(4400.0, snapshot.totalAmount)
        assertEquals("GBP", snapshot.totalCurrency)
        assertEquals(listOf("GET https://api.duffel.com/air/offers/off_12345"), methods)
    }
}
