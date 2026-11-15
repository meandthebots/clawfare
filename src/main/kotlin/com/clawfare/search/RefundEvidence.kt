package com.clawfare.search

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * Evidence from a particular airline offer, not a promise of bookability.
 * Duffel's refund_before_departure describes airline cancellation rights, but
 * not necessarily original-payment refunds or any additional servicing fees.
 */
@Serializable
data class RefundEvidence(
    val provider: String = "",
    val offerId: String? = null,
    val observedAt: String? = null,
    val expiresAt: String? = null,
    val allowed: Boolean? = null,
    val penaltyAmount: Double? = null,
    val penaltyCurrency: String? = null,
    /** original_payment, travel_credit, or unknown; Duffel alone normally returns unknown. */
    val method: String = "unknown",
    /** Extra supplier/agent fees; null means not verified. */
    val servicingFeeAmount: Double? = null,
    val servicingFeeCurrency: String? = null,
    val availablePassengers: Int? = null,
    val baggageEvidence: List<String> = emptyList(),
    val sourceDescription: String = "",
) {
    val refundStatus: String
        get() =
            when {
                allowed == false -> "nonrefundable"
                allowed == true && penaltyAmount == 0.0 -> "no_airline_penalty"
                allowed == true -> "conditional_or_penalty_unknown"
                else -> "unverified"
            }

    fun isExpired(now: Instant = Instant.now()): Boolean =
        expiresAt?.let { runCatching { !Instant.parse(it).isAfter(now) }.getOrDefault(true) } ?: true

    /** Requires affirmative proof, including refund method and all fees. */
    fun isVerifiedFullCashRefund(
        liveOffer: Boolean,
        adults: Int,
        now: Instant = Instant.now(),
    ): Boolean =
        liveOffer &&
            !isExpired(now) &&
            allowed == true &&
            penaltyAmount == 0.0 &&
            method == "original_payment" &&
            servicingFeeAmount == 0.0 &&
            (availablePassengers == null || availablePassengers >= adults)
}

fun parseDuffelRefundEvidence(
    offer: JsonObject,
    observedAt: String,
): RefundEvidence {
    val conditions = offer["conditions"] as? JsonObject
    val refund = conditions?.get("refund_before_departure") as? JsonObject
    val allowed = refund?.get("allowed")?.jsonPrimitive?.booleanOrNull
    val penalty = refund?.get("penalty_amount")?.jsonPrimitive?.doubleOrNull
    return RefundEvidence(
        provider = "duffel",
        offerId = offer["id"]?.jsonPrimitive?.contentOrNull,
        observedAt = observedAt,
        expiresAt = offer["expires_at"]?.jsonPrimitive?.contentOrNull,
        allowed = allowed,
        penaltyAmount = penalty,
        penaltyCurrency = refund?.get("penalty_currency")?.jsonPrimitive?.contentOrNull,
        // The refund API does not prove the form of repayment or ancillary fees.
        method = "unknown",
        servicingFeeAmount = null,
        availablePassengers = offer["available_passenger_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        baggageEvidence = parseDuffelBaggage(offer),
        sourceDescription = "Duffel offer.conditions.refund_before_departure",
    )
}

private fun parseDuffelBaggage(offer: JsonObject): List<String> {
    val slices = offer["slices"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return slices.flatMap { slice ->
        val segments =
            (slice as? JsonObject)
                ?.get("segments") as? kotlinx.serialization.json.JsonArray ?: return@flatMap emptyList()
        segments.flatMap { segment ->
            val passengers =
                (segment as? JsonObject)?.get("passengers") as? kotlinx.serialization.json.JsonArray
                    ?: return@flatMap emptyList()
            passengers.flatMap { passenger ->
                val bags =
                    (passenger as? JsonObject)?.get("baggages") as? kotlinx.serialization.json.JsonArray
                        ?: return@flatMap emptyList()
                bags.mapNotNull { bag ->
                    val value = bag as? JsonObject ?: return@mapNotNull null
                    val type = value["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val quantity = value["quantity"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                    "$quantity $type"
                }
            }
        }
    }.distinct()
}
