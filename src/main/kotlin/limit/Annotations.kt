package limit

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RateLimit(
    val key: String =  "",
    val capacity: Long = 10,
    val duration: Long = 60,
    val unit: TimeUnit = TimeUnit.SECONDS,
    val type: RateLimitType = RateLimitType.TOKEN_BUCKET,
    val fallbackMethod: String = ""
)

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RateLimitGroup(vararg val limits: RateLimit)

enum class RateLimitType {
    TOKEN_BUCKET,
    LEAKY_BUCKET,
    FIXED_WINDOW,
    SLIDING_WINDOW,
    CONCURRENT_REQUESTS
}

enum class TimeUnit {
    SECONDS, MINUTES, HOURS, DAYS
}