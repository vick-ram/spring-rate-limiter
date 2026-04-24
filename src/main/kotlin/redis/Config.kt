package redis

import io.lettuce.core.api.StatefulConnection
import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

@Configuration
@EnableCaching
@EnableConfigurationProperties(RedisProperties::class)
class RedisConfig {
    @Bean
    fun connectionFactory(properties: RedisProperties): LettuceConnectionFactory {
        val config = RedisStandaloneConfiguration().apply {
            hostName = properties.host
            port = properties.port
            password = properties.password?.let { RedisPassword.of(it) } ?: RedisPassword.none()
            database = properties.database
        }
        val poolConfig = GenericObjectPoolConfig<StatefulConnection<*, *>>().apply {
            maxTotal = properties.pool.maxActive
            maxIdle = properties.pool.maxIdle
            minIdle = properties.pool.minIdle
            setMaxWait(properties.pool.maxWait)
            testOnBorrow = true
            testOnReturn = true
            testWhileIdle = true
        }
        val clientConfig = LettucePoolingClientConfiguration.builder()
            .poolConfig(poolConfig)
            .commandTimeout(properties.timeout)
            .build()
        return LettuceConnectionFactory(config, clientConfig)
    }

    @Bean
    fun redisTemplate(connectionFactory: LettuceConnectionFactory): RedisTemplate<String, Any> {
        return RedisTemplate<String, Any>().apply {
            this.connectionFactory = connectionFactory
            keySerializer = StringRedisSerializer()
            valueSerializer = JacksonJsonRedisSerializer(Any::class.java)
            hashKeySerializer = StringRedisSerializer()
            hashValueSerializer = JacksonJsonRedisSerializer(Any::class.java)
            setEnableTransactionSupport(true)
            afterPropertiesSet()
        }
    }

    @Bean
    fun objectMapper(): ObjectMapper {
        return JsonMapper.builder()
            .findAndAddModules()
            .build()
    }
}