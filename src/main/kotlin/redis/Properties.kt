package redis

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app.redis")
data class RedisProperties(
    val host: String = "localhost",
    val port: Int = 6379,
    val password: String? = null,
    val database: Int = 0,
    val timeout: Duration = Duration.ofSeconds(60),
    val pool: PoolProperties = PoolProperties()
) {
    data class PoolProperties(
        val maxActive: Int = 8,
        val maxIdle: Int = 8,
        val minIdle: Int = 0,
        val maxWait: Duration = Duration.ofMillis(-1)
    )
}