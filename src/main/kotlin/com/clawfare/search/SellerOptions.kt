package com.clawfare.search

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A retrieved Google Flights *seller option*, not an airline ticket or booking.
 * Booking POST data, credentials and opaque provider tokens must never appear
 * in this view model or its database/export serialization.
 */
@Serializable
data class SellerOption(
    val id: String,
    val productKey: String,
    val itineraryId: String,
    val provider: String,
    val market: String,
    val seller: String,
    val fareBrand: String? = null,
    val fareType: String? = null,
    /** Whole-party quote, in the requested provider market currency. */
    val priceTotal: Double? = null,
    val currency: String,
    val passengers: Int,
    val journey: String,
    val directAirline: Boolean? = null,
    val splitTickets: Boolean = false,
    val bags: String = "",
    val phoneServiceFee: Double? = null,
    val observedAt: String,
    /** Availability of a booking action is not proof of price or refundability. */
    val bookingActionAvailable: Boolean = false,
    val refundStatus: String = "unverified",
)

/** Both SerpApi's nested `together` and SearchAPI's flat option schema. */
fun parseGoogleSellerOptions(
    body: String,
    provider: String,
    market: String,
    currency: String,
    itineraryId: String,
    passengers: Int,
    journey: String,
    observedAt: String,
): List<SellerOption> {
    val root = runCatching { AppJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
    val raw = root["booking_options"] as? JsonArray ?: return emptyList()
    return raw.mapIndexedNotNull { index, element ->
        val wrapper = element as? JsonObject ?: return@mapIndexedNotNull null
        val option = wrapper["together"] as? JsonObject ?: wrapper
        val seller = option.sellerText("book_with") ?: wrapper.sellerText("book_with") ?: return@mapIndexedNotNull null
        val title = option.sellerText("option_title") ?: wrapper.sellerText("option_title")
        val fareType = option.sellerText("fare_type") ?: wrapper.sellerText("fare_type")
        val bags =
            (option["baggage_prices"] as? JsonArray)
                ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                ?.joinToString("; ")
                .orEmpty()
        val split =
            wrapper.sellerBool("separate_tickets") == true ||
                wrapper.sellerBool("is_split_booking") == true
        val price = option.sellerDouble("price")?.takeIf { it.isFinite() && it >= 0.0 }
        val phoneFee = option.sellerDouble("estimated_phone_service_fee")?.takeIf { it.isFinite() && it >= 0.0 }
        val semantic =
            listOf(
                provider,
                itineraryId,
                market,
                journey,
                seller,
                title.orEmpty(),
                fareType.orEmpty(),
                split.toString(),
                bags,
                phoneFee?.toString().orEmpty(),
            ).joinToString("|")
        // The observation ID includes the returned option index to distinguish
        // same-branded but otherwise unresolved seller offers. The stable
        // product key deliberately excludes price and provider option index.
        SellerOption(
            id = "seller-" + sha256("$semantic|$index").take(24),
            productKey = "seller-product-" + sha256(semantic).take(24),
            itineraryId = itineraryId,
            provider = provider,
            market = market,
            seller = seller,
            fareBrand = title,
            fareType = fareType,
            priceTotal = price,
            currency = currency,
            passengers = passengers,
            journey = journey,
            directAirline = option.sellerBool("airline") ?: wrapper.sellerBool("airline"),
            splitTickets = split,
            bags = bags,
            phoneServiceFee = phoneFee,
            observedAt = observedAt,
            bookingActionAvailable = option["booking_request"] is JsonObject || option.sellerText("booking_phone") != null,
        )
    }
}

private fun JsonObject.sellerText(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull }
        .getOrNull()
        ?.takeIf { it.isNotBlank() && it != "null" }

private fun JsonObject.sellerBool(key: String): Boolean? =
    runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

private fun JsonObject.sellerDouble(key: String): Double? =
    runCatching { this[key]?.jsonPrimitive?.doubleOrNull }.getOrNull()
