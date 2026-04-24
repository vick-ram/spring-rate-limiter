package com.vinteque.turent.rateLimit

import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import limit.RateLimit
import limit.RateLimitAspect
import limit.RateLimitExceededException
import limit.RateLimitResult
import limit.RateLimitService
import org.aspectj.lang.ProceedingJoinPoint
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.Test
import kotlin.test.assertEquals

@ExtendWith(MockKExtension::class)
class RateLimitAspectTest {

    @MockK
    lateinit var rateLimitService: RateLimitService

    @MockK
    lateinit var joinPoint: ProceedingJoinPoint

    lateinit var aspect: RateLimitAspect

    @BeforeEach
    fun setup() {
        aspect = RateLimitAspect(rateLimitService)
    }

    @Test
    fun `should allow request when rate limit not exceeded`() {

        val annotation = RateLimit(
            key = "test",
            capacity = 10,
            duration = 60
        )

        every { rateLimitService.allowRequest(any(), any()) } returns
                RateLimitResult(
                    allowed = true,
                    remainingTokens = 9,
                    resetTimeInSeconds = 60,
                    limit = 10
                )

        every { joinPoint.proceed() } returns "success"

        val result = aspect.rateLimit(joinPoint, annotation)

        assertEquals("success", result)
    }

    @Test
    fun `should throw exception when rate limit exceeded`() {

        val annotation = RateLimit(
            key = "test",
            capacity = 10,
            duration = 60
        )

        every { rateLimitService.allowRequest(any(), any()) } returns
                RateLimitResult(
                    allowed = false,
                    remainingTokens = 0,
                    resetTimeInSeconds = 60,
                    retryAfterInSeconds = 5,
                    limit = 10
                )

        assertThrows<RateLimitExceededException> {
            aspect.rateLimit(joinPoint, annotation)
        }
    }
}