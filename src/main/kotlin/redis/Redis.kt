package redis

import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.Duration

interface Redis {
    fun set(key: String, value: Any)
    fun set(key: String, value: Any, timeout: Duration)
    fun <T : Any> execute(script: RedisScript<T>, keys: List<String>, vararg args: Any): T?
    fun <T> get(key: String, clazz: Class<T>): T?
    fun <T> get(key: String, typeRef: TypeReference<T>): T?
    fun delete(key: String): Boolean
    fun delete(vararg keys: String): Long
    fun hasKey(key: String): Boolean
    fun expire(key: String, timeout: Duration): Boolean
    fun increment(key: String): Long
    fun <T> getSortedSetRange(key: String, start: Long, end: Long, clazz: Class<T>): Set<T>
    fun leftPush(key: String, value: Any): Long?
    fun pushToList(key: String, vararg values: Any): Long
    fun trimList(key: String, start: Long, end: Long)
    fun range(key: String, start: Long, end: Long): List<Any>
}

@Service
class RedisImpl(
    private val redisTemplate: RedisTemplate<String, Any>,
    private val objectMapper: ObjectMapper
) : Redis {
    override fun set(key: String, value: Any) {
        redisTemplate.opsForValue().set(key, value)
    }

    override fun set(key: String, value: Any, timeout: Duration) {
        redisTemplate.opsForValue().set(key, value, timeout)
    }

    override fun <T : Any> execute(script: RedisScript<T>, keys: List<String>, vararg args: Any): T? {
        return try {
            redisTemplate.execute(script, keys, *args)
        } catch (e: Exception) {
            // Log error appropriately
            null
        }
    }

    override fun <T> get(key: String, clazz: Class<T>): T? {
        val result = redisTemplate.opsForValue().get(key) ?: return null
        return convertValue(result, clazz)
    }

    override fun <T> get(key: String, typeRef: TypeReference<T>): T? {
        val result = redisTemplate.opsForValue().get(key) ?: return null
        return convertValue(result, typeRef)
    }

    override fun delete(key: String): Boolean {
        return redisTemplate.delete(key)
    }

    override fun delete(vararg keys: String): Long {
        return redisTemplate.delete(keys.toList())
    }

    override fun hasKey(key: String): Boolean {
        return redisTemplate.hasKey(key)
    }

    override fun expire(key: String, timeout: Duration): Boolean {
        return redisTemplate.expire(key, timeout)
    }

    override fun increment(key: String): Long {
        return redisTemplate.opsForValue().increment(key)
    }

    override fun <T> getSortedSetRange(key: String, start: Long, end: Long, clazz: Class<T>): Set<T> {
        val results = redisTemplate.opsForZSet().range(key, start, end) ?: return emptySet()
        return results.mapNotNull { convertValue(it, clazz) }.toSet()
    }

    override fun pushToList(key: String, vararg values: Any): Long {
        return redisTemplate.opsForList().rightPushAll(key, *values)
    }

    override fun trimList(key: String, start: Long, end: Long) {
        redisTemplate.opsForList().trim(key, start, end)
    }

    override fun leftPush(key: String, value: Any): Long? {
        return redisTemplate.opsForList().leftPush(key, value)
    }

    override fun range(key: String, start: Long, end: Long): List<Any> {
        return redisTemplate.opsForList().range(key, start, end)
    }

    private fun <T> convertValue(value: Any, clazz: Class<T>): T {
        return if (clazz.isInstance(value)) {
            clazz.cast(value)
        } else {
            objectMapper.convertValue(value, clazz)
        }
    }

    private fun <T> convertValue(value: Any, typeRef: TypeReference<T>): T {
        return objectMapper.convertValue(value, typeRef)
    }
}