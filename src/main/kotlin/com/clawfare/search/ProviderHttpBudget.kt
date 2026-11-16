package com.clawfare.search

import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Conservatively reserve every possible billable HTTP request before dispatch.
 * A reserved-but-unfinished call still consumes its daily/monthly allowance.
 * No attempt to infer a provider's invoice from HTTP status is made.
 */
@Serializable
data class ProviderHttpEvent(
    val id: String,
    val at: String,
    val provider: String,
    val category: String,
    /** reserved or completed. Only reserved events consume budget. */
    val phase: String,
    val httpStatus: Int? = null,
    /** unknown, rather than a fabricated "charged" or "free" classification. */
    val charge: String = "unknown",
    val outcome: String = "",
)

data class ProviderHttpUsage(
    val provider: String,
    val day: String,
    val reservedToday: Int,
    val dailyLimit: Int?,
    val reservedThisMonth: Int,
    val monthlyLimit: Int?,
    val unfinished: Int,
)

class ProviderHttpBudget(
    private val directory: File,
    private val dailyLimits: Map<String, Int>,
    private val monthlyLimits: Map<String, Int> = emptyMap(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val journal = File(directory, "provider-http-budget.ndjson")
    private val lockFile = File(directory, "provider-http-budget.lock")

    private fun <T> locked(action: () -> T): T {
        ensurePrivateDirectory()
        val monitor = mutexes.computeIfAbsent(lockFile.canonicalPath) { Any() }
        synchronized(monitor) {
            ensurePrivateFile(lockFile)
            FileChannel.open(lockFile.toPath(), StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    return action()
                }
            }
        }
    }

    fun reserve(
        provider: String,
        category: String,
    ): String =
        locked {
            require(provider in knownProviders) { "Unknown provider; network request denied." }
            require(category in knownCategories) { "Unknown request category; network request denied." }
            val day = clock.instant().toString().take(10)
            val month = day.take(7)
            val events = read()
            val consumed = events.filter { it.phase == "reserved" && it.provider == provider }
            val today = consumed.count { it.at.startsWith(day) }
            val monthly = consumed.count { it.at.startsWith(month) }
            val dailyCap = dailyLimits[provider]
                ?: error("Missing HTTP daily cap for $provider. Configure CLAWFARE_HTTP_DAILY_${provider.uppercase()}.")
            require(dailyCap >= 0) { "Daily cap must not be negative." }
            val monthlyCap = monthlyLimits[provider]
            require(monthlyCap == null || monthlyCap >= 0) { "Monthly cap must not be negative." }
            require(today < dailyCap) { "$provider HTTP daily cap reached ($today/$dailyCap). No request sent." }
            require(monthlyCap == null || monthly < monthlyCap) {
                "$provider HTTP monthly cap reached ($monthly/$monthlyCap). No request sent."
            }
            val id = UUID.randomUUID().toString()
            append(ProviderHttpEvent(id, clock.instant().toString(), provider, category, "reserved"))
            id
        }

    fun complete(
        id: String,
        provider: String,
        category: String,
        status: Int?,
        outcome: String,
    ) {
        locked {
            val events = read()
            val reservation = events.singleOrNull { it.id == id && it.phase == "reserved" }
                ?: error("Missing request reservation. HTTP outcome not reconciled.")
            require(reservation.provider == provider && reservation.category == category) { "Mismatched HTTP reservation." }
            require(events.none { it.id == id && it.phase == "completed" }) { "Duplicate request completion." }
            append(
                ProviderHttpEvent(
                    id = id,
                    at = clock.instant().toString(),
                    provider = provider,
                    category = category,
                    phase = "completed",
                    httpStatus = status,
                    charge = "unknown",
                    outcome = outcome,
                ),
            )
        }
    }

    fun usage(provider: String): ProviderHttpUsage =
        locked {
            val now = clock.instant().toString()
            val events = read().filter { it.provider == provider }
            val reservations = events.filter { it.phase == "reserved" }
            val completed = events.filter { it.phase == "completed" }.map { it.id }.toSet()
            ProviderHttpUsage(
                provider = provider,
                day = now.take(10),
                reservedToday = reservations.count { it.at.startsWith(now.take(10)) },
                dailyLimit = dailyLimits[provider],
                reservedThisMonth = reservations.count { it.at.startsWith(now.take(7)) },
                monthlyLimit = monthlyLimits[provider],
                unfinished = reservations.count { it.id !in completed },
            )
        }

    private fun read(): List<ProviderHttpEvent> {
        if (!journal.isFile) return emptyList()
        // Corruption or truncated lines MUST fail closed; do not erase a
        // previous reservation or try to repair an incomplete write silently.
        return journal.readLines().filter { it.isNotBlank() }.map { line ->
            val event = AppJson.decodeFromString(ProviderHttpEvent.serializer(), line)
            require(event.phase == "reserved" || event.phase == "completed") { "Invalid HTTP journal phase." }
            event
        }
    }

    private fun append(event: ProviderHttpEvent) {
        ensurePrivateFile(journal)
        FileOutputStream(journal, true).use { stream ->
            val line = AppJson.encodeToString(ProviderHttpEvent.serializer(), event) + System.lineSeparator()
            stream.write(line.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
    }

    private fun ensurePrivateDirectory() {
        if (!directory.exists()) require(directory.mkdirs()) { "Unable to create private HTTP budget directory." }
        require(directory.isDirectory) { "HTTP budget path is not a directory." }
        protect(directory, directoryPermissions)
    }

    private fun ensurePrivateFile(file: File) {
        if (!file.exists()) Files.createFile(file.toPath())
        protect(file, filePermissions)
    }

    private fun protect(
        path: File,
        permissions: Set<PosixFilePermission>,
    ) {
        try {
            Files.setPosixFilePermissions(path.toPath(), permissions)
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX systems still use a local directory; operators must
            // enforce ACLs on that platform as described in the runbook.
        }
    }

    companion object {
        private val mutexes = ConcurrentHashMap<String, Any>()
        private val knownProviders = setOf("serpapi", "searchapi", "duffel", "travelpayouts")
        private val knownCategories =
            setOf("base_search", "return_selection", "booking_options", "exact_offer", "quota")
        private val directoryPermissions =
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        private val filePermissions =
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

        fun fromEnvironment(): ProviderHttpBudget {
            val dir = System.getenv("CLAWFARE_HTTP_BUDGET_DIR")
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?: File(System.getProperty("user.home"), ".clawfare")
            fun readLimits(prefix: String): Map<String, Int> =
                knownProviders.mapNotNull { provider ->
                    System.getenv(prefix + provider.uppercase())?.let { raw ->
                        provider to (raw.toIntOrNull() ?: error("Invalid integer for ${prefix + provider.uppercase()}"))
                    }
                }.toMap()
            return ProviderHttpBudget(
                directory = dir,
                dailyLimits = readLimits("CLAWFARE_HTTP_DAILY_"),
                monthlyLimits = readLimits("CLAWFARE_HTTP_MONTHLY_"),
            )
        }
    }
}

/**
 * Shared production HTTP boundary for Google Flights, Duffel, Travelpayouts,
 * their seller/return lookups, and quota checks. Explicitly never supports
 * order issuance or payment routes. Each network dispatch reserves 1 call.
 * Automatic retries are intentionally disabled: a caller retry is a new
 * budgeted dispatch.
 */
class MeteredProviderHttp(
    private val delegate: HttpFetcher,
    private val budget: ProviderHttpBudget,
) : HttpFetcher {
    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResult {
        val (provider, category) = classify(method, url)
            ?: error("Unrecognized provider API route; no network request sent.")
        val id = budget.reserve(provider, category)
        try {
            val result = delegate.request(method, url, headers, body)
            budget.complete(id, provider, category, result.status, if (result.status == 429) "rate_limited" else "response")
            return result
        } catch (e: Exception) {
            budget.complete(id, provider, category, null, "transport_error")
            throw e
        }
    }

    private fun classify(
        method: String,
        url: String,
    ): Pair<String, String>? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.userInfo != null) return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty()
        val get = method.equals("GET", true)
        return when {
            host == "serpapi.com" && get && path == "/account.json" -> "serpapi" to "quota"
            host == "serpapi.com" && get && path in setOf("/search", "/search.json") ->
                "serpapi" to googleCategory(uri.rawQuery.orEmpty())
            host == "www.searchapi.io" && get && path == "/api/v1/me" -> "searchapi" to "quota"
            host == "www.searchapi.io" && get && path == "/api/v1/search" ->
                "searchapi" to googleCategory(uri.rawQuery.orEmpty())
            host == "api.travelpayouts.com" && get && path.startsWith("/aviasales/v3/prices_for_dates") ->
                "travelpayouts" to "base_search"
            host == "api.duffel.com" && method.equals("POST", true) && path == "/air/offer_requests" ->
                "duffel" to "base_search"
            host == "api.duffel.com" && get && path.startsWith("/air/offers/") -> "duffel" to "exact_offer"
            else -> null
        }
    }

    private fun googleCategory(query: String): String =
        when {
            Regex("(?:^|&)booking_token=").containsMatchIn(query) -> "booking_options"
            Regex("(?:^|&)departure_token=").containsMatchIn(query) -> "return_selection"
            else -> "base_search"
        }
}
