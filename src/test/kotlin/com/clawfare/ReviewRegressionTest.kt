package com.clawfare

import com.clawfare.cli.ClawfareCommand
import com.clawfare.db.ClawfareDatabase
import com.clawfare.db.FlightQueries
import com.clawfare.db.PriceHistoryQueries
import com.clawfare.export.JsonExporter
import com.clawfare.importer.JsonImporter
import com.clawfare.model.FlightValidator
import com.clawfare.search.DiskCache
import com.clawfare.search.FareProvider
import com.clawfare.search.FareStore
import com.clawfare.search.GoogleFlightsProvider
import com.clawfare.search.GoogleFlightsStyle
import com.clawfare.search.HttpFetcher
import com.clawfare.search.HttpResult
import com.clawfare.search.Itinerary
import com.clawfare.search.NoopSink
import com.clawfare.search.ProviderOutcome
import com.clawfare.search.QuotaView
import com.clawfare.search.ResponseCache
import com.clawfare.search.SampleData
import com.clawfare.search.SearchQuery
import com.clawfare.search.SearchService
import com.clawfare.search.SqliteResponseCache
import com.clawfare.search.StoredResult
import com.clawfare.search.VendorMath
import com.clawfare.web.Server
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.io.File
import java.sql.DriverManager

class ReviewRegressionTest {
    @Test
    fun localServerIsLoopbackOnly() {
        assertEquals("127.0.0.1", Server.HOST)
    }

