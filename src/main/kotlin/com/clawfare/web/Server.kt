package com.clawfare.web

import com.clawfare.db.ClawfareDatabase
import com.clawfare.search.AppJson
import com.clawfare.search.Export
import com.clawfare.search.FareStore
import com.clawfare.search.FilterSpec
import com.clawfare.search.ResultSet
import com.clawfare.search.SampleSeeder
import com.clawfare.search.SearchQuery
import com.clawfare.search.SearchService
import com.clawfare.search.SqliteResponseCache
import com.clawfare.search.StatusResponse
import com.clawfare.search.TrackRequest
import com.clawfare.search.VendorRequest
import com.clawfare.search.providersFromEnv
import com.clawfare.web.generated.GetEndpoints
import com.clawfare.web.generated.KtorClawfareApiRoutes
import com.clawfare.web.generated.PostEndpoints
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.util.concurrent.Semaphore

private const val MAX_QUERY_JSON_BYTES = 64 * 1024
private const val MAX_EXPORT_JSON_BYTES = 2 * 1024 * 1024

/**
 * Read raw bytes up to a route-specific bound instead of trusting the optional
 * Content-Length header. This also covers chunked/streaming requests and guards
 * JSON parsing before any search, quota reservation, or database mutation.
 */
private suspend fun <T> ApplicationCall.receiveBoundedJson(
    serializer: DeserializationStrategy<T>,
    maxBytes: Int,
): T? {
    // Content-Length can reject oversized requests early, but an absent or
    // forged value never substitutes for counting received bytes below.
    val declaredLength = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declaredLength != null && declaredLength > maxBytes) {
        respondText(
            """{"error":"Request body too large."}""",
            ContentType.Application.Json,
            HttpStatusCode.PayloadTooLarge,
        )
        return null
    }
    val channel = receiveChannel()
    val bytes = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val chunk = ByteArray(8192)
    while (true) {
        val count = channel.readAvailable(chunk, 0, chunk.size)
        if (count < 0) break
        if (bytes.size() + count > maxBytes) {
            respondText(
                """{"error":"Request body too large."}""",
                ContentType.Application.Json,
                HttpStatusCode.PayloadTooLarge,
            )
            return null
        }
        bytes.write(chunk, 0, count)
    }
    return try {
        AppJson.decodeFromString(serializer, bytes.toString(Charsets.UTF_8))
    } catch (_: Exception) {
        respondText(
            """{"error":"Invalid JSON request body."}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        null
    }
}

@Serializable
data class ExportRequest(
    val result: ResultSet,
    val filters: FilterSpec = FilterSpec(),
    val kind: String = "itineraries",
)

object Server {
    const val HOST: String = "127.0.0.1"

    @Volatile
    private var pageHtml: String? = null

    fun start(port: Int) {
        println("Clawfare  http://$HOST:$port")
        embeddedServer(Netty, host = HOST, port = port, module = Application::clawfareModule).start(wait = true)
    }

    fun page(): String {
        pageHtml?.let { return it }
        val text =
            Server::class.java.classLoader
                .getResourceAsStream("web/index.html")
                ?.bufferedReader()
                ?.readText()
                ?: error("web/index.html is missing from the classpath")
        pageHtml = text
        return text
    }
}

fun Application.clawfareModule() {
    if (!ClawfareDatabase.isInitialized()) {
        val path = System.getenv("CLAWFARE_DB")?.takeIf { it.isNotBlank() } ?: ClawfareDatabase.defaultPath
        ClawfareDatabase.connect(path)
    }
    SampleSeeder.ensure()
    val service = SearchService(providersFromEnv(), SqliteResponseCache(), FareStore)
    val local = LocalAccess()
    val paidRequestLock = Semaphore(1)
    // Web requests are deliberately smaller than offline CLI batches.
    val maxWebCalls = System.getenv("CLAWFARE_WEB_MAX_NETWORK_CALLS")?.toIntOrNull()?.coerceIn(0, 100) ?: 16

    suspend fun authorized(call: io.ktor.server.application.ApplicationCall): Boolean {
        if (local.acceptsWrite(call)) return true
        call.respondText(
            """{"error":"A same-origin local session with JSON content is required."}""",
            ContentType.Application.Json,
            HttpStatusCode.Forbidden,
        )
        return false
    }

    suspend fun denied(
        call: io.ktor.server.application.ApplicationCall,
        detail: String,
    ) {
        call.respondText(
            """{"error":"$detail"}""",
            ContentType.Application.Json,
            HttpStatusCode.Forbidden,
        )
    }

    fun searchFingerprint(q: SearchQuery): String = AppJson.encodeToString(SearchQuery.serializer(), q.copy(confirmed = false))

    fun vendorFingerprint(req: VendorRequest): String = AppJson.encodeToString(VendorRequest.serializer(), req.copy(confirmed = false))

    install(ContentNegotiation) { json(AppJson) }
    install(StatusPages) {
        // Arbitrary exception messages may contain provider payloads, trip
        // details or opaque tokens that are not covered by known-secret redaction.
        exception<Throwable> { call, _ ->
            call.respondText(
                """{"error":"Internal server error."}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
    routing {
        // Host validation must cover GET routes as well as writes. Otherwise a
        // hostile DNS name rebinding to 127.0.0.1 could read saved investigations
        // through its own same-origin hostname despite the session/Origin checks.
        intercept(ApplicationCallPipeline.Call) {
            if (!local.acceptsHost(call.request.headers[HttpHeaders.Host])) {
                denied(call, "Invalid local host.")
                finish()
            }
        }
        val api = KtorClawfareApiRoutes(this)
        get("/") { call.respondText(Server.page(), ContentType.Text.Html) }
        api.get(GetEndpoints.getSession) {
            if (!local.acceptsHost(call.request.headers[HttpHeaders.Host])) {
                denied(call, "Invalid local host.")
                return@get
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(mapOf("token" to local.sessionToken))
        }
        get("/favicon.ico") { call.respond(HttpStatusCode.NoContent) }
        api.get(GetEndpoints.getStatus) {
            call.respond(
                StatusResponse(
                    cacheDir = "sqlite",
                    searchesDir = "sqlite",
                    providers =
                        providersFromEnv().map { provider ->
                            com.clawfare.search.QuotaView(provider.id, provider.configured, note = idleNote(provider))
                        },
                ),
            )
        }
        api.get(GetEndpoints.getQuota) {
            if (!local.acceptsSession(call)) {
                denied(call, "A same-origin local session is required for quota inspection.")
                return@get
            }
            // Quota introspection can contact external provider APIs. It must
            // never be triggered by a cross-origin image/navigation GET, and
            // repeated requests from a valid session are throttled.
            call.response.header(HttpHeaders.CacheControl, "no-store")
            if (!local.tryBeginQuotaCheck()) {
                call.response.header(HttpHeaders.RetryAfter, LocalAccess.QUOTA_CHECK_COOLDOWN_SECONDS.toString())
                call.respondText(
                    """{"error":"Quota inspection rate limit exceeded."}""",
                    ContentType.Application.Json,
                    HttpStatusCode.TooManyRequests,
                )
                return@get
            }
            val views = withContext(Dispatchers.IO) { providersFromEnv().map { it.quota() } }
            call.respond(views)
        }
        api.get(GetEndpoints.getSample) { call.respond(SampleSeeder.ensure()) }
        api.get(GetEndpoints.getSearches) { call.respond(service.listSearches()) }
        api.get(GetEndpoints.getResult) {
            val result = service.open(call.parameters["id"] ?: "")
            if (result == null) {
                call.respondText("""{"error":"Not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            } else {
                call.respond(result)
            }
        }
        api.post(PostEndpoints.estimate) {
            if (!authorized(call)) return@post
            val query = call.receiveBoundedJson(SearchQuery.serializer(), MAX_QUERY_JSON_BYTES) ?: return@post
            // Estimation must never trigger provider quota HTTP calls before consent.
            val estimate = withContext(Dispatchers.IO) { service.estimate(query, includeQuota = false) }
            val upper = estimate.liveCalls + estimate.returnLookupsUpperBound
            if (upper in 1..maxWebCalls) {
                call.response.header("X-Clawfare-Consent", local.issueConsent("search", searchFingerprint(query)))
            }
            val shown =
                if (upper > maxWebCalls) {
                    estimate.copy(
                        warnings =
                            estimate.warnings +
                                "Web request exceeds the $maxWebCalls-call limit; use an offline capped batch.",
                    )
                } else {
                    estimate
                }
            call.respond(shown)
        }
        api.post(PostEndpoints.search) {
            if (!authorized(call)) return@post
            val query = call.receiveBoundedJson(SearchQuery.serializer(), MAX_QUERY_JSON_BYTES) ?: return@post
            val estimate = service.estimate(query)
            val upper = estimate.liveCalls + estimate.returnLookupsUpperBound
            if (upper > maxWebCalls) {
                call.respondText(
                    """{"error":"Web network-call limit exceeded."}""",
                    ContentType.Application.Json,
                    HttpStatusCode.TooManyRequests,
                )
                return@post
            }
            if (upper > 0) {
                val approved =
                    query.confirmed &&
                        local.consumeConsent(
                            call.request.headers["X-Clawfare-Consent"],
                            "search",
                            searchFingerprint(query),
                        )
                if (!approved) {
                    denied(call, "A matching, unexpired estimate and explicit confirmation are required.")
                    return@post
                }
            }
            if (!paidRequestLock.tryAcquire()) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@post
            }
            try {
                val bounded = query.copy(maxLiveCalls = minOf(query.maxLiveCalls ?: maxWebCalls, maxWebCalls))
                val response = withContext(Dispatchers.IO) { service.search(bounded) }
                call.respond(response)
            } finally {
                paidRequestLock.release()
            }
        }
        api.post(PostEndpoints.vendors) {
            if (!authorized(call)) return@post
            val id = call.parameters["id"] ?: ""
            val body = call.receiveBoundedJson(VendorRequest.serializer(), MAX_QUERY_JSON_BYTES) ?: return@post
            if (body.markets.size > 2 || body.markets.size * 7 > maxWebCalls) {
                call.respondText(
                    """{"error":"Seller-market request exceeds the configured web call cap."}""",
                    ContentType.Application.Json,
                    HttpStatusCode.TooManyRequests,
                )
                return@post
            }
            val action = "vendors/$id"
            if (!body.cacheOnly && body.confirmed) {
                val approved =
                    local.consumeConsent(
                        call.request.headers["X-Clawfare-Consent"],
                        action,
                        vendorFingerprint(body),
                    )
                if (!approved) {
                    denied(call, "A matching seller lookup estimate and confirmation are required.")
                    return@post
                }
            }
            if (!paidRequestLock.tryAcquire()) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@post
            }
            try {
                val response = withContext(Dispatchers.IO) { service.vendors(id, body) }
                if (!body.cacheOnly && !body.confirmed && response.needsConfirmation) {
                    call.response.header("X-Clawfare-Consent", local.issueConsent(action, vendorFingerprint(body)))
                }
                call.respond(response)
            } finally {
                paidRequestLock.release()
            }
        }
        api.post(PostEndpoints.track) {
            if (!authorized(call)) return@post
            val id = call.parameters["id"] ?: ""
            val body = call.receiveBoundedJson(TrackRequest.serializer(), MAX_QUERY_JSON_BYTES) ?: return@post
            val slug =
                runCatching { FareStore.track(id, body.itineraryId, body.tripSlug) }.getOrElse {
                    call.respondText(
                        """{"error":"Could not save."}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                    return@post
                }
            call.respond(mapOf("slug" to slug))
        }
        api.post(PostEndpoints.exportHtml) {
            if (!authorized(call)) return@post
            val body = call.receiveBoundedJson(ExportRequest.serializer(), MAX_EXPORT_JSON_BYTES) ?: return@post
            val snapshot = body.result.copy(suggestedFilters = body.filters)
            val html = Export.html(Server.page(), snapshot)
            call.response.header("Content-Disposition", "attachment; filename=\"clawfare-${safeName(body.result.id)}.html\"")
            call.respondText(html, ContentType.Text.Html)
        }
        api.post(PostEndpoints.exportCsv) {
            if (!authorized(call)) return@post
            val body = call.receiveBoundedJson(ExportRequest.serializer(), MAX_EXPORT_JSON_BYTES) ?: return@post
            val csv =
                when (body.kind) {
                    "vendors" -> Export.vendorCsv(body.result)
                    "seller_options" -> Export.sellerOptionsCsv(body.result)
                    else -> Export.itineraryCsv(body.result, body.filters)
                }
            call.response.header("Content-Disposition", "attachment; filename=\"clawfare-${safeName(body.result.id)}.csv\"")
            call.respondText(csv, ContentType.Text.CSV)
        }
        api.get(GetEndpoints.getSampleExport) {
            call.respondText(Export.html(Server.page(), SampleSeeder.ensure()), ContentType.Text.Html)
        }
    }
}

private fun idleNote(provider: com.clawfare.search.FareProvider): String =
    when {
        !provider.configured && provider.id == "serpapi" -> "Set SERPAPI_API_KEY"
        !provider.configured && provider.id == "searchapi" -> "Set SEARCHAPI_API_KEY"
        !provider.configured && provider.id == "travelpayouts" -> "Set TRAVELPAYOUTS_API_TOKEN"
        !provider.configured && provider.id == "duffel" -> "Set DUFFEL_ACCESS_TOKEN"
        provider.priceKind == "mock" -> "Configured. Prices are labelled mock."
        provider.priceKind == "cached" -> "Configured. Prices are labelled cached, not live."
        else -> "Configured. The key stays on the server."
    }

private fun safeName(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]+"), "-").take(80).ifBlank { "search" }
