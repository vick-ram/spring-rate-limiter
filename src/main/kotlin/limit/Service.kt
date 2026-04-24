package limit

import redis.Redis
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max

interface RateLimitService {
    fun allowRequest(key: String, config: RateLimitConfig): RateLimitResult
    fun resetLimit(key: String, type: RateLimitType)
    fun getCurrentLimit(key: String, type: RateLimitType): Long
    fun getMetrics(key: String, type: RateLimitType): List<RateLimitMetric>
    fun isRateLimited(key: String, type: RateLimitType): Boolean
}

@Service
class RedisRateLimitService(
    private val redis: Redis,
) : RateLimitService {

    companion object {
        private const val TOKEN_BUCKET_KEY = "rate:token:%s"
        private const val FIXED_WINDOW_KEY = "rate:fixed:%s"
        private const val SLIDING_WINDOW_KEY = "rate:sliding:%s"
        private const val LEAKY_BUCKET_KEY = "rate:leaky:%s"
        private const val CONCURRENT_KEY = "rate:concurrent:%s"

        // Lua script for token bucket algorithm
        private const val TOKEN_BUCKET_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refillRate = tonumber(ARGV[2])
            local requestedTokens = tonumber(ARGV[3])
            local now = tonumber(ARGV[4])
            
            local bucket = redis.call('hgetall', key)
            local lastTokens = capacity
            local lastRefill = now
            
            if #bucket > 0 then
                lastTokens = tonumber(bucket[2])
                lastRefill = tonumber(bucket[4])
            end
            
            local elapsed = math.max(0, now - lastRefill)
            local tokensToAdd = elapsed * refillRate
            local currentTokens = math.min(capacity, lastTokens + tokensToAdd)
            
            if currentTokens >= requestedTokens then
                currentTokens = currentTokens - requestedTokens
                redis.call('hmset', key, 'tokens', currentTokens, 'lastRefill', now)
                redis.call('expire', key, math.ceil(capacity / refillRate) * 2)
                return {1, currentTokens, capacity}
            else
                return {0, currentTokens, capacity, math.ceil((requestedTokens - currentTokens) / refillRate)}
            end
        """

        // Lua script for sliding window
        private const val SLIDING_WINDOW_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local windowSize = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            
            redis.call('zremrangebyscore', key, 0, now - windowSize)
            
            local currentCount = redis.call('zcard', key)
            
            if currentCount < capacity then
                redis.call('zadd', key, now, now)
                redis.call('expire', key, windowSize)
                return {1, capacity - currentCount - 1, capacity, 0}
            else
                local oldest = redis.call('zrange', key, 0, 0, 'withscores')[2]
                local retryAfter = math.ceil(oldest + windowSize - now)
                return {0, 0, capacity, retryAfter}
            end
        """
    }

    private val metricsCollector = ConcurrentLinkedQueue<RateLimitMetric>()
    private val concurrentRequests = ConcurrentHashMap<String, Long>()

    override fun allowRequest(key: String, config: RateLimitConfig): RateLimitResult {
        val startTime = System.currentTimeMillis()

        val result = when (config.type) {
            RateLimitType.TOKEN_BUCKET -> checkTokenBucket(key, config)
            RateLimitType.LEAKY_BUCKET -> checkLeakyBucket(key, config)
            RateLimitType.FIXED_WINDOW -> checkFixedWindow(key, config)
            RateLimitType.SLIDING_WINDOW -> checkSlidingWindow(key, config)
            RateLimitType.CONCURRENT_REQUESTS -> checkConcurrentRequests(key, config)
        }

        // Collect metrics
        collectMetrics(key, config.type, result.allowed, result.remainingTokens,
            System.currentTimeMillis() - startTime)

        return result
    }

    override fun resetLimit(key: String, type: RateLimitType) {
        val redisKey = getRedisKey(key, type)
        redis.delete(redisKey)

        if (type == RateLimitType.CONCURRENT_REQUESTS) {
            concurrentRequests.remove(key)
        }
    }

    override fun getCurrentLimit(key: String, type: RateLimitType): Long {
        val redisKey = getRedisKey(key, type)

        return when (type) {
            RateLimitType.TOKEN_BUCKET -> {
                val bucket = redis.get(redisKey, TokenBucket::class.java)
                bucket?.tokens?.toLong() ?: 0
            }
            RateLimitType.FIXED_WINDOW -> {
                redis.get(redisKey, Long::class.java) ?: 0
            }
            RateLimitType.SLIDING_WINDOW -> {
                val now = Instant.now().epochSecond
                val windowStart = now - 60 // Default window
                redis.getSortedSetRange(redisKey, windowStart, now, Long::class.java).size.toLong()
            }
            RateLimitType.CONCURRENT_REQUESTS -> {
                concurrentRequests[key] ?: 0
            }
            else -> 0
        }
    }

    override fun getMetrics(key: String, type: RateLimitType): List<RateLimitMetric> {
        return metricsCollector.filter { it.key == key && it.type == type }.toList()
    }

    override fun isRateLimited(key: String, type: RateLimitType): Boolean {
        val config = RateLimitConfig(
            key = key,
            capacity = 1,
            durationInSeconds = 60,
            type = type
        )
        val result = allowRequest(key, config)
        return !result.allowed
    }

    private fun checkTokenBucket(key: String, config: RateLimitConfig): RateLimitResult {
        val redisKey = TOKEN_BUCKET_KEY.format(key)
        val refillRate = config.capacity.toDouble() / config.durationInSeconds
        val now = Instant.now().epochSecond.toDouble()

        val result = redis.execute(
            DefaultRedisScript(TOKEN_BUCKET_SCRIPT, List::class.java),
            listOf(redisKey),
            config.capacity.toString(),
            refillRate.toString(),
            "1",
            now.toString()
        ) as List<Any>

        val allowed = result[0] == 1L
        val remainingTokens = (result[1] as Number).toLong()
        val capacity = (result[2] as Number).toLong()
        val retryAfter = if (result.size > 3) (result[3] as Number).toLong() else 0

        return RateLimitResult(
            allowed = allowed,
            remainingTokens = remainingTokens,
            resetTimeInSeconds = config.durationInSeconds,
            retryAfterInSeconds = retryAfter,
            limit = config.capacity
        )
    }

    private fun checkLeakyBucket(key: String, config: RateLimitConfig): RateLimitResult {
        val redisKey = LEAKY_BUCKET_KEY.format(key)
        val now = Instant.now()

        val bucket = redis.get(redisKey, LeakyBucket::class.java) ?: LeakyBucket(
            key = redisKey,
            water = 0,
            capacity = config.capacity,
            leakRate = config.capacity / config.durationInSeconds,
            lastLeakTimestamp = now
        )

        // Leak water based on time elapsed
        val elapsedSeconds = Duration.between(bucket.lastLeakTimestamp, now).seconds
        val leakedWater = elapsedSeconds * bucket.leakRate
        bucket.water = max(0, bucket.water - leakedWater)
        bucket.lastLeakTimestamp = now

        val allowed = bucket.water < bucket.capacity
        if (allowed) {
            bucket.water++
            redis.set(redisKey, bucket, Duration.ofSeconds(config.durationInSeconds * 2))
        }

        return RateLimitResult(
            allowed = allowed,
            remainingTokens = bucket.capacity - bucket.water,
            resetTimeInSeconds = config.durationInSeconds,
            retryAfterInSeconds = if (!allowed) bucket.leakRate else 0,
            limit = config.capacity
        )
    }

    private fun checkFixedWindow(key: String, config: RateLimitConfig): RateLimitResult {
        val redisKey = FIXED_WINDOW_KEY.format(key)
        val now = Instant.now().epochSecond
        val windowKey = "$redisKey:${now / config.durationInSeconds}"

        val currentCount = redis.increment(windowKey)
        if (currentCount == 1L) {
            redis.expire(windowKey, Duration.ofSeconds(config.durationInSeconds))
        }

        val allowed = currentCount <= config.capacity
        val remaining = if (allowed) config.capacity - currentCount else 0

        return RateLimitResult(
            allowed = allowed,
            remainingTokens = remaining,
            resetTimeInSeconds = config.durationInSeconds - (now % config.durationInSeconds),
            retryAfterInSeconds = if (!allowed) config.durationInSeconds - (now % config.durationInSeconds) else 0,
            limit = config.capacity
        )
    }

    private fun checkSlidingWindow(key: String, config: RateLimitConfig): RateLimitResult {
        val redisKey = SLIDING_WINDOW_KEY.format(key)
        val now = Instant.now().toEpochMilli().toDouble()

        val result = redis.execute(
            DefaultRedisScript(SLIDING_WINDOW_SCRIPT, List::class.java),
            listOf(redisKey),
            config.capacity.toString(),
            (config.durationInSeconds * 1000).toString(),
            now.toString()
        ) as List<Any>

        val allowed = result[0] == 1L
        val remaining = (result[1] as Number).toLong()
        val limit = (result[2] as Number).toLong()
        val retryAfter = if (result.size > 3) (result[3] as Number).toLong() else 0

        return RateLimitResult(
            allowed = allowed,
            remainingTokens = remaining,
            resetTimeInSeconds = config.durationInSeconds,
            retryAfterInSeconds = retryAfter / 1000,
            limit = limit
        )
    }

    private fun checkConcurrentRequests(key: String, config: RateLimitConfig): RateLimitResult {
        val current = concurrentRequests.compute(key) { _, value ->
            (value ?: 0) + 1
        } ?: 1

        val allowed = current <= config.capacity

        if (!allowed) {
            concurrentRequests.computeIfPresent(key) { _, value -> value - 1 }
        }

        return RateLimitResult(
            allowed = allowed,
            remainingTokens = config.capacity - current + (if (allowed) 1 else 0),
            resetTimeInSeconds = config.durationInSeconds,
            limit = config.capacity
        )
    }

    private fun releaseConcurrentRequest(key: String) {
        concurrentRequests.computeIfPresent(key) { _, value ->
            if (value > 0) value - 1 else 0
        }
    }

    private fun getRedisKey(key: String, type: RateLimitType): String {
        return when (type) {
            RateLimitType.TOKEN_BUCKET -> TOKEN_BUCKET_KEY.format(key)
            RateLimitType.FIXED_WINDOW -> FIXED_WINDOW_KEY.format(key)
            RateLimitType.SLIDING_WINDOW -> SLIDING_WINDOW_KEY.format(key)
            RateLimitType.LEAKY_BUCKET -> LEAKY_BUCKET_KEY.format(key)
            RateLimitType.CONCURRENT_REQUESTS -> CONCURRENT_KEY.format(key)
        }
    }

    private fun collectMetrics(key: String, type: RateLimitType, allowed: Boolean,
                               remaining: Long, processingTimeMs: Long) {
        val metric = RateLimitMetric(
            key = key,
            type = type,
            timestamp = Instant.now(),
            allowed = allowed,
            remaining = remaining,
            processingTimeMs = processingTimeMs
        )

        metricsCollector.add(metric)
        // Keep only last 1000 metrics per key to avoid memory issues
        while (metricsCollector.size > 1000) {
            metricsCollector.poll()
        }

        // Optionally store metrics in Redis for distributed collection
        val metricsKey = "rate:metrics:$key:${type.name}"
        redis.pushToList(metricsKey, metric)
        redis.trimList(metricsKey, -1000, -1)
    }
}