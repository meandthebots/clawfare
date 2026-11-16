package com.clawfare.search

import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
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
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) return null
        return try {
            ownerOnly(file, false)
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
        val parent = requireNotNull(file.parentFile)
        Files.createDirectories(parent.toPath())
        ownerOnly(root, true)
        ownerOnly(parent, true)
        val temporary = Files.createTempFile(parent.toPath(), ".response-", ".tmp")
        try {
            ownerOnly(temporary.toFile(), false)
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(AppJson.encodeToString(CachedResponse.serializer(), payload).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary,
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            ownerOnly(file, false)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Examine only direct provider-cache files, never directories of unrelated private data. */
    fun pruneOlderThan(
        cutoff: Instant,
        confirm: Boolean = false,
    ): Int {
        if (!root.isDirectory) return 0
        val stale =
            root
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
                .flatMap { provider -> provider.listFiles().orEmpty().toList() }
                .filter { it.isFile && it.extension == "json" && !Files.isSymbolicLink(it.toPath()) }
                .filter { file ->
                    val cached = runCatching { AppJson.decodeFromString(CachedResponse.serializer(), file.readText()) }.getOrNull()
                    val observed = cached?.cachedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    observed?.isBefore(cutoff) == true
                }
        if (confirm) stale.forEach { Files.deleteIfExists(it.toPath()) }
        return stale.size
    }

    private fun ownerOnly(
        file: File,
        directory: Boolean,
    ) {
        val access =
            if (directory) {
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
            } else {
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            }
        try {
            Files.setPosixFilePermissions(file.toPath(), access)
        } catch (_: UnsupportedOperationException) {
            // Windows ACL permissions must be configured by the operator.
        }
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
