package com.vinteque.turent.rateLimit

import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.runs
import limit.RateLimitConfig
import limit.RateLimitType
import limit.RedisRateLimitService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.data.redis.core.script.DefaultRedisScript
import redis.Redis
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(MockKExtension::class)
class RedisRateLimitServiceTest {

    @MockK
    lateinit var redis: Redis

    lateinit var service: RedisRateLimitService

    @BeforeEach
    fun setup() {
        service = RedisRateLimitService(redis)
        every { redis.pushToList(any(), any()) } returns 1L
        every { redis.trimList(any(), any(), any()) } just runs
    }

    @Test
    fun `should allow request when token bucket has tokens`() {

        val config = RateLimitConfig(
            key = "test",
            capacity = 10,
            durationInSeconds = 60,
            type = RateLimitType.TOKEN_BUCKET
        )

        every {
            redis.execute(any<DefaultRedisScript<List<Any>>>(), any(), any(), any(), any(), any())
        } returns listOf(1L, 9L, 10L)

        val result = service.allowRequest("test", config)

        assertTrue(result.allowed)
        assertEquals(9, result.remainingTokens)
        assertEquals(10, result.limit)
    }

    @Test
    fun `should deny request when token bucket empty`() {

        val config = RateLimitConfig(
            key = "test",
            capacity = 10,
            durationInSeconds = 60,
            type = RateLimitType.TOKEN_BUCKET
        )

        every {
            redis.execute(any<DefaultRedisScript<List<Any>>>(), any(), any(), any(), any(), any())
        } returns listOf(0L, 0L, 10L, 5L)

        val result = service.allowRequest("test", config)

        assertFalse(result.allowed)
        assertEquals(0, result.remainingTokens)
        assertEquals(5, result.retryAfterInSeconds)
    }

    @Test
    fun `should limit concurrent requests`() {

        val config = RateLimitConfig(
            key = "concurrent-test",
            capacity = 2,
            durationInSeconds = 60,
            type = RateLimitType.CONCURRENT_REQUESTS
        )

        every {
            redis.execute(any<DefaultRedisScript<List<Any>>>(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            listOf(1L, 1L, 2L),
            listOf(1L, 0L, 2L),
            listOf(0L, 0L, 2L)
        )

        val r1 = service.allowRequest("concurrent-test", config)
        val r2 = service.allowRequest("concurrent-test", config)
        val r3 = service.allowRequest("concurrent-test", config)

        assertTrue(r1.allowed)
        assertTrue(r2.allowed)
        assertFalse(r3.allowed)
    }

    @Test
    fun `should enforce concurrent limits under load`() {

        val config = RateLimitConfig(
            key = "load-test",
            capacity = 5,
            durationInSeconds = 60,
            type = RateLimitType.CONCURRENT_REQUESTS
        )

        val mockResponses = (1..5).map { listOf(1L, (5 - it).toLong(), 5L) } +
                (6..20).map { listOf(0L, 0L, 5L) }

        every {
            redis.execute(any<DefaultRedisScript<List<Any>>>(), any(), any(), any(), any(), any())
        } returnsMany mockResponses

        val results = (1..20).map {
            service.allowRequest("load-test", config)
        }

        val allowed = results.count { it.allowed }

        assertEquals(5, allowed)
    }
}