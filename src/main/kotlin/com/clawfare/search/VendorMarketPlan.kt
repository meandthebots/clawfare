package com.clawfare.search

/**
 * Canonical seller markets shared by request planning and actual provider
 * execution. The default UK market is a real lookup, not a zero-call plan.
 */
class VendorMarketPlan private constructor(
    val markets: List<String>,
) {
    // Per market: round-trip base + optional return selection + booking
    // options (3), then two one-way bases and booking options (2 + 2).
    val networkCallsUpperBound: Int get() = markets.size * CALLS_PER_MARKET_UPPER_BOUND

    fun withinWebCap(
        maxNetworkCalls: Int,
        maxMarkets: Int = 2,
    ): Boolean = markets.size <= maxMarkets && networkCallsUpperBound <= maxNetworkCalls

    companion object {
        private const val CALLS_PER_MARKET_UPPER_BOUND = 7

        fun parse(raw: List<String>): VendorMarketPlan =
            VendorMarketPlan(
                raw
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .ifEmpty { listOf("uk") },
            )
    }
}
