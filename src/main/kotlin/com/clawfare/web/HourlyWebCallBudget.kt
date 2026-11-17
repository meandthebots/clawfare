package com.clawfare.web

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** An admission decision; denied requests never reserve or dispatch calls. */
sealed interface WebCallAdmission {
    data object Granted : WebCallAdmission

    data class Throttled(
        val retryAfterSeconds: Long,
    ) : WebCallAdmission {
        init {
            require(retryAfterSeconds > 0)
        }
    }
}

/**
 * Defense-in-depth, process-local cap shared by confirmed flight searches
 * and seller lookups. Reserve pessimistically before starting provider work:
 * failures or incomplete requests do not silently return budget.
 *
 * The durable per-provider HTTP journal remains responsible for the stricter
 * cross-process / crash-safe cap. This local window only prevents repeated
 * one-shot consents from multiplying a single web session's allowance.
 */
class HourlyWebCallBudget(
    private val maxCalls: Int,
    private val clock: () -> Instant = Instant::now,
) {
    init {
        require(maxCalls >= 0) { "The hourly web network-call cap cannot be negative." }
    }

    private data class Window(
        val startedAt: Instant,
        val reserved: Int,
    )

    private val window = AtomicReference<Window?>(null)

    fun reserve(upperBound: Int): WebCallAdmission {
        require(upperBound >= 0) { "The call estimate cannot be negative." }
        if (upperBound == 0) return WebCallAdmission.Granted

        while (true) {
            val now = clock()
            val previous = window.get()
            val current =
                if (previous == null || !now.isBefore(previous.startedAt.plusSeconds(WINDOW_SECONDS))) {
                    Window(now, 0)
                } else {
                    previous
                }
            if (current.reserved.toLong() + upperBound.toLong() > maxCalls.toLong()) {
                val remaining = Duration.between(now, current.startedAt.plusSeconds(WINDOW_SECONDS)).toMillis()
                val seconds = ((remaining.coerceAtLeast(1) + 999) / 1_000).coerceAtLeast(1)
                return WebCallAdmission.Throttled(seconds)
            }
            if (window.compareAndSet(previous, current.copy(reserved = current.reserved + upperBound))) {
                return WebCallAdmission.Granted
            }
        }
    }

    companion object {
        const val WINDOW_SECONDS: Long = 3_600
    }
}
