package com.clawfare.search

import kotlinx.serialization.Serializable
import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * A deliberately manual, seller-specific pre-purchase attestation.
 *
 * Airline-level Duffel `conditions.refund_before_departure` does not specify
 * whether the *traveller* gets a card refund after an agent receives funds.
 * An order-cancellation quote is post-purchase and is NOT interchangeable
 * with these pre-purchase terms. This model never invokes booking endpoints.
 */
@Serializable
data class SellerRefundProof(
    val sellerId: String,
    val offerId: String,
    /** Hash of itinerary, fare brand, cabin, party, dates and segment times. */
    val itinerarySha256: String,
    val partySize: Int,
    val fareBrand: String,
    val termsSourceUrl: String,
    /** SHA-256 of captured exact seller terms on private local storage. */
    val termsSha256: String,
    val reviewedAt: String,
    val expiresAt: String,
    /** Normalised UTC deadline for a fee-free voluntary whole-ticket cancellation. */
    val cancellationDeadlineAt: String,
    val coversFullTicketBeforeOutbound: Boolean,
    val refundToTravellerOriginalPayment: Boolean,
    val issuerPenaltyAmount: Double?,
    val sellerServicingFeeAmount: Double?,
    val reviewerEvidence: String = "manual_review",
) {
    fun isValidFor(
        itinerary: Itinerary,
        evidence: RefundEvidence,
        now: Instant = Instant.now(),
    ): Boolean {
        if (sellerId.isBlank() || sellerId != evidence.sellerId) return false
        if (offerId.isBlank() || offerId != evidence.offerId) return false
        if (itinerarySha256 != exactRefundItineraryKey(itinerary)) return false
        if (partySize != itinerary.passengers || partySize <= 0) return false
        if (fareBrand != itinerary.fareBrand.orEmpty()) return false
        if (!coversFullTicketBeforeOutbound || !refundToTravellerOriginalPayment) return false
        if (reviewerEvidence != "manual_review" || termsSha256.length != 64 || !termsSha256.all { it in "0123456789abcdef" }) {
            return false
        }
        val source = runCatching { URI(termsSourceUrl) }.getOrNull() ?: return false
        val publicTermsSource =
            source.scheme == "https" &&
                !source.host.isNullOrBlank() &&
                source.userInfo == null &&
                source.rawQuery == null &&
                source.rawFragment == null
        // Exact source links with auth/booking tokens must not be stored.
        if (!publicTermsSource) return false
        val reviewed = reviewedAt.asInstant() ?: return false
        val expiry = expiresAt.asInstant() ?: return false
        val cancellationDeadline = cancellationDeadlineAt.asInstant() ?: return false
        val age = Duration.between(reviewed, now)
        if (age.isNegative || age > Duration.ofHours(48)) return false
        if (!expiry.isAfter(now) || !cancellationDeadline.isAfter(now)) return false
        return issuerPenaltyAmount == 0.0 &&
            sellerServicingFeeAmount == 0.0 &&
            evidence.allowed == true &&
            evidence.penaltyAmount == 0.0 &&
            evidence.servicingFeeAmount == 0.0 &&
            evidence.method == "original_payment"
    }
}

private fun String.asInstant(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

/**
 * Changes to the fare family, operator, flight dates or party invalidate proof.
 * Deliberately excludes price: a price refresh needs fresh offer/expiry evidence
 * and the exact offer ID to stay identical before an attestation can survive.
 */
fun exactRefundItineraryKey(itinerary: Itinerary): String {
    val legs = itinerary.outbound.legs + itinerary.returning?.legs.orEmpty()
    val key =
        listOf(
            itinerary.source,
            itinerary.fareBrand.orEmpty(),
            itinerary.farePolicyKey.orEmpty(),
            itinerary.cabinKey,
            itinerary.passengers.toString(),
            itinerary.outboundDate,
            itinerary.returnDate.orEmpty(),
            legs.joinToString("|") { leg ->
                listOf(
                    leg.flightNumber,
                    leg.airlineCode,
                    leg.operatingCarrier,
                    leg.from,
                    leg.to,
                    leg.departDate,
                    leg.departTime,
                    leg.arriveDate,
                    leg.arriveTime,
                    leg.cabin.orEmpty(),
                ).joinToString("/")
            },
        ).joinToString("|")
    return sha256(key)
}

fun RefundEvidence.isVerifiedFor(
    itinerary: Itinerary,
    now: Instant = Instant.now(),
): Boolean =
    itinerary.sourceLabel == "live" &&
        isVerifiedFullCashRefund(itinerary.livePrice, itinerary.passengers, now) &&
        sellerProof?.isValidFor(itinerary, this, now) == true
