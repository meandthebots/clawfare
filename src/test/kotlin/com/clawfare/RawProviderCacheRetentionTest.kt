package com.clawfare

import com.clawfare.db.ClawfareDatabase
import com.clawfare.db.ProviderCache
import com.clawfare.search.AppJson
import com.clawfare.search.CachedResponse
import com.clawfare.search.DiskCache
import com.clawfare.search.SqliteResponseCache
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RawProviderCacheRetentionTest {
    @Test
    fun dryRunAndConfirmedSqliteRawCachePruningPreserveRecentRecords() {
        ClawfareDatabase.connectInMemory()
        val cache = SqliteResponseCache()
        val fresh = mapOf("departure_id" to "LHR", "arrival_id" to "HND")
        cache.put("serpapi", fresh, """{"marker":"recent"}""", emptyList())
        transaction {
            ProviderCache.insert {
                it[provider] = "serpapi"
                it[cacheKey] = "old-record"
                it[paramsJson] = """{"departure_id":"FRA"}"""
                it[body] = """{"marker":"old-private-provider-body"}"""
                it[fetchedAt] = "2023-01-01T00:00:00Z"
            }
        }
        val cutoff = Instant.parse("2025-01-01T00:00:00Z")
        assertEquals(1, cache.pruneOlderThan(cutoff))
        assertEquals(2L, transaction { ProviderCache.selectAll().count() })
        assertEquals(1, cache.pruneOlderThan(cutoff, confirm = true))
        assertEquals(1L, transaction { ProviderCache.selectAll().count() })
        assertTrue(cache.get("serpapi", fresh)?.body?.contains("recent") == true)
        assertEquals(0, cache.pruneOlderThan(cutoff, confirm = true))
    }

    @Test
    fun diskCacheUsesPrivateAtomicFilesAndPrunesOnlyExpiredValidRecords() {
        val root =
            File.createTempFile("clawfare-raw-cache", "").apply {
                delete()
                mkdirs()
            }
        val cache = DiskCache(root)
        val params = mapOf("departure_id" to "LHR", "api_key" to "SECRET_TOKEN")
        cache.put("serpapi", params, """{"value":"SECRET_TOKEN"}""", listOf("SECRET_TOKEN"))
        val file = cache.file("serpapi", params)
        assertTrue(file.isFile)
        assertTrue(file.readText().contains("[REDACTED]"))
        assertFalse(file.readText().contains("SECRET_TOKEN"))
        val tempFiles =
            root
                .resolve("serpapi")
                .listFiles()
                .orEmpty()
                .count { it.name.endsWith(".tmp") }
        assertEquals(0, tempFiles)
        val mode =
            runCatching { Files.getPosixFilePermissions(file.toPath()) }.getOrNull()
        if (mode != null) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                mode,
            )
        }
        val old =
            CachedResponse(
                cachedAt = "2023-01-01T00:00:00Z",
                provider = "serpapi",
                params = mapOf("departure_id" to "FRA"),
                body = """{"private":"historical"}""",
            )
        val oldFile = cache.file("serpapi", old.params)
        oldFile.writeText(AppJson.encodeToString(CachedResponse.serializer(), old))
        val cutoff = Instant.parse("2025-01-01T00:00:00Z")
        assertEquals(1, cache.pruneOlderThan(cutoff))
        assertTrue(oldFile.exists())
        assertEquals(1, cache.pruneOlderThan(cutoff, confirm = true))
        assertFalse(oldFile.exists())
        assertTrue(file.exists())
    }
}
