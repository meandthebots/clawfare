package com.clawfare

import com.clawfare.web.HourlyWebCallBudget
import com.clawfare.web.WebCallAdmission
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HourlyWebCallBudgetTest {
    @Test
    fun searchAndSellerLookupsShareOnePessimisticHourlyAllowance() {
        var now = Instant.parse("2026-11-17T19:00:00Z")
        val budget = HourlyWebCallBudget(21) { now }

        assertEquals(WebCallAdmission.Granted, budget.reserve(7))
        assertEquals(WebCallAdmission.Granted, budget.reserve(14))
        assertEquals(WebCallAdmission.Throttled(3_600), budget.reserve(1))

        now = now.plusSeconds(3_599)
        assertEquals(WebCallAdmission.Throttled(1), budget.reserve(1))
        now = now.plusSeconds(1)
        assertEquals(WebCallAdmission.Granted, budget.reserve(14))
        assertEquals(WebCallAdmission.Throttled(3_600), budget.reserve(8))
    }

    @Test
    fun cacheOnlyAndEstimatesDoNotConsumeNetworkCallBudget() {
        val budget = HourlyWebCallBudget(0) { Instant.parse("2026-11-17T19:00:00Z") }

        repeat(20) { assertEquals(WebCallAdmission.Granted, budget.reserve(0)) }
        assertEquals(WebCallAdmission.Throttled(3_600), budget.reserve(7))
        assertEquals(WebCallAdmission.Throttled(3_600), budget.reserve(1))
        assertEquals(WebCallAdmission.Granted, budget.reserve(0))
    }

    @Test
    fun failedOrUnfinishedReservationsStayChargedUntilWindowExpires() {
        val budget = HourlyWebCallBudget(7) { Instant.parse("2026-11-17T19:00:00Z") }

        // No refund API: reserving prior to dispatch protects against
        // partial failures and lost acknowledgements within this process.
        assertEquals(WebCallAdmission.Granted, budget.reserve(7))
        assertEquals(WebCallAdmission.Throttled(3_600), budget.reserve(7))
    }

    @Test
    fun simultaneousReservationsNeverOverbookTheHourlyLimit() {
        val budget = HourlyWebCallBudget(18) { Instant.parse("2026-11-17T19:00:00Z") }
        val ready = CountDownLatch(16)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(16)
        try {
            val attempts =
                (1..16).map {
                    executor.submit<WebCallAdmission> {
                        ready.countDown()
                        start.await()
                        budget.reserve(3)
                    }
                }
            ready.await()
            start.countDown()
            assertEquals(6, attempts.count { it.get() == WebCallAdmission.Granted })
            assertEquals(10, attempts.count { it.get() is WebCallAdmission.Throttled })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun rejectsNegativeEstimatesAndAvoidsIntegerOverflow() {
        val budget = HourlyWebCallBudget(100) { Instant.parse("2026-11-17T19:00:00Z") }

        assertFailsWith<IllegalArgumentException> { HourlyWebCallBudget(-1) }
        assertFailsWith<IllegalArgumentException> { budget.reserve(-1) }
        val huge = assertIs<WebCallAdmission.Throttled>(budget.reserve(Int.MAX_VALUE))
        assertTrue(huge.retryAfterSeconds > 0)
        assertEquals(WebCallAdmission.Granted, budget.reserve(100))
        assertIs<WebCallAdmission.Throttled>(budget.reserve(1))
    }
}
