package com.vinteque.turent.rateLimit

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import limit.RateLimitInterceptor
import limit.RateLimitResult
import limit.RedisRateLimitService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Nested
class RateLimitInterceptorTest {

    private lateinit var rateLimitService: RedisRateLimitService
    private lateinit var request: HttpServletRequest
    private lateinit var response: HttpServletResponse
    private lateinit var handler: Any
    private lateinit var interceptor: RateLimitInterceptor

    @BeforeEach
    fun setUp() {
        rateLimitService = mockk()
        request = mockk()
        response = mockk(relaxed = true)
        handler = Any()
        interceptor = RateLimitInterceptor(rateLimitService)
    }

    @Test
    fun `should allow request when global and endpoint limits not exceeded`() {
        // Given
        val clientIp = "192.168.1.1"
        val endpoint = "/api/test"

        every { request.getHeader("X-Forwarded-For") } returns clientIp
        every { request.requestURI } returns endpoint

        val globalResult = RateLimitResult(true, 999, 3600, 0, 1000)
        val endpointResult = RateLimitResult(true, 99, 60, 0, 100)

        every { rateLimitService.allowRequest(match { it.contains("global") }, any()) } returns globalResult
        every { rateLimitService.allowRequest(match { it.contains(endpoint) }, any()) } returns endpointResult
        every { response.setHeader(any(), any()) } returns Unit

        // When
        val result = interceptor.preHandle(request, response, handler)

        // Then
        assertTrue(result)
        verify(exactly = 1) { response.setHeader("X-RateLimit-Limit", "100") }
        verify(exactly = 1) { response.setHeader("X-RateLimit-Remaining", "99") }
        verify(exactly = 1) { response.setHeader("X-RateLimit-Reset", "60") }
    }

    @Test
    fun `should reject request when global limit exceeded`() {
        // Given
        val clientIp = "192.168.1.1"
        val endpoint = "/api/test"

        every { request.getHeader("X-Forwarded-For") } returns clientIp
        every { request.requestURI } returns endpoint

        val globalResult = RateLimitResult(false, 0, 3600, 3600, 1000)

        every { rateLimitService.allowRequest(match { it.contains("global") }, any()) } returns globalResult

        // When
        val result = interceptor.preHandle(request, response, handler)

        // Then
        assertFalse(result)
        verify { response.status = 506 }
        verify(exactly = 1) { response.setHeader("X-RateLimit-Limit", "1000") }
        verify(exactly = 1) { response.setHeader("X-RateLimit-Remaining", "0") }
        verify(exactly = 1) { response.setHeader("Retry-After", "3600") }
    }

    @Test
    fun `should reject request when endpoint limit exceeded`() {
        // Given
        val clientIp = "192.168.1.1"
        val endpoint = "/api/test"

        every { request.getHeader("X-Forwarded-For") } returns clientIp
        every { request.requestURI } returns endpoint

        val globalResult = RateLimitResult(true, 999, 3600, 0, 1000)
        val endpointResult = RateLimitResult(false, 0, 60, 30, 100)

        every { rateLimitService.allowRequest(match { it.contains("global") }, any()) } returns globalResult
        every { rateLimitService.allowRequest(match { it.contains(endpoint) }, any()) } returns endpointResult

        // When
        val result = interceptor.preHandle(request, response, handler)

        // Then
        assertFalse(result)
        verify { response.status = 506 }
        verify(exactly = 1) { response.setHeader("X-RateLimit-Limit", "100") }
        verify(exactly = 1) { response.setHeader("X-RateLimit-Remaining", "0") }
        verify(exactly = 1) { response.setHeader("Retry-After", "30") }
    }

    @Test
    fun `should extract client IP from X-Forwarded-For header`() {
        // Given
        val forwardedIp = "10.0.0.1, 192.168.1.1, 172.16.0.1"
        val endpoint = "/api/test"

        every { request.getHeader("X-Forwarded-For") } returns forwardedIp
        every { request.requestURI } returns endpoint

        val globalResult = RateLimitResult(true, 999, 3600, 0, 1000)
        val endpointResult = RateLimitResult(true, 99, 60, 0, 100)

        every { rateLimitService.allowRequest(any(), any()) } returns globalResult andThen endpointResult

        // When
        interceptor.preHandle(request, response, handler)

        // Then
        verify(exactly = 1) { rateLimitService.allowRequest("global:10.0.0.1", any()) }
        verify(exactly = 1) { rateLimitService.allowRequest("/api/test:10.0.0.1", any()) }
    }

    @Test
    fun `should use remote address when X-Forwarded-For is not present`() {
        // Given
        val remoteAddr = "192.168.1.100"
        val endpoint = "/api/test"

        every { request.getHeader("X-Forwarded-For") } returns null
        every { request.remoteAddr } returns remoteAddr
        every { request.requestURI } returns endpoint

        val globalResult = RateLimitResult(true, 999, 3600, 0, 1000)
        val endpointResult = RateLimitResult(true, 99, 60, 0, 100)

        every { rateLimitService.allowRequest(any(), any()) } returns globalResult andThen endpointResult

        // When
        interceptor.preHandle(request, response, handler)

        // Then
        verify(exactly = 1) { rateLimitService.allowRequest("global:$remoteAddr", any()) }
        verify(exactly = 1) { rateLimitService.allowRequest("$endpoint:$remoteAddr", any()) }
    }
}
