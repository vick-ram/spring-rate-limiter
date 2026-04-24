package limit

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class RateLimitConfig(
    val key: String,
    val capacity: Long,
    val durationInSeconds: Long,
    val type: RateLimitType,
    val fallbackMethod: String = ""
)

data class TokenBucket(
    val key: String,
    var tokens: Double,
    val capacity: Long,
    val refillRate: Double, // tokens per second
    var lastRefillTimestamp: Instant
)

data class LeakyBucket(
    val key: String,
    var water: Long,
    val capacity: Long,
    val leakRate: Long, // requests per second
    var lastLeakTimestamp: Instant
)

data class FixedWindow(
    val key: String,
    var count: Long,
    val capacity: Long,
    val windowStart: Instant,
    val windowDurationInSeconds: Long
)

data class SlidingWindow(
    val key: String,
    val requests: MutableList<Instant>,
    val capacity: Long,
    val windowDurationInSeconds: Long
)

data class RateLimitResult(
    val allowed: Boolean,
    val remainingTokens: Long,
    val resetTimeInSeconds: Long,
    val retryAfterInSeconds: Long = 0,
    val limit: Long
)

data class RateLimitMetric(
    val key: String,
    val type: RateLimitType,
    val timestamp: Instant,
    val allowed: Boolean,
    val remaining: Long,
    val processingTimeMs: Long
)

@Configuration
class RateLimitConfiguration(
    private val rateLimitInterceptor: RateLimitInterceptor
): WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(rateLimitInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns("/api/public/**", "/api/health/**")
    }

    @Bean
    fun rateLimitHealthChecker(): RateLimitHealthChecker {
        return RateLimitHealthChecker()
    }
}

@Component
class RateLimitHealthChecker {

    private val healthStatus = ConcurrentHashMap<String, Boolean>()

    fun checkRateLimitHealth(serviceName: String, rateLimitService: RedisRateLimitService): Boolean {
        try {
            val testKey = "health:check:$serviceName:${Instant.now().epochSecond}"
            val config = RateLimitConfig(
                key = testKey,
                capacity = 1,
                durationInSeconds = 1,
                type = RateLimitType.TOKEN_BUCKET
            )

            val result = rateLimitService.allowRequest(testKey, config)
            healthStatus[serviceName] = result.allowed
            return result.allowed
        } catch (e: Exception) {
            healthStatus[serviceName] = false
            return false
        }
    }

    fun getHealthStatus(): Map<String, Boolean> {
        return healthStatus.toMap()
    }
}