package com.vinteque.turent.rateLimit

import io.mockk.every
import io.mockk.mockk
import limit.RateLimitConfig
import limit.RateLimitHealthChecker
import limit.RateLimitResult
import limit.RedisRateLimitService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Nested
class RateLimitHealthCheckerTest {

    private lateinit var rateLimitService: RedisRateLimitService
    private lateinit var healthChecker: RateLimitHealthChecker

    @BeforeEach
    fun setUp() {
        rateLimitService = mockk()
        healthChecker = RateLimitHealthChecker()
    }

    @Test
    fun `should return true when rate limit service is healthy`() {
        // Given
        val serviceName = "test-service"
        val result = RateLimitResult(true, 0, 1, 0, 1)
        every { rateLimitService.allowRequest(any(), any<RateLimitConfig>()) } returns result

        // When
        val isHealthy = healthChecker.checkRateLimitHealth(serviceName, rateLimitService)

        // Then
        assertTrue(isHealthy)
        assertTrue(healthChecker.getHealthStatus()[serviceName] == true)
    }

    @Test
    fun `should return false when rate limit service is unhealthy`() {
        // Given
        val serviceName = "test-service"
        every { rateLimitService.allowRequest(any(), any<RateLimitConfig>()) } throws RuntimeException("Redis connection failed")

        // When
        val isHealthy = healthChecker.checkRateLimitHealth(serviceName, rateLimitService)

        // Then
        assertFalse(isHealthy)
        assertTrue(healthChecker.getHealthStatus()[serviceName] == false)
    }

    @Test
    fun `should maintain health status for multiple services`() {
        // Given
        val service1 = "service1"
        val service2 = "service2"
        val result1 = RateLimitResult(true, 0, 1, 0, 1)
        val result2 = RateLimitResult(false, 0, 1, 0, 1)

        every { rateLimitService.allowRequest(any(), any<RateLimitConfig>()) } returns result1 andThen result2

        // When
        healthChecker.checkRateLimitHealth(service1, rateLimitService)
        healthChecker.checkRateLimitHealth(service2, rateLimitService)

        // Then
        val status = healthChecker.getHealthStatus()
        assertEquals(true, status[service1])
        assertEquals(false, status[service2])
    }
}