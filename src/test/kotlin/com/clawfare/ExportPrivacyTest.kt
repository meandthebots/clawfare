package com.clawfare

import com.clawfare.cli.ClawfareCommand
import com.clawfare.search.AppJson
import com.clawfare.search.BookLink
import com.clawfare.search.Export
import com.clawfare.search.ExportPrivacy
import com.clawfare.search.ExportPrivacyProjection
import com.clawfare.search.FilterSpec
import com.clawfare.search.Itinerary
import com.clawfare.search.NoteCard
import com.clawfare.search.RefundEvidence
import com.clawfare.search.ResultSet
import com.clawfare.search.SellerOption
import com.clawfare.search.VendorRow
import org.junit.jupiter.api.Test
import picocli.CommandLine
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** All identifiers and fares here are artificial, and no provider is contacted. */
class ExportPrivacyTest {
    private val token = "SYNTHETIC_PRIVATE_BOOKING_TOKEN_XYZ"
    private val url = "https://example.invalid/book?booking_token=$token"

    private fun sample(): ResultSet {
        val itinerary =
            Itinerary(
                id = "fake-itinerary",
                origin = "LHR",
                destination = "HND",
                outboundDate = "2030-01-01",
                pricePerPerson = 42.0,
                links = listOf(BookLink("exact", "Provider booking link", url)),
                farePolicyKey = token,
                notes = "Private note $token",
                refundEvidence =
                    RefundEvidence(
                        offerId = token,
                        sellerId = token,
                        sourceDescription = token,
                        baggageEvidence = listOf(token),
                    ),
                cashRefundProofVerified = true,
            )
        val vendor =
            VendorRow(
                vendor = "Test seller",
                market = "uk",
                marketLabel = "United Kingdom",
                currency = "GBP",
                note = "Private seller note $token",
                links = listOf(BookLink("rt", "Round trip", url)),
            )
        val seller =
            SellerOption(
                id = token,
                productKey = token,
                itineraryId = itinerary.id,
                provider = "fake",
                market = "uk",
                seller = "Test seller",
                currency = "GBP",
                passengers = 1,
                journey = "round_trip",
                bags = "",
                observedAt = "2030-01-01T00:00:00Z",
                bookingActionAvailable = true,
            )
        return ResultSet(
            id = "fake-private-trip",
            title = "Private trip",
            subtitle = token,
            itineraries = listOf(itinerary),
            addons = listOf(itinerary.copy(id = "fake-addon")),
            vendors = listOf(vendor),
            sellerOptions = listOf(seller),
            headerNotes = listOf(NoteCard("private", token)),
            vendorIntro = listOf(NoteCard("private", token)),
            warnings = listOf(token),
        )
    }

    @Test
    fun portableHtmlUsesTypedProjectionAndCannotLeakKnownOpaqueFields() {
        val source = sample()
        val page = "<html><script>window.__SNAPSHOT__ = null;</script></html>"
        val html = Export.html(page, source)
        assertFalse(html.contains(token))
        assertFalse(html.contains("booking_token"))
        assertTrue(html.contains("fake-private-trip"))
        val embeddedJson = html.substringAfter("window.__SNAPSHOT__ = ").substringBefore(";</script>")
        val restored = AppJson.decodeFromString(ResultSet.serializer(), embeddedJson)
        val safeItinerary = restored.itineraries.first()
        val safeAddon = restored.addons.first()
        val safeVendor = restored.vendors.first()
        val safeSeller = restored.sellerOptions.first()
        assertEquals(null, restored.query)
        assertTrue(safeItinerary.links.isEmpty())
        assertTrue(safeAddon.links.isEmpty())
        assertEquals(null, safeItinerary.farePolicyKey)
        assertEquals(null, safeItinerary.refundEvidence?.offerId)
        assertEquals(null, safeItinerary.refundEvidence?.sellerProof)
        assertFalse(safeItinerary.cashRefundProofVerified)
        assertEquals("", safeItinerary.notes)
        assertTrue(safeVendor.links.isEmpty())
        assertEquals("", safeVendor.note)
        assertEquals("", safeSeller.productKey)
        assertFalse(safeSeller.bookingActionAvailable)
        assertTrue(restored.headerNotes.isEmpty())
        assertEquals(1, restored.warnings.size)
        // The stored result is immutable and still usable by private services.
        val originalItinerary = source.itineraries.first()
        val originalLink = originalItinerary.links.first()
        assertEquals(url, originalLink.url)
        assertEquals(token, originalItinerary.refundEvidence?.offerId)
    }

    @Test
    fun portableCsvRetainsNumericPriceAndOmitsBookingLinksAndPrivateNotes() {
        val source = sample()
        val itineraryCsv = Export.itineraryCsv(source, FilterSpec())
        assertTrue(itineraryCsv.contains("42.0"))
        assertFalse(itineraryCsv.contains(token))
        assertFalse(itineraryCsv.contains("booking_token"))
        val vendorCsv = Export.vendorCsv(source)
        assertTrue(vendorCsv.contains("Test seller"))
        assertFalse(vendorCsv.contains(token))
        assertFalse(vendorCsv.contains("booking_token"))
        val sellerCsv = Export.sellerOptionsCsv(source)
        assertTrue(sellerCsv.contains("Test seller"))
        assertFalse(sellerCsv.contains(token))
        assertTrue(sellerCsv.contains("export-seller-1"))
    }

    @Test
    fun diagnosticOptInPreservesDataAndDoesNotChangeOriginal() {
        val source = sample()
        val preserved = ExportPrivacyProjection.project(source, ExportPrivacy.PRIVATE_DIAGNOSTIC)
        assertEquals(source, preserved)
        val html = Export.html("<script>window.__SNAPSHOT__ = null;</script>", source, ExportPrivacy.PRIVATE_DIAGNOSTIC)
        assertTrue(html.contains(token))
        assertTrue(Export.itineraryCsv(source, FilterSpec(), ExportPrivacy.PRIVATE_DIAGNOSTIC).contains(token))
        assertTrue(Export.vendorCsv(source, ExportPrivacy.PRIVATE_DIAGNOSTIC).contains(token))
        assertTrue(Export.sellerOptionsCsv(source, ExportPrivacy.PRIVATE_DIAGNOSTIC).contains(token))
    }

    @Test
    fun rawReimportableJsonNeedsExplicitCliOptIn() {
        // This command must be rejected before touching a database.
        assertEquals(2, CommandLine(ClawfareCommand()).execute("report", "json", "fake-private-trip"))
    }
}
