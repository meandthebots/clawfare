package com.clawfare

import com.clawfare.web.LocalAccess
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalAccessTest {
    @Test
    fun hostAndOriginMustMatchLoopbackAddressAndPort() {
        val access = LocalAccess()
        assertTrue(access.acceptsOrigin("127.0.0.1:8080", "http://127.0.0.1:8080"))
        assertTrue(access.acceptsOrigin("localhost:8080", null))
        assertFalse(access.acceptsOrigin("outside.example", null))
        assertFalse(access.acceptsOrigin("localhost:8080", "https://localhost:8080"))
        assertFalse(access.acceptsOrigin("localhost:8080", "http://localhost:8081"))
        assertFalse(access.acceptsOrigin("localhost:8080", "http://outside.example"))
    }

    @Test
    fun oneShotConsentBindsTheExactRequestAndExpires() {
        var now = Instant.parse("2026-11-16T01:00:00Z")
        val access = LocalAccess { now }
        val first = access.issueConsent("search", """{"adults":2}""")
        assertFalse(access.consumeConsent(first, "search", """{"adults":1}"""))
        assertFalse(access.consumeConsent(first, "search", """{"adults":2}"""))
        val second = access.issueConsent("search", """{"adults":2}""")
        assertFalse(access.consumeConsent(second, "vendors/id", """{"adults":2}"""))
        val third = access.issueConsent("search", """{"adults":2}""")
        assertTrue(access.consumeConsent(third, "search", """{"adults":2}"""))
        assertFalse(access.consumeConsent(third, "search", """{"adults":2}"""))
        val expired = access.issueConsent("search", "any")
        now = now.plusSeconds(601)
        assertFalse(access.consumeConsent(expired, "search", "any"))
    }

    @Test
    fun quotaInspectionThrottleHonorsCooldown() {
        var now = Instant.parse("2026-11-17T05:00:00Z")
        val access = LocalAccess { now }

        assertTrue(access.tryBeginQuotaCheck())
        assertFalse(access.tryBeginQuotaCheck())
        now = now.plusSeconds(LocalAccess.QUOTA_CHECK_COOLDOWN_SECONDS - 1)
        assertFalse(access.tryBeginQuotaCheck())
        now = now.plusSeconds(1)
        assertTrue(access.tryBeginQuotaCheck())
    }

    @Test
    fun concurrentQuotaChecksAllowOnlyOne() {
        val access = LocalAccess { Instant.parse("2026-11-17T05:00:00Z") }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val attempts =
                (1..16).map {
                    pool.submit<Boolean> {
                        start.await()
                        access.tryBeginQuotaCheck()
                    }
                }
            start.countDown()
            assertEquals(1, attempts.count { it.get() })
        } finally {
            pool.shutdownNow()
        }
    }
}
