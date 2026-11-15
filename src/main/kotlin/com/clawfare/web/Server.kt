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
import com.clawfare.search.redact
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class ExportRequest(
    val result: ResultSet,
    val filters: FilterSpec = FilterSpec(),
    val kind: String = "itineraries",
)

object Server {
    @Volatile
    private var pageHtml: String? = null

    fun start(port: Int) {
        println("Clawfare  http://localhost:$port")
        embeddedServer(Netty, port = port, module = Application::clawfareModule).start(wait = true)
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

    install(ContentNegotiation) { json(AppJson) }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText(
                """{"error":"${redact(cause.message ?: "Something went wrong").replace("\"", "'")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
    routing {
        get("/") { call.respondText(Server.page(), ContentType.Text.Html) }
        get("/favicon.ico") { call.respond(HttpStatusCode.NoContent) }
        get("/api/status") {
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
        get("/api/quota") {
            val views = withContext(Dispatchers.IO) { providersFromEnv().map { it.quota() } }
            call.respond(views)
        }
        get("/api/sample") { call.respond(SampleSeeder.ensure()) }
        get("/api/searches") { call.respond(service.listSearches()) }
        get("/api/results/{id}") {
            val result = service.open(call.parameters["id"] ?: "")
            if (result == null) {
                call.respondText("""{"error":"Not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            } else {
                call.respond(result)
            }
        }
        post("/api/estimate") {
            val query = call.receive<SearchQuery>()
            call.respond(service.estimate(query))
        }
        post("/api/search") {
            val query = call.receive<SearchQuery>()
            val response = withContext(Dispatchers.IO) { service.search(query) }
            call.respond(response)
        }
        post("/api/results/{id}/vendors") {
            val id = call.parameters["id"] ?: ""
            val body = call.receive<VendorRequest>()
            val response = withContext(Dispatchers.IO) { service.vendors(id, body) }
            call.respond(response)
        }
        post("/api/results/{id}/track") {
            val id = call.parameters["id"] ?: ""
            val body = call.receive<TrackRequest>()
            val slug =
                runCatching { FareStore.track(id, body.itineraryId, body.tripSlug) }.getOrElse { error ->
                    call.respondText(
                        """{"error":"${redact(error.message ?: "Could not save").replace("\"", "'")}"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                    return@post
                }
            call.respondText("""{"slug":"$slug"}""", ContentType.Application.Json)
        }
        post("/api/export/html") {
            val body = call.receive<ExportRequest>()
            val snapshot = body.result.copy(suggestedFilters = body.filters)
            val html = Export.html(Server.page(), snapshot)
            call.response.header("Content-Disposition", "attachment; filename=\"clawfare-${safeName(body.result.id)}.html\"")
            call.respondText(html, ContentType.Text.Html)
        }
        post("/api/export/csv") {
            val body = call.receive<ExportRequest>()
            val csv = if (body.kind == "vendors") Export.vendorCsv(body.result) else Export.itineraryCsv(body.result, body.filters)
            call.response.header("Content-Disposition", "attachment; filename=\"clawfare-${safeName(body.result.id)}.csv\"")
            call.respondText(csv, ContentType.Text.CSV)
        }
        get("/api/sample/export.html") {
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
