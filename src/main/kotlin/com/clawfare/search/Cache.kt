package com.clawfare.search

import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import java.time.Instant

interface ResponseCache {
    fun get(
        provider: String,
        params: Map<String, String>,
    ): CachedResponse?

    fun put(
        provider: String,
        params: Map<String, String>,
        body: String,
        secrets: List<String> = emptyList(),
    )
}

class DiskCache(
    val root: File,
) : ResponseCache {
    override fun get(
        provider: String,
        params: Map<String, String>,
    ): CachedResponse? {
        val file = file(provider, params)
        if (!file.isFile) return null
        return try {
            AppJson.decodeFromString(CachedResponse.serializer(), file.readText())
        } catch (_: Exception) {
            null
        }
    }

    override fun put(
        provider: String,
        params: Map<String, String>,
        body: String,
        secrets: List<String>,
    ) {
        val safeParams = params.filterKeys { it != "api_key" && it != "token" && it != "access_token" }
        val payload =
            CachedResponse(
                cachedAt = Instant.now().toString(),
                provider = provider,
                params = safeParams.toSortedMap(),
                body = redact(body, secrets),
            )
        val file = file(provider, safeParams)
        file.parentFile?.mkdirs()
        file.writeText(AppJson.encodeToString(CachedResponse.serializer(), payload))
    }

    fun file(
        provider: String,
        params: Map<String, String>,
    ): File = File(File(root, provider), sha256(canonical(params)) + ".json")

    companion object {
        fun canonical(params: Map<String, String>): String =
            params
                .filterKeys { it != "api_key" && it != "token" && it != "access_token" }
                .toSortedMap()
                .entries
                .joinToString("&") { "${it.key}=${it.value}" }
    }
}

@Serializable
data class CachedResponse(
    val cachedAt: String,
    val provider: String,
    val params: Map<String, String>,
    val body: String,
)

fun sha256(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
