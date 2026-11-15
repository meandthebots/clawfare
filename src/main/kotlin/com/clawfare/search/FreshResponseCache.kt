package com.clawfare.search

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Revalidates provider prices rather than treating arbitrarily old cached
 * responses as a free, fresh search. Explicit cache-only re-ingestion bypasses
 * this wrapper and retains historical observations.
 */
class FreshResponseCache(
    private val delegate: ResponseCache,
    private val maxAge: Duration = Duration.ofHours(2),
    private val clock: Clock = Clock.systemUTC(),
) : ResponseCache {
    override fun get(
        provider: String,
        params: Map<String, String>,
    ): CachedResponse? {
        val hit = delegate.get(provider, params) ?: return null
        if (provider !in setOf("serpapi", "searchapi", "duffel")) return hit
        val at = runCatching { Instant.parse(hit.cachedAt) }.getOrNull() ?: return null
        val elapsed = Duration.between(at, clock.instant())
        return hit.takeIf { !elapsed.isNegative && elapsed <= maxAge }
    }

    override fun put(
        provider: String,
        params: Map<String, String>,
        body: String,
        secrets: List<String>,
    ) {
        delegate.put(provider, params, body, secrets)
    }
}
