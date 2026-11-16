package com.clawfare.search

import kotlinx.serialization.Serializable

/**
 * Unprivileged inputs from a platform-specific cache/provider adapter.
 * Cache status must reflect the same freshness policy used during execution.
 */
@Serializable
data class PlannedCall(
    val description: String,
    val cached: Boolean,
)

@Serializable
data class PlannedProvider(
    val id: String,
    val configured: Boolean,
    val calls: List<PlannedCall>,
)

/**
 * Pessimistic provider-call counts: return lookups are an upper bound, not
 * proven billable calls. The host must reserve budget durably and require
 * consent; this pure function never authorizes network access.
 */
@Serializable
data class CallBudget(
    val liveCalls: Int,
    val cachedCalls: Int,
    val skippedCalls: Int,
    val returnLookupsUpperBound: Int,
    val lines: List<String>,
) {
    val maximumNetworkCalls: Int get() = liveCalls + returnLookupsUpperBound
}

fun projectCallBudget(
    query: SearchQuery,
    providers: List<PlannedProvider>,
): CallBudget {
    var live = 0
    var cached = 0
    var skipped = 0
    var returns = 0
    val lines = mutableListOf<String>()
    for (provider in providers) {
        if (
            (provider.id == "serpapi" || provider.id == "searchapi") &&
            query.fetchReturns && query.tripType != "one_way"
        ) {
            returns += provider.calls.size * query.returnChoices
        }
        for (call in provider.calls) {
            when {
                call.cached -> {
                    cached++
                    lines += "${call.description} — cached"
                }
                !provider.configured -> {
                    skipped++
                    lines += "${call.description} — skipped, no API key"
                }
                query.cacheOnly -> {
                    skipped++
                    lines += "${call.description} — not in cache"
                }
                else -> {
                    live++
                    lines += "${call.description} — will call"
                }
            }
        }
    }
    if (query.cacheOnly) returns = 0
    return CallBudget(live, cached, skipped, returns, lines)
}
