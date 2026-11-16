package com.clawfare.search

import kotlinx.serialization.Serializable
import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * A manual quote about *partially used* tickets. It is separate from
 * refund_before_departure, which describes cancelling the whole unused ticket.
 * Its numbers are whole-party GBP amounts, never per-person or original
 * provider currency. No action on any ticket is performed by this model.
 */
@Serializable
data class PartialUseRefundProof(
    val offerId: String,
    val sellerId: String,
    val itinerarySha256: String,
    val fareBrand: String,
    val partySize: Int,
    val sourceUrl: String,
    val termsSha256: String,
    val reviewedAt: String,
    val expiresAt: String,
    /** Airline/seller has confirmed that the inbound may be cancelled after outbound travel. */
    val cancelRemainingCouponAllowed: Boolean,
    /** Explicit actual gross refund on the unused coupon, prior to assessed deductions. */
    val unusedCouponRefundGbp: Double?,
    /** Difference in fare required for the outbound already flown. */
    val postDepartureRepricingGbp: Double?,
    val airlineCancellationFeeGbp: Double?,
    val sellerServicingFeeGbp: Double?,
    /** Independently confirmed net GBP payable back to the traveller. */
    val travellerCashRefundGbp: Double?,
    val refundToOriginalPayment: Boolean,
    val reviewedManually: Boolean = false,
) {
    /** Reject mismatched, stale and incomplete seller-specific evidence. */
    fun verifiedRefundGbp(
        itinerary: Itinerary,
        seller: String,
        now: Instant = Instant.now(),
    ): Double? {
        if (itinerary.sourceLabel != "live" || !itinerary.livePrice) return null
        if (!cancelRemainingCouponAllowed || !refundToOriginalPayment || !reviewedManually) return null
        if (offerId.isBlank() || offerId != itinerary.refundEvidence?.offerId) return null
        if (sellerId.isBlank() || sellerId != seller || sellerId != itinerary.refundEvidence?.sellerId) return null
        if (itinerarySha256 != exactRefundItineraryKey(itinerary)) return null
        if (partySize != itinerary.passengers || partySize <= 0 || fareBrand != itinerary.fareBrand.orEmpty()) return null
        if (termsSha256.length != 64 || !termsSha256.all { it in "0123456789abcdef" }) return null
        val link = runCatching { URI(sourceUrl) }.getOrNull() ?: return null
        if (link.scheme != "https" || link.host.isNullOrBlank() || link.userInfo != null) return null
        if (link.rawQuery != null || link.rawFragment != null) return null
        val reviewed = runCatching { Instant.parse(reviewedAt) }.getOrNull() ?: return null
        val expiry = runCatching { Instant.parse(expiresAt) }.getOrNull() ?: return null
        val age = Duration.between(reviewed, now)
        if (age.isNegative || age > Duration.ofHours(48) || !expiry.isAfter(now)) return null
        val amounts =
            listOf(
                unusedCouponRefundGbp,
                postDepartureRepricingGbp,
                airlineCancellationFeeGbp,
                sellerServicingFeeGbp,
                travellerCashRefundGbp,
            )
        if (amounts.any { it == null || !it.isFinite() || it < 0.0 }) return null
        val gross = requireNotNull(unusedCouponRefundGbp)
        val deductions =
            requireNotNull(postDepartureRepricingGbp) +
                requireNotNull(airlineCancellationFeeGbp) +
                requireNotNull(sellerServicingFeeGbp)
        val actual = requireNotNull(travellerCashRefundGbp)
        // No guessed half-ticket refund, no negative net refund, and no
        // discrepancy between itemised deductions and the seller's final quote.
        if (gross < deductions || kotlin.math.abs(gross - deductions - actual) > 0.01) return null
        val paid = itinerary.pricePerPerson?.times(itinerary.passengers) ?: return null
        if (!paid.isFinite() || actual > paid || gross > paid) return null
        return actual
    }
}

