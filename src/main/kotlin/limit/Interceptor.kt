package limit

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class RateLimitInterceptor(
    private val rateLimitService: RedisRateLimitService
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val clientIp = getClientIp(request)
        val endpoint = request.requestURI

        // Apply global rate limits
        val globalConfig = RateLimitConfig(
            key = "global:$clientIp",
            capacity = 1000,
            durationInSeconds = 3600,
            type = RateLimitType.SLIDING_WINDOW
        )

        val globalResult = rateLimitService.allowRequest(globalConfig.key, globalConfig)

        if (!globalResult.allowed) {
            sendRateLimitResponse(response, globalResult)
            return false
        }

        // Apply endpoint-specific rate limits
        val endpointConfig = RateLimitConfig(
            key = "$endpoint:$clientIp",
            capacity = 100,
            durationInSeconds = 60,
            type = RateLimitType.TOKEN_BUCKET
        )

        val endpointResult = rateLimitService.allowRequest(endpointConfig.key, endpointConfig)

        if (!endpointResult.allowed) {
            sendRateLimitResponse(response, endpointResult)
            return false
        }

        // Add rate limit headers
        addRateLimitHeaders(response, endpointResult)

        return true
    }

    private fun getClientIp(request: HttpServletRequest): String {
        val xForwardedFor = request.getHeader("X-Forwarded-For")
        return if (!xForwardedFor.isNullOrBlank()) {
            xForwardedFor.split(",").first().trim()
        } else {
            request.remoteAddr
        }
    }

    private fun sendRateLimitResponse(response: HttpServletResponse, result: RateLimitResult) {
        response.status = 506
        response.setHeader("X-RateLimit-Limit", result.limit.toString())
        response.setHeader("X-RateLimit-Remaining", result.remainingTokens.toString())
        response.setHeader("X-RateLimit-Reset", result.resetTimeInSeconds.toString())
        response.setHeader("Retry-After", result.retryAfterInSeconds.toString())

        response.writer.write("Rate limit exceeded. Try again in ${result.retryAfterInSeconds} seconds")
    }

    private fun addRateLimitHeaders(response: HttpServletResponse, result: RateLimitResult) {
        response.setHeader("X-RateLimit-Limit", result.limit.toString())
        response.setHeader("X-RateLimit-Remaining", result.remainingTokens.toString())
        response.setHeader("X-RateLimit-Reset", result.resetTimeInSeconds.toString())
    }
}
