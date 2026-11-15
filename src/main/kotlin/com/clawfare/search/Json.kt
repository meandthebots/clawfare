package com.clawfare.search

import kotlinx.serialization.json.Json

val AppJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

/** Strip credentials before anything is logged, cached as a message, or sent to the browser. */
fun redact(
    text: String,
    secrets: List<String> = emptyList(),
): String {
    var s = text
    for (secret in secrets) {
        if (secret.length >= 6) s = s.replace(secret, "REDACTED")
    }
    s = API_KEY.replace(s, "$1=REDACTED")
    s = BEARER.replace(s, "Bearer REDACTED")
    s = DUFFEL.replace(s, "duffel_REDACTED")
    return s
}

private val API_KEY = Regex("(?i)(api[_-]?key|access[_-]?token|token|apikey)=([^&\\s\"']+)")
private val BEARER = Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]+")
private val DUFFEL = Regex("duffel_(test|live)_[A-Za-z0-9]+")
