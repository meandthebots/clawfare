package com.clawfare

import com.clawfare.search.AppJson
import com.clawfare.search.RefundEvidence
import com.clawfare.search.SampleData
import com.clawfare.search.SellerRefundProof
import com.clawfare.search.exactRefundItineraryKey
import com.clawfare.search.isVerifiedFor
import com.clawfare.search.sha256
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RefundProofTest {
    private val at = Instant.parse("2026-11-16T01:00:00Z")

    private fun fixture(): Pair<com.clawfare.search.Itinerary, RefundEvidence> {
        val itinerary =
            SampleData.florida().itineraries.first().copy(
                source = "airline_direct",
                sourceLabel = "live",
                livePrice = true,
                passengers = 2,
                fareBrand = "Business Flex",
                farePolicyKey = "brand-1",
            )
        val proof =
            SellerRefundProof(
                sellerId = "airline_direct",
                offerId = "off_test_123",
                itinerarySha256 = exactRefundItineraryKey(itinerary),
                partySize = 2,
                fareBrand = "Business Flex",
                termsSourceUrl = "https://airline.example/terms",
                termsSha256 = sha256("Synthetic private seller-terms snapshot"),
                reviewedAt = "2026-11-15T23:00:00Z",
                expiresAt = "2026-11-16T12:00:00Z",
                cancellationDeadlineAt = "2026-11-16T12:00:00Z",
                coversFullTicketBeforeOutbound = true,
                refundToTravellerOriginalPayment = true,
                issuerPenaltyAmount = 0.0,
                sellerServicingFeeAmount = 0.0,
            )
        val evidence =
            RefundEvidence(
                provider = "airline_direct",
                offerId = "off_test_123",
                observedAt = "2026-11-15T23:00:00Z",
                expiresAt = "2026-11-16T12:00:00Z",
                allowed = true,
                penaltyAmount = 0.0,
                method = "original_payment",
                servicingFeeAmount = 0.0,
                availablePassengers = 2,
                sellerId = "airline_direct",
                sellerProof = proof,
            )
        return itinerary to evidence
    }

    @Test
    fun exactFreshSellerProofIsNeededBeyondZeroAirlinePenalty() {
        val (itinerary, evidence) = fixture()
        assertTrue(evidence.isVerifiedFor(itinerary, at))
        assertFalse(evidence.copy(sellerProof = null).isVerifiedFor(itinerary, at))
        assertFalse(evidence.copy(sellerId = null).isVerifiedFor(itinerary, at))
        assertFalse(evidence.copy(method = "travel_credit").isVerifiedFor(itinerary, at))
        assertFalse(evidence.copy(servicingFeeAmount = 20.0).isVerifiedFor(itinerary, at))
        assertFalse(evidence.copy(penaltyAmount = null).isVerifiedFor(itinerary, at))
        assertFalse(evidence.isVerifiedFor(itinerary.copy(sourceLabel = "cached"), at))
        assertFalse(evidence.isVerifiedFor(itinerary.copy(livePrice = false), at))
        assertFalse(evidence.isVerifiedFor(itinerary.copy(passengers = 3), at))
        assertFalse(evidence.isVerifiedFor(itinerary.copy(fareBrand = "Business Basic"), at))
        assertFalse(evidence.isVerifiedFor(itinerary.copy(outboundDate = "2027-01-01"), at))
    }

    @Test
    fun conflictingOrStaleSellerTermsFailClosed() {
        val (itinerary, evidence) = fixture()
        val proof = evidence.sellerProof!!
        assertFalse(evidence.copy(sellerProof = proof.copy(sellerServicingFeeAmount = 30.0)).isVerifiedFor(itinerary, at))
        assertFalse(evidence.copy(sellerProof = proof.copy(refundToTravellerOriginalPayment = false)).isVerifiedFor(itinerary, at))
        assertFalse(evidence.copy(sellerProof = proof.copy(coversFullTicketBeforeOutbound = false)).isVerifiedFor(itinerary, at))
        assertFalse(evidence.copy(sellerProof = proof.copy(termsSha256 = "undocumented")).isVerifiedFor(itinerary, at))
        assertFalse(
            evidence
                .copy(sellerProof = proof.copy(termsSourceUrl = "https://airline.example/terms?booking_token=secret"))
                .isVerifiedFor(itinerary, at),
        )
        assertFalse(evidence.copy(sellerProof = proof.copy(cancellationDeadlineAt = "2026-11-15T00:00:00Z")).isVerifiedFor(itinerary, at))
        assertFalse(evidence.copy(sellerProof = proof.copy(reviewedAt = "2026-11-10T00:00:00Z")).isVerifiedFor(itinerary, at))
        assertFalse(evidence.copy(sellerProof = proof.copy(offerId = "different-fare")).isVerifiedFor(itinerary, at))
        assertFalse(evidence.copy(sellerProof = proof.copy(sellerId = "unrelated-agent")).isVerifiedFor(itinerary, at))
        assertFalse(evidence.isVerifiedFor(itinerary, Instant.parse("2026-11-17T00:00:00Z")))

        val saved = AppJson.encodeToString(RefundEvidence.serializer(), evidence)
        val restored = AppJson.decodeFromString(RefundEvidence.serializer(), saved)
        assertTrue(restored.isVerifiedFor(itinerary, at))
        assertFalse(saved.contains("booking_token"))
    }
}
