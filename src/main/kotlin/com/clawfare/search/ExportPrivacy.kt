package com.clawfare.search

/**
 * An explicit export policy, not a declaration that flight dates or prices are anonymous.
 *
 * SHARE_SAFE removes opaque booking links, private seller evidence and free-form
 * text from portable reports. Private diagnostic exports retain everything for
 * local investigation; they must never be uploaded to public issue trackers.
 */
enum class ExportPrivacy {
    SHARE_SAFE,
    PRIVATE_DIAGNOSTIC,
}

/**
 * Project a ResultSet rather than applying regexes to serialized JSON/CSV.
 * Parsing/serialization remains type checked, and no mutable stored data is
 * modified. The original itinerary data stays in the local DB.
 */
object ExportPrivacyProjection {
    fun project(
        result: ResultSet,
        privacy: ExportPrivacy,
    ): ResultSet {
        if (privacy == ExportPrivacy.PRIVATE_DIAGNOSTIC) return result

        fun sanitize(itinerary: Itinerary): Itinerary =
            itinerary.copy(
                links = emptyList(),
                farePolicyKey = null,
                notes = "",
                cashRefundProofVerified = false,
                refundEvidence =
                    itinerary.refundEvidence?.copy(
                        offerId = null,
                        sellerId = null,
                        sellerProof = null,
                        sourceDescription = "",
                        baggageEvidence = emptyList(),
                    ),
            )

        return result.copy(
            query = null,
            subtitle = "",
            itineraries = result.itineraries.map(::sanitize),
            addons = result.addons.map(::sanitize),
            vendors = result.vendors.map { it.copy(links = emptyList(), note = "") },
            // The displayed seller prices and product categories remain useful,
            // but internal offer keys/booking affordances should not travel.
            sellerOptions =
                result.sellerOptions.mapIndexed { index, option ->
                    option.copy(
                        id = "export-seller-${index + 1}",
                        productKey = "",
                        bookingActionAvailable = false,
                    )
                },
            headerNotes = emptyList(),
            vendorIntro = emptyList(),
            warnings =
                listOf(
                    "Portable export: provider links, booking identifiers, private notes " +
                        "and exact refund proof were omitted. Refundability is not verified.",
                ),
        )
    }
}
