package com.clawfare

import com.clawfare.search.AppJson
import com.clawfare.search.BatchConfig
import com.clawfare.search.BatchScenario
import com.clawfare.search.BatchSweep
import com.clawfare.search.Direction
import com.clawfare.search.FareSink
import com.clawfare.search.Itinerary
import com.clawfare.search.Leg
import com.clawfare.search.PartialUseRefundProof
import com.clawfare.search.RefundEvidence
import com.clawfare.search.ResultSet
import com.clawfare.search.SearchQuery
import com.clawfare.search.SellerRefundProof
import com.clawfare.search.StoredResult
import com.clawfare.search.TicketUse
import com.clawfare.search.TicketUsePlan
import com.clawfare.search.exactRefundItineraryKey
import com.clawfare.search.sha256
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TicketUseTest {
    private val now = Instant.parse("2026-11-16T12:00:00Z")

    private fun itinerary(): Itinerary {
        val out =
            Leg(
                "SA 1",
                "Synthetic Airline",
                "SA",
                "Synthetic Airline",
                "LHR",
                "HND",
                "11:00",
                "08:00",
                "2028-06-01",
                "2028-06-02",
                cabin = "Business",
            )
        val back =
            Leg(
                "SA 2",
                "Synthetic Airline",
                "SA",
                "Synthetic Airline",
                "HND",
                "LHR",
                "10:00",
                "18:00",
                "2028-06-20",
                "2028-06-20",
                cabin = "Business",
            )
        return Itinerary(
            id = "off_synthetic",
            source = "serpapi",
            sourceLabel = "live",
            livePrice = true,
            pricePerPerson = 2250.0,
            passengers = 2,
            cabinKey = "business",
            fareBrand = "Business Flex",
            outbound = Direction(legs = listOf(out)),
            returning = Direction(legs = listOf(back)),
            outboundDate = "2028-06-01",
            returnDate = "2028-06-20",
            nights = 19,
            refundEvidence = RefundEvidence(offerId = "off_synthetic", sellerId = "airline-direct"),
        )
    }

    private fun proof(it: Itinerary = itinerary()): PartialUseRefundProof =
        PartialUseRefundProof(
            offerId = "off_synthetic",
            sellerId = "airline-direct",
            itinerarySha256 = exactRefundItineraryKey(it),
            fareBrand = "Business Flex",
            partySize = 2,
            sourceUrl = "https://example.test/exact-fare-conditions",
            termsSha256 = sha256("synthetic seller-partial-use agreement"),
            reviewedAt = "2026-11-16T10:00:00Z",
            expiresAt = "2026-11-17T10:00:00Z",
            cancelRemainingCouponAllowed = true,
            unusedCouponRefundGbp = 700.0,
            postDepartureRepricingGbp = 150.0,
            airlineCancellationFeeGbp = 50.0,
            sellerServicingFeeGbp = 0.0,
            travellerCashRefundGbp = 500.0,
            refundToOriginalPayment = true,
            reviewedManually = true,
        )

    @Test
    fun wholeTicketGenuineReturnAndOneWayAreDifferentProducts() {
        val ticket = itinerary()
        val oneWay = ticket.copy(pricePerPerson = 2000.0, returning = null, returnDate = null, nights = null)
        val ow = TicketUse.evaluate(oneWay, TicketUsePlan("one_way"), now)
        val rt = TicketUse.evaluate(ticket, TicketUsePlan("genuine_return"), now)
        assertEquals(4000.0, ow.netTicketGbp)
        assertEquals(4500.0, rt.netTicketGbp)
        assertEquals(4500.0, rt.grossTicketGbp)
        assertNull(TicketUse.evaluate(ticket, TicketUsePlan("one_way"), now).netTicketGbp)
        assertNull(TicketUse.evaluate(oneWay, TicketUsePlan("genuine_return"), now).netTicketGbp)
    }

    @Test
    fun unusedInboundSavesOnlySubstantiatedNetCashAfterRepricingAndFees() {
        val it = itinerary()
        val plan = TicketUsePlan("cancel_inbound", "airline-direct", partialRefundProof = proof())
        val outcome = TicketUse.evaluate(it, plan, now)
        assertEquals(4500.0, outcome.grossTicketGbp)
        assertEquals(500.0, outcome.travellerCashRefundGbp)
        assertEquals(4000.0, outcome.netTicketGbp)
        assertEquals("computable", outcome.state)
        val repriced =
            TicketUse.evaluate(
                it,
                plan.copy(
                    partialRefundProof =
                        proof().copy(
                            unusedCouponRefundGbp = 700.0,
                            postDepartureRepricingGbp = 450.0,
                            airlineCancellationFeeGbp = 50.0,
                            travellerCashRefundGbp = 200.0,
                        ),
                ),
                now,
            )
        assertEquals(4300.0, repriced.netTicketGbp)
    }

    @Test
    fun noProofNoShowAndFullPretravelRefundClaimsNeverBecomeSavings() {
        val ticket = itinerary()
        val unknown = TicketUse.evaluate(ticket, TicketUsePlan("cancel_inbound"), now)
        assertEquals(4500.0, unknown.grossTicketGbp)
        assertNull(unknown.netTicketGbp)
        assertTrue(unknown.note.contains("Gross price is shown only"))
        val noShow = TicketUse.evaluate(ticket, TicketUsePlan("no_show_inbound"), now)
        assertNull(noShow.netTicketGbp)
        assertTrue(noShow.note.contains("repriced"))
        assertNull(TicketUse.evaluate(ticket, TicketUsePlan("cancel_whole_before_outbound"), now).netTicketGbp)
        assertEquals("not_computable", noShow.state)
    }

    @Test
    fun proofBindsExactFarePartySellerOfferTermsAndTime() {
        val ticket = itinerary()
        val baseline = proof()

        fun net(
            p: PartialUseRefundProof,
            offer: Itinerary = ticket,
        ): Double? = TicketUse.evaluate(offer, TicketUsePlan("cancel_inbound", "airline-direct", p), now).netTicketGbp

        assertNull(net(baseline.copy(travellerCashRefundGbp = 600.0)))
        assertNull(net(baseline.copy(refundToOriginalPayment = false)))
        assertNull(net(baseline.copy(airlineCancellationFeeGbp = null)))
        assertNull(net(baseline.copy(reviewedManually = false)))
        assertNull(net(baseline.copy(sourceUrl = "https://example.test/terms?booking_token=PRIVATE")))
        assertNull(net(baseline.copy(termsSha256 = "made-up")))
        assertNull(net(baseline.copy(expiresAt = "2026-11-15T00:00:00Z")))
        assertNull(net(baseline.copy(sellerId = "another-seller")))
        assertNull(net(baseline, ticket.copy(passengers = 3)))
        assertNull(net(baseline, ticket.copy(fareBrand = "Business Basic")))
        assertNull(net(baseline, ticket.copy(outboundDate = "2028-06-02")))
        assertFalse(AppJson.encodeToString(PartialUseRefundProof.serializer(), baseline).contains("PRIVATE"))
    }

    @Test
    fun sourcedMaximumStayAndCouponSequenceAreChecked() {
        val ticket = itinerary()
        val invalid =
            TicketUsePlan(
                "genuine_return",
                minStayDays = 30,
                maxStayDays = 90,
                stayRuleSourceUrl = "https://example.test/minimum-stay",
            )
        assertNull(TicketUse.evaluate(ticket, invalid, now).netTicketGbp)
        val valid = invalid.copy(minStayDays = 10, maxStayDays = 30)
        assertEquals(4500.0, TicketUse.evaluate(ticket, valid, now).netTicketGbp)
        val brokenSequence =
            TicketUse.evaluate(
                ticket.copy(returning = null, returnMissing = true),
                valid,
                now,
            )
        assertNull(brokenSequence.netTicketGbp)
    }

    @Test
    fun batchReportLeavesUnknownUnusedReturnUnranked() {
        val ticket = itinerary()
        val q =
            SearchQuery(
                origins = listOf("LHR"),
                destinations = listOf("HND"),
                outboundFrom = "2028-06-01",
                tripType = "round_trip",
                returnFrom = "2028-06-20",
                providers = listOf("serpapi"),
                adults = 2,
                cabin = "business",
            )
        val scenario =
            BatchScenario(
                id = "partial",
                query = q,
                positioningGbp = 0.0,
                hotelsGbp = 0.0,
                groundTransfersGbp = 0.0,
                baggageGbp = 0.0,
                ticketUsePlan = TicketUsePlan("no_show_inbound"),
            )
        val config = BatchConfig("coupon", listOf(scenario), maxLiveCalls = 0)
        val key = BatchSweep.expand(config).single().signature
        val sink =
            object : FareSink {
                override fun save(stored: StoredResult): ResultSet = stored.result

                override fun load(id: String): StoredResult? =
                    if (id == key) StoredResult(ResultSet(id, "synthetic", itineraries = listOf(ticket))) else null

                override fun list(): List<ResultSet> = emptyList()
            }
        val row = BatchSweep.compare(config, sink).single()
        assertEquals(4500.0, row.partyFareGbp)
        assertEquals("no_show_inbound", row.ticketUseMode)
        assertNull(row.allInGbp)
        assertNull(row.netPartyTicketGbp)
        assertTrue(row.ticketUseNote!!.contains("repriced"))
    }

    @Test
    fun completePretravelRefundIsNeverRankedAsAFreeFlight() {
        val raw = itinerary()
        val clock = Instant.now()
        val proof =
            SellerRefundProof(
                sellerId = "airline-direct",
                offerId = "off_synthetic",
                itinerarySha256 = exactRefundItineraryKey(raw),
                partySize = 2,
                fareBrand = "Business Flex",
                termsSourceUrl = "https://example.test/refund-terms",
                termsSha256 = sha256("synthetic whole-ticket conditions"),
                reviewedAt = clock.minusSeconds(60).toString(),
                expiresAt = clock.plusSeconds(7200).toString(),
                cancellationDeadlineAt = clock.plusSeconds(3600).toString(),
                coversFullTicketBeforeOutbound = true,
                refundToTravellerOriginalPayment = true,
                issuerPenaltyAmount = 0.0,
                sellerServicingFeeAmount = 0.0,
            )
        val ticket =
            raw.copy(
                refundEvidence =
                    RefundEvidence(
                        provider = "serpapi",
                        offerId = "off_synthetic",
                        sellerId = "airline-direct",
                        allowed = true,
                        penaltyAmount = 0.0,
                        method = "original_payment",
                        servicingFeeAmount = 0.0,
                        expiresAt = clock.plusSeconds(7200).toString(),
                        sellerProof = proof,
                    ),
            )
        val q =
            SearchQuery(
                origins = listOf("LHR"),
                destinations = listOf("HND"),
                outboundFrom = "2028-06-01",
                tripType = "round_trip",
                returnFrom = "2028-06-20",
                providers = listOf("serpapi"),
                adults = 2,
            )
        val scenario =
            BatchScenario(
                id = "cancellation",
                query = q,
                positioningGbp = 0.0,
                hotelsGbp = 0.0,
                groundTransfersGbp = 0.0,
                baggageGbp = 0.0,
                ticketUsePlan = TicketUsePlan("cancel_whole_before_outbound"),
            )
        val config = BatchConfig("nontravel", listOf(scenario))
        val signature = BatchSweep.expand(config).single().signature
        val sink =
            object : FareSink {
                override fun save(stored: StoredResult): ResultSet = stored.result

                override fun load(id: String): StoredResult? =
                    if (id == signature) StoredResult(ResultSet(id, "cancelled", itineraries = listOf(ticket))) else null

                override fun list(): List<ResultSet> = emptyList()
            }
        val result = BatchSweep.compare(config, sink).single()
        assertEquals(0.0, result.netPartyTicketGbp)
        assertEquals("computable_nontravel", result.ticketUseState)
        assertNull(result.allInGbp)
        assertTrue(result.ticketUseNote!!.contains("no journey remains"))
    }
}