    @Test
    fun twoAdultSellerSearchesAndBaggageUsePartyCount() {
        val urls = mutableListOf<String>()
        val http =
            object : HttpFetcher {
                override fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    body: String?,
                ): HttpResult {
                    urls += url
                    return if ("booking_token=" in url) {
                        HttpResult(
                            200,
                            """{"booking_options":[{"together":{"book_with":"British Airways","price":4200,"option_title":"Business Flex"}}]}""",
                        )
                    } else {
                        HttpResult(
                            200,
                            """{"best_flights":[{"price":4200,"booking_token":"book-123","flights":[{"departure_airport":{"id":"LHR","time":"2027-03-23 10:00"},"arrival_airport":{"id":"HND","time":"2027-03-24 08:00"},"duration":780,"airline":"British Airways","flight_number":"BA 5","travel_class":"Business"}]}]}""",
                        )
                    }
                }
            }
        val provider = GoogleFlightsProvider("serpapi", "SerpApi", "test-key", GoogleFlightsStyle.SERPAPI, http)
        val selected = SampleData.florida().itineraries.first { it.returning != null }
        val itinerary = selected.copy(passengers = 2)
        val cacheDir = File.createTempFile("clawfare-review-cache", "")
        cacheDir.delete()
        cacheDir.mkdirs()
        val sellers = provider.vendors(itinerary, null, listOf("uk"), 1, DiskCache(cacheDir), allowNetwork = true)
        assertTrue(urls.isNotEmpty())
        assertTrue(urls.all { "adults=2" in it }, urls.joinToString(","))
        assertTrue(sellers.rows.any { it.roundTrip == 4200.0 })
        assertEquals(VendorMath.bagFee("us", "1st checked bag: 40", 2, 1, 1) * 2, VendorMath.bagFee("us", "1st checked bag: 40", 2, 1, 2))
    }

    private fun products(): List<Itinerary> {
        val base =
            SampleData.florida().itineraries.first { it.returning != null }.copy(
                passengers = 2,
                priceOriginal = null,
                priceCurrency = "GBP",
                prevPrice = null,
                fetchedAt = "2026-11-15T12:00:00Z",
                sourceLabel = "live",
            )
        return listOf(
            base.copy(id = "serp-basic", source = "serpapi", fareBrand = "Basic", farePolicyKey = "no-refund", pricePerPerson = 2100.0),
            base.copy(id = "serp-flex", source = "serpapi", fareBrand = "Flex", farePolicyKey = "free-refund", pricePerPerson = 3200.0),
            base.copy(id = "search-flex", source = "searchapi", fareBrand = "Flex", farePolicyKey = "free-refund", pricePerPerson = 3150.0),
        )
    }

    @Test
    fun dedupeKeepsDifferentFaresAndSources() {
        val offers = products()
        val provider =
            object : FareProvider {
                override val id = "serpapi"
                override val displayName = "Scripted"
                override val configured = true
                override val priceKind = "live"

                override fun quota(): QuotaView = QuotaView(id, true)

                override fun baseCalls(query: SearchQuery) = emptyList<com.clawfare.search.CallSpec>()

                override fun search(
                    query: SearchQuery,
                    cache: ResponseCache,
                    allowNetwork: Boolean,
                ): ProviderOutcome = ProviderOutcome(itineraries = offers + offers[0].copy(pricePerPerson = 2250.0))
            }
        val cacheDir = File.createTempFile("clawfare-dedupe", "")
        cacheDir.delete()
        cacheDir.mkdirs()
        val query =
            SearchQuery(
                origins = listOf("LHR"),
                destinations = listOf("HND"),
                outboundFrom = "2027-03-23",
                tripType = "one_way",
                adults = 2,
                cabin = "business",
                providers = listOf("serpapi"),
                cacheOnly = true,
                confirmed = true,
            )
        val response = SearchService(listOf(provider), DiskCache(cacheDir), NoopSink).search(query)
        assertTrue(response.ok)
        assertEquals(3, response.result!!.itineraries.size)
        val basic = response.result!!.itineraries.first { it.id == "serp-basic" }
        assertEquals(2100.0, basic.pricePerPerson)
    }

    @Test
    fun distinctFareProductsSurviveSqliteRoundTripAndRecheck() {
        ClawfareDatabase.connectInMemory()
        val offers = products()
        val shell =
            SampleData.florida().copy(
                id = "review-offers",
                title = "Offer variants",
                itineraries = offers,
                addons = emptyList(),
                vendors = emptyList(),
                vendorItineraryId = null,
                scenarioLabel = null,
                query = null,
            )
        val saved = FareStore.save(StoredResult(shell))
        assertEquals(3, saved.itineraries.size)
        val flights = FlightQueries.listByInvestigation(shell.id)
        assertEquals(3, flights.size)
        assertEquals(3, flights.map { it.id }.toSet().size)
        flights.forEach {
            val entry = JsonExporter.flightWithPriceToEntry(FlightQueries.getWithPrice(it.id)!!)
            assertTrue(FlightValidator.validate(entry).isValid, FlightValidator.validate(entry).errors.joinToString())
            assertNotNull(entry.offerIdentity)
            val imported = JsonImporter.entryToDto(entry, "copy-of-review")
            assertEquals(entry.offerIdentity, imported.offerIdentity)
            assertEquals(entry.fareBrand, imported.fareBrand)
            assertEquals(entry.farePolicyKey, imported.farePolicyKey)
        }
        val flex = flights.first { it.source == "serpapi" && it.fareBrand == "Flex" }
        val refreshed =
            offers.map { offer ->
                if (offer.id == "serp-flex") {
                    offer.copy(
                        pricePerPerson = 3000.0,
                        fetchedAt = "2026-11-16T12:00:00Z",
                    )
                } else {
                    offer
                }
            }
        FareStore.save(StoredResult(shell.copy(itineraries = refreshed)))
        assertEquals(2, PriceHistoryQueries.getByFlightId(flex.id).size)
        val reloaded = FareStore.project(shell.id)!!
        assertEquals(3, reloaded.itineraries.size)
        val restoredFlex = reloaded.itineraries.first { it.id == "serp-flex" }
        assertEquals("free-refund", restoredFlex.farePolicyKey)
    }

    @Test
    fun cacheOnlyReingestPreservesPriceAndBookingToken() {
        ClawfareDatabase.connectInMemory()
        var networkCalls = 0
        val http =
            object : HttpFetcher {
                override fun request(
                    method: String,
                    url: String,
                    headers: Map<String, String>,
                    body: String?,
                ): HttpResult {
                    networkCalls++
                    return HttpResult(
                        200,
                        """{"best_flights":[{"price":3800,"booking_token":"book-live-123","flights":[{"departure_airport":{"id":"LHR","time":"2027-03-23 12:00"},"arrival_airport":{"id":"HND","time":"2027-03-24 09:00"},"duration":790,"airline":"British Airways","flight_number":"BA 5","travel_class":"Business"}]}]}""",
                    )
                }
            }
        val service =
            SearchService(
                listOf(GoogleFlightsProvider("serpapi", "SerpApi", "key", GoogleFlightsStyle.SERPAPI, http)),
                SqliteResponseCache(),
                FareStore,
            )
        val query =
            SearchQuery(
                origins = listOf("LHR"),
                destinations = listOf("HND"),
                outboundFrom = "2027-03-23",
                tripType = "one_way",
                adults = 2,
                cabin = "business",
                providers = listOf("serpapi"),
                investigation = "review-reingest",
                confirmed = true,
            )
        val initial = service.search(query)
        assertTrue(initial.ok, initial.error)
        val initialFare = initial.result!!.itineraries.single()
        assertEquals(1900.0, initialFare.pricePerPerson)
        assertEquals(1, networkCalls)
        val again = service.search(query.copy(cacheOnly = true))
        assertTrue(again.ok, again.error)
        assertEquals(1, networkCalls)
        val flight = FlightQueries.listByInvestigation("review-reingest").single()
        assertEquals(1, PriceHistoryQueries.getByFlightId(flight.id).size)
        val stored = FareStore.load("review-reingest")!!
        val token = stored.tokens.values.single()
        assertEquals("book-live-123", token.bookingToken)
    }

    @Test
    fun migrationPreservesPopulatedOlderSchema() {
        val file = File.createTempFile("clawfare-old-schema", ".db")
        file.deleteOnExit()
        val url = "jdbc:sqlite:" + file.absolutePath
        DriverManager.getConnection(url).use { conn ->
            val sql =
                listOf(
                    """CREATE TABLE investigations (slug TEXT PRIMARY KEY, origin TEXT, destination TEXT, depart_start TEXT, depart_end TEXT, return_start TEXT, return_end TEXT, cabin_class TEXT DEFAULT 'economy', max_stops INTEGER DEFAULT 1, max_price REAL, depart_after TEXT, depart_before TEXT, min_trip_days INTEGER, max_trip_days INTEGER, must_include_date TEXT, max_layover_minutes INTEGER, excluded_airlines TEXT, included_airlines TEXT, created_at TEXT, updated_at TEXT)""",
                    """CREATE TABLE flights (id TEXT PRIMARY KEY, investigation_slug TEXT, share_link TEXT, source TEXT, trip_type TEXT, ticket_structure TEXT, price_amount REAL, price_currency TEXT, price_market TEXT, price_checked_at TEXT, origin TEXT, destination TEXT, outbound_json TEXT, return_json TEXT, booking_class TEXT, cabin_mixed INTEGER DEFAULT 0, stale INTEGER DEFAULT 0, aircraft_type TEXT, fare_brand TEXT, disqualified TEXT, notes TEXT, tags TEXT, captured_at TEXT)""",
                    """CREATE TABLE price_history (id INTEGER PRIMARY KEY AUTOINCREMENT, flight_id TEXT, amount REAL, currency TEXT, source_url TEXT, checked_at TEXT, source TEXT DEFAULT 'manual', price_market TEXT DEFAULT 'UK')""",
                    """INSERT INTO investigations (slug,origin,destination,depart_start,depart_end,cabin_class,created_at,updated_at) VALUES ('old-trip','LHR','HND','2027-03-23','2027-03-23','business','2026-11-14','2026-11-14')""",
                    """INSERT INTO flights (id,investigation_slug,share_link,source,trip_type,ticket_structure,origin,destination,outbound_json,captured_at) VALUES ('old-id','old-trip','https://example.com/legacy','manual','one_way','single','LHR','HND','{}','2026-11-14')""",
                    """INSERT INTO price_history (flight_id,amount,currency,source_url,checked_at) VALUES ('old-id',1200,'GBP','https://example.com/legacy','2026-11-14')""",
                )
            conn.createStatement().use { statement -> sql.forEach { statement.executeUpdate(it) } }
        }
        ClawfareDatabase.connect(file.absolutePath)
        assertTrue(
            file.parentFile.listFiles().orEmpty().any { candidate ->
                candidate.name.startsWith(file.name + ".backup-") && candidate.name.endsWith(".sqlite")
            },
            "Expected an online pre-migration SQLite recovery snapshot",
        )
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { version ->
                    assertTrue(version.next())
                    assertEquals(1, version.getInt(1))
                }
            }
        }
        assertNotNull(FlightQueries.getById("old-id"))
        assertEquals(1200.0, PriceHistoryQueries.getByFlightId("old-id").single().amount)
        assertEquals(null, FlightQueries.getById("old-id")!!.offerIdentity)
    }

    @Test
    fun legacyInvestigationAndFlightCommandsReadPortedProviderRecords() {
        ClawfareDatabase.connectInMemory()
        val result =
            SampleData.florida().copy(
                id = "review-cli-compat",
                itineraries = products().take(1),
                addons = emptyList(),
                vendors = emptyList(),
                vendorItineraryId = null,
                scenarioLabel = null,
            )
        FareStore.save(StoredResult(result))
        val flight = FlightQueries.listByInvestigation(result.id).single()
        assertEquals(0, CommandLine(ClawfareCommand()).execute("inv", "show", result.id))
        assertEquals(0, CommandLine(ClawfareCommand()).execute("flight", "show", flight.id))
        assertEquals(0, CommandLine(ClawfareCommand()).execute("flight", "history", result.id, "--by-flight"))
    }
}
