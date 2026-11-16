package com.clawfare.search

import kotlin.math.round

/** Pure fare calculations shared by the JVM server, Kotlin/JS, and Kotlin/Wasm. */
object FareMath {
    /**
     * Convert to whole GBP using currency units per one GBP. Missing, zero,
     * infinite and negative values are deliberately not treated as GBP.
     */
    fun toGbpOrNull(
        amount: Double,
        unitsPerGbp: Double?,
    ): Double? {
        val rate = unitsPerGbp?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        if (!amount.isFinite() || amount < 0.0) return null
        return round(amount / rate)
    }

    /** A group total is the sum of per-person fares; no inferred baggage cost. */
    fun partyTotal(
        primaryPerPerson: Double,
        primaryPassengers: Int,
        addonPerPerson: Double = 0.0,
        addonPassengers: Int = 0,
    ): Double = primaryPerPerson * primaryPassengers + addonPerPerson * addonPassengers
}