@Serializable
data class TicketUsePlan(
    /** one_way, genuine_return, cancel_inbound, no_show_inbound, cancel_whole_before_outbound. */
    val mode: String,
    val sellerId: String = "",
    val partialRefundProof: PartialUseRefundProof? = null,
    /** Published fare maximum/minimum stay, if individually verified. */
    val minStayDays: Int? = null,
    val maxStayDays: Int? = null,
    /** Exact fare-specific source, not general airline marketing language. */
    val stayRuleSourceUrl: String? = null,
)

data class TicketUseOutcome(
    val mode: String,
    /** Whole-party published purchase price before any coupon is flown. */
    val grossTicketGbp: Double?,
    /** Null if voluntary fees/refund value cannot be established. */
    val netTicketGbp: Double?,
    val travellerCashRefundGbp: Double?,
    val state: String,
    val note: String,
)

/**
 * Read-only scenario comparison. Never books, cancels, changes or advises
 * no-show. Unknown post-departure repricing/refunds are NOT treated as zero.
 */
object TicketUse {
    fun evaluate(
        itinerary: Itinerary,
        plan: TicketUsePlan,
        now: Instant = Instant.now(),
    ): TicketUseOutcome {
        val gross =
            itinerary.pricePerPerson
                ?.takeIf { it.isFinite() && it >= 0.0 && itinerary.passengers > 0 }
                ?.times(itinerary.passengers)
        fun unknown(reason: String): TicketUseOutcome = TicketUseOutcome(plan.mode, gross, null, null, "not_computable", reason)

        fun known(
            net: Double,
            cash: Double = 0.0,
        ): TicketUseOutcome {
            return TicketUseOutcome(
                plan.mode,
                gross,
                net,
                cash,
                "computable",
                "Manual fare-rule evidence; recheck with ticket issuer.",
            )
        }

        if (gross == null || !gross.isFinite()) return unknown("Price per party or amount scope is not established.")
        val isReturn =
            itinerary.returning != null &&
                !itinerary.returnMissing &&
                itinerary.returnDate != null &&
                itinerary.nights?.let { it > 0 } == true
        val isOneWay = itinerary.returning == null && itinerary.returnDate == null
        when (plan.mode) {
            "one_way" -> {
                if (!isOneWay) return unknown("One-way strategy requires a genuine one-way offer.")
                return known(gross)
            }
            "genuine_return", "cancel_inbound", "no_show_inbound" -> {
                if (!isReturn) return unknown("A complete outbound and inbound on one return ticket is required.")
                val duration = requireNotNull(itinerary.nights)
                val requiresRules = plan.minStayDays != null || plan.maxStayDays != null
                if (requiresRules) {
                    val source = runCatching { URI(plan.stayRuleSourceUrl) }.getOrNull()
                    if (source?.scheme != "https" || source.host.isNullOrBlank() || source.userInfo != null) {
                        return unknown("Minimum/maximum stay needs source-proven fare-specific evidence.")
                    }
                    if ((plan.minStayDays ?: 0) > duration || (plan.maxStayDays ?: Int.MAX_VALUE) < duration) {
                        return unknown("Return date violates the sourced ticket's min/max stay restriction.")
                    }
                }
                if (plan.mode == "genuine_return") return known(gross)
                if (plan.mode == "no_show_inbound") {
                    return unknown(
                        "Intentionally missing the inbound is not a verified saving. " +
                            "Remaining coupons may be invalidated, the fare may be repriced and fees may apply.",
                    )
                }
                val cash =
                    plan.partialRefundProof?.verifiedRefundGbp(itinerary, plan.sellerId, now)
                        ?: return unknown(
                        "Post-outbound cancellation must have exact seller and fare-rule evidence " +
                            "for actual cash refund, coupon repricing and all fees. Gross price is shown only.",
                    )
                return known(gross - cash, cash)
            }
            "cancel_whole_before_outbound" -> {
                if (itinerary.refundEvidence?.isVerifiedFor(itinerary, now) != true) {
                    return unknown("Full pre-departure cash refund requires separate original-payment proof.")
                }
                return known(0.0, gross)
            }
            else -> return unknown("Unknown ticket-use mode.")
        }
    }
}
