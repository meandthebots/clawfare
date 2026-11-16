package com.clawfare

import com.clawfare.db.ClawfareDatabase
import com.clawfare.search.FareStore
import com.clawfare.search.Fx
import com.clawfare.search.FxInfo
import com.clawfare.search.MarketQuotes
import com.clawfare.search.SampleData
import com.clawfare.search.SellerQuote
import com.clawfare.search.StoredResult
import com.clawfare.search.VendorMath
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FxSafetyTest {
    @Test
    fun missingRateNeverPassesThroughTheForeignNumericAmountAsGbp() {
        assertNull(Fx.toGbpOrNull(4000.0, "XYZ"))
        assertNull(Fx.toGbpOrNull(Double.NaN, "GBP"))
        assertNull(Fx.toGbpOrNull(Double.POSITIVE_INFINITY, "GBP"))
        assertNull(Fx.toGbpOrNull(-5.0, "GBP"))
        assertEquals(4000.0, Fx.toGbpOrNull(4000.0, "GBP"))
        assertEquals(null to "", VendorMath.allIn("xyz", 4000.0, null))
        assertEquals(null to "", VendorMath.allIn("xyz", null, 4200.0))
        assertEquals(null to "", VendorMath.allIn("xyz", Double.NaN, null))
    }

    @Test
    fun distinctPartyPricesStayUnrankedWithoutReferenceFx() {
        val variants =
            mapOf(
                "xyz" to
                    MarketQuotes(
                        roundTrip =
                            mapOf("A" to SellerQuote("Unknown Currency Seller", 4000.0, "1st checked bag: free")),
                        outbound = emptyMap(),
                        inbound = emptyMap(),
                    ),
            )
        val rows = VendorMath.rows(variants, checkedBags = 1, passengers = 2)
        assertNotNull(rows.single().roundTripWithBag)
        assertNull(rows.single().allInGbp)
        assertTrue(rows.single().currency == "XYZ")
        assertFalse(rows.single().how.isNotBlank())
    }

    @Test
    fun frozenSnapshotSurvivesInvestigationReopenWithoutRevaluation() {
        ClawfareDatabase.connectInMemory()
        val historical =
            FxInfo(
                perGbp = mapOf("GBP" to 1.0, "EUR" to 1.2500, "USD" to 1.3000),
                asOf = "Fixture snapshot: 2026-11-15",
            )
        val sample =
            SampleData.florida().copy(
                id = "fx-frozen-snapshot-test",
                fx = historical,
                vendors = emptyList(),
                vendorItineraryId = null,
            )
        FareStore.save(StoredResult(sample))
        val reloaded = assertNotNull(FareStore.project("fx-frozen-snapshot-test"))
        assertEquals(historical, reloaded.fx)
        assertEquals(1.25, reloaded.fx.perGbp["EUR"])
        assertEquals("Fixture snapshot: 2026-11-15", reloaded.fx.asOf)
        // The reference is display metadata: normalized stored GBP fares
        // must not be recalculated from a newly loaded mutable rate.
        assertEquals(sample.itineraries.first().pricePerPerson, reloaded.itineraries.first().pricePerPerson)
    }
}
