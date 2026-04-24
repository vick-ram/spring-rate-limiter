package limit

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.stereotype.Component

// Rate Limit Aspect for Annotation-based

@Aspect
@Component
class RateLimitAspect(
    private val rateLimitService: RateLimitService
) {
    @Around("@annotation(rateLimit)")
    fun rateLimit(joinPoint: ProceedingJoinPoint, rateLimit: RateLimit): Any? {
        return handleRateLimit(joinPoint, rateLimit)
    }

    @Around("@annotation(rateLimitGroup)")
    fun rateLimitGroup(joinPoint: ProceedingJoinPoint, rateLimitGroup: RateLimitGroup): Any? {
        for (limit in rateLimitGroup.limits) {
            val result = checkRateLimit(joinPoint, limit)
            if (!result.allowed) {
                return handleRateLimitExceeded(joinPoint, limit, result)
            }
        }
        return joinPoint.proceed()
    }

    private fun handleRateLimit(joinPoint: ProceedingJoinPoint, rateLimit: RateLimit): Any? {
        val result = checkRateLimit(joinPoint, rateLimit)

        return if (result.allowed) {
            try {
                joinPoint.proceed()
            } finally {
                if (rateLimit.type == RateLimitType.CONCURRENT_REQUESTS) {
                    releaseConcurrentRequest(joinPoint, rateLimit)
                }
            }
        } else {
            handleRateLimitExceeded(joinPoint, rateLimit, result)
        }
    }

    private fun checkRateLimit(joinPoint: ProceedingJoinPoint, rateLimit: RateLimit): RateLimitResult {
        val key = resolveKey(joinPoint, rateLimit.key)
        val config = RateLimitConfig(
            key = key,
            capacity = rateLimit.capacity,
            durationInSeconds = convertToSeconds(rateLimit.duration, rateLimit.unit),
            type = rateLimit.type,
            fallbackMethod = rateLimit.fallbackMethod
        )
        return rateLimitService.allowRequest(key, config)
    }

    private fun resolveKey(joinPoint: ProceedingJoinPoint, keyExpression: String): String {
        if (keyExpression.isEmpty()) {
            val signature = joinPoint.signature as MethodSignature
            val method = signature.method
            val className = method.declaringClass.simpleName
            val methodName = method.name

            val args = joinPoint.args
            val argsKey = args.joinToString(":") { arg ->
                when (arg) {
                    is String -> arg
                    is Number -> arg.toString()
                    else -> arg?.hashCode().toString()
                }
            }
            return "$className:$methodName:$argsKey"
        }
        return parseExpression(keyExpression, joinPoint)
    }

    private fun parseExpression(expression: String, joinPoint: ProceedingJoinPoint): String {
        return when {
            expression.startsWith("#id") -> {
                val args = joinPoint.args
                val id = args.firstOrNull()?.toString() ?: "unknown"
                expression.replace("#id", id)
            }
            else -> expression
        }
    }

    private fun convertToSeconds(duration: Long, unit: TimeUnit): Long {
        return when (unit) {
            TimeUnit.SECONDS -> duration
            TimeUnit.MINUTES -> duration * 60
            TimeUnit.HOURS -> duration * 3600
            TimeUnit.DAYS -> duration * 86400
        }
    }

    private fun handleRateLimitExceeded(
        joinPoint: ProceedingJoinPoint,
        rateLimit: RateLimit,
        result: RateLimitResult
    ): Any {
        if (rateLimit.fallbackMethod.isNotEmpty()) {
            return invokeFallbackMethod(joinPoint, rateLimit.fallbackMethod, result)
        }

        throw RateLimitExceededException(
            message = "Rate limit exceeded. Try again in ${result.retryAfterInSeconds} seconds",
            retryAfterSeconds = result.retryAfterInSeconds,
            limit = result.limit,
            remaining = result.remainingTokens
        )
    }

    private fun invokeFallbackMethod(
        joinPoint: ProceedingJoinPoint,
        fallbackMethod: String,
        result: RateLimitResult
    ): Any {
        val methodSignature = joinPoint.signature as MethodSignature
        val targetClass = joinPoint.target.javaClass

        try {
            val method = targetClass.getMethod(fallbackMethod, methodSignature.parameterTypes as Class<*>?)
            return method.invoke(joinPoint.target, *joinPoint.args)
        } catch (e: Exception) {
            throw RateLimitExceededException(
                message = "Rate limit exceeded and fallback method failed: ${e.message}",
                retryAfterSeconds = result.retryAfterInSeconds,
                limit = result.limit,
                remaining = result.remainingTokens
            )
        }
    }

    private fun releaseConcurrentRequest(joinPoint: ProceedingJoinPoint, rateLimit: RateLimit) {
        val key = resolveKey(joinPoint, rateLimit.key)
        rateLimitService.resetLimit(key, RateLimitType.CONCURRENT_REQUESTS)
    }
}

class RateLimitExceededException(
    message: String, val retryAfterSeconds: Long,
    val limit: Long,
    val remaining: Long
) : RuntimeException(message)