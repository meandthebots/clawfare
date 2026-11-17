package com.clawfare.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Browser-origin guard for a loopback-only application. Local processes with
 * access to the machine are outside this protection boundary; do not expose
 * this server on public interfaces or through an unauthenticated proxy.
 */
class LocalAccess(
    private val clock: () -> Instant = Instant::now,
) {
    private data class Grant(
        val action: String,
        val fingerprint: String,
        val expiresAt: Instant,
    )

    val sessionToken: String = newToken()
    private val grants = ConcurrentHashMap<String, Grant>()

    fun acceptsHost(host: String?): Boolean = host != null && host.matches(Regex("(?i)^(localhost|127[.]0[.]0[.]1)(:[0-9]{1,5})?$"))

    fun acceptsOrigin(
        host: String?,
        origin: String?,
    ): Boolean {
        if (!acceptsHost(host)) return false
        if (origin == null) return true
        val uri = runCatching { URI(origin) }.getOrNull() ?: return false
        return uri.scheme.equals("http", ignoreCase = true) &&
            uri.rawAuthority.equals(host, ignoreCase = true) &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            uri.userInfo == null
    }

    fun acceptsWrite(call: ApplicationCall): Boolean {
        val host = call.request.headers[HttpHeaders.Host]
        if (!acceptsOrigin(host, call.request.headers[HttpHeaders.Origin])) return false
        if (!call.request.contentType().match(ContentType.Application.Json)) return false
        val supplied = call.request.headers["X-Clawfare-Session"] ?: return false
        return MessageDigest.isEqual(
            supplied.toByteArray(Charsets.UTF_8),
            sessionToken.toByteArray(Charsets.UTF_8),
        )
    }

    fun issueConsent(
        action: String,
        request: String,
    ): String {
        grants.entries.removeIf { it.value.expiresAt <= clock() }
        if (grants.size >= 128) grants.clear()
        val token = newToken()
        grants[token] = Grant(action, fingerprint(request), clock().plusSeconds(600))
        return token
    }

    /** Consume before any expensive action. Consent is one-use and request-bound. */
    fun consumeConsent(
        token: String?,
        action: String,
        request: String,
    ): Boolean {
        val grant = token?.let { grants.remove(it) } ?: return false
        return grant.expiresAt.isAfter(clock()) &&
            grant.action == action &&
            grant.fingerprint == fingerprint(request)
    }

    private fun fingerprint(request: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(request.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    private fun newToken(): String =
        ByteArray(32).also { SecureRandom().nextBytes(it) }.let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }

}
