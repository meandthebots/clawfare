package com.clawfare.search

import com.clawfare.db.ProviderCache
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

/**
 * Raw provider responses in the same SQLite database as the investigations.
 * This is a cache so a search can be parsed again without another API call.
 * Fares themselves live on flights and price_history.
 */
class SqliteResponseCache : ResponseCache {
    private val maps = MapSerializer(String.serializer(), String.serializer())

    override fun get(
        provider: String,
        params: Map<String, String>,
    ): CachedResponse? =
        transaction {
            val key = sha256(DiskCache.canonical(params))
            ProviderCache
                .selectAll()
                .where { (ProviderCache.provider eq provider) and (ProviderCache.cacheKey eq key) }
                .firstOrNull()
                ?.let { row ->
                    CachedResponse(
                        cachedAt = row[ProviderCache.fetchedAt],
                        provider = provider,
                        params = AppJson.decodeFromString(maps, row[ProviderCache.paramsJson]),
                        body = row[ProviderCache.body],
                    )
                }
        }

    override fun put(
        provider: String,
        params: Map<String, String>,
        body: String,
        secrets: List<String>,
    ) {
        val safeParams = params.filterKeys { it != "api_key" && it != "token" && it != "access_token" }
        val key = sha256(DiskCache.canonical(safeParams))
        val cleaned = redact(body, secrets)
        transaction {
            ProviderCache.deleteWhere {
                (ProviderCache.provider eq provider) and (ProviderCache.cacheKey eq key)
            }
            ProviderCache.insert {
                it[ProviderCache.provider] = provider
                it[cacheKey] = key
                it[paramsJson] = AppJson.encodeToString(maps, safeParams.toSortedMap())
                it[ProviderCache.body] = cleaned
                it[fetchedAt] = Instant.now().toString()
            }
        }
    }
}
