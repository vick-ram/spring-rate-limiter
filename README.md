# Spring Rate Limiter

A robust and flexible rate-limiting library for Spring Boot applications, implemented in Kotlin. This library provides various rate-limiting algorithms and integrates seamlessly with Redis for distributed rate limiting.

## Features

*   **Multiple Rate Limiting Algorithms**: Supports Token Bucket, Leaky Bucket, Fixed Window, Sliding Window, and Concurrent Requests.
*   **Annotation-Driven**: Apply rate limits easily using `@RateLimit` and `@RateLimitGroup` annotations on methods or classes.
*   **Redis Integration**: Utilizes Redis for storing rate limit states, enabling distributed rate limiting across multiple application instances.
*   **Configurable**: Customize capacity, duration, and time units for each rate limit.
*   **Dynamic Key Resolution**: Define rate limit keys dynamically based on method arguments or custom expressions.
*   **Fallback Methods**: Specify fallback methods to execute when a rate limit is exceeded, providing graceful degradation.
*   **Metrics Collection**: Gathers metrics on rate limit usage, allowing for monitoring and analysis.
*   **Error Handling**: Throws `RateLimitExceededException` with useful information when limits are hit.

## Getting Started

These instructions will get you a copy of the project up and running on your local machine for development and testing purposes.

### Prerequisites

*   Java Development Kit (JDK) 17 or newer
*   Gradle (wrapper included)
*   Redis server running locally or accessible via network

### Building and Running

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/your-username/spring-rate-limiter.git
    cd spring-rate-limiter
    ```

2.  **Build the project:**
    ```bash
    ./gradlew build
    ```

3.  **Run the application:**
    ```bash
    ./gradlew bootRun
    ```
    The application will start on `http://localhost:8080` (or your configured port).

## Usage

The library provides `@RateLimit` and `@RateLimitGroup` annotations to apply rate limiting to your Spring services or controllers.

### `@RateLimit` Annotation

Apply this annotation to a method or a class to enforce a single rate limit.

**Parameters:**

*   `key`: (Optional) A string expression to resolve the rate limit key. If empty, a default key based on class, method, and arguments will be generated. Supports `#id` for the first argument.
*   `capacity`: The maximum number of requests allowed within the `duration`.
*   `duration`: The time period for the rate limit.
*   `unit`: The `TimeUnit` for the `duration` (SECONDS, MINUTES, HOURS, DAYS).
*   `type`: The `RateLimitType` to use (TOKEN_BUCKET, LEAKY_BUCKET, FIXED_WINDOW, SLIDING_WINDOW, CONCURRENT_REQUESTS).
*   `fallbackMethod`: (Optional) The name of a method in the same class to call if the rate limit is exceeded.

**Example:**

```kotlin
import limit.RateLimit
import limit.RateLimitType
import limit.TimeUnit
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class MyController {

    // Fixed Window: 5 requests per 60 seconds for this method
    @RateLimit(capacity = 5, duration = 60, unit = TimeUnit.SECONDS, type = RateLimitType.FIXED_WINDOW)
    @GetMapping("/api/fixed-window")
    fun fixedWindowEndpoint(): String {
        return "Fixed Window: Request allowed!"
    }

    // Token Bucket: 10 tokens, refills 10 tokens per 60 seconds, key based on path variable
    @RateLimit(key = "#id", capacity = 10, duration = 60, unit = TimeUnit.SECONDS, type = RateLimitType.TOKEN_BUCKET)
    @GetMapping("/api/token-bucket/{id}")
    fun tokenBucketEndpoint(@PathVariable id: String): String {
        return "Token Bucket for $id: Request allowed!"
    }

    // Sliding Window: 3 requests per 30 seconds
    @RateLimit(capacity = 3, duration = 30, unit = TimeUnit.SECONDS, type = RateLimitType.SLIDING_WINDOW)
    @GetMapping("/api/sliding-window")
    fun slidingWindowEndpoint(): String {
        return "Sliding Window: Request allowed!"
    }

    // Leaky Bucket: 2 requests per 10 seconds
    @RateLimit(capacity = 2, duration = 10, unit = TimeUnit.SECONDS, type = RateLimitType.LEAKY_BUCKET)
    @GetMapping("/api/leaky-bucket")
    fun leakyBucketEndpoint(): String {
        return "Leaky Bucket: Request allowed!"
    }

    // Concurrent Requests: Max 2 concurrent requests
    @RateLimit(capacity = 2, type = RateLimitType.CONCURRENT_REQUESTS)
    @GetMapping("/api/concurrent")
    fun concurrentEndpoint(): String {
        Thread.sleep(1000) // Simulate work
        return "Concurrent: Request allowed!"
    }

    // Example with a fallback method
    @RateLimit(capacity = 1, duration = 5, unit = TimeUnit.SECONDS, fallbackMethod = "fallbackMethod")
    @GetMapping("/api/with-fallback")
    fun endpointWithFallback(): String {
        return "Endpoint with Fallback: Request allowed!"
    }

    fun fallbackMethod(): String {
        return "Fallback: Rate limit exceeded for endpointWithFallback!"
    }
}
```

### `@RateLimitGroup` Annotation

Use this annotation to apply multiple rate limits simultaneously. If any of the limits in the group are exceeded, the request will be rejected (or the fallback will be called).

**Example:**

```kotlin
import limit.RateLimit
import limit.RateLimitGroup
import limit.RateLimitType
import limit.TimeUnit
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class MyGroupedController {

    @RateLimitGroup(
        limits = [
            RateLimit(capacity = 5, duration = 60, unit = TimeUnit.SECONDS, type = RateLimitType.FIXED_WINDOW),
            RateLimit(key = "global", capacity = 100, duration = 3600, unit = TimeUnit.SECONDS, type = RateLimitType.TOKEN_BUCKET)
        ]
    )
    @GetMapping("/api/grouped-limits")
    fun groupedLimitsEndpoint(): String {
        return "Grouped Limits: Request allowed!"
    }
}
```

### Key Resolution

The `key` parameter in `@RateLimit` can be used to define dynamic keys.

*   **Empty `key`**: A default key is generated based on the class name, method name, and a hash of method arguments.
*   **`#id`**: If the first argument of the method is an ID, you can use `#id` to incorporate it into the key. For example, `@RateLimit(key = "user:#id", ...)` for a method `getUser(@PathVariable id: String)`.
*   **Custom String**: You can provide any static string as a key.

### Fallback Methods

If `fallbackMethod` is specified in `@RateLimit`, the method with that name in the same class will be invoked when the rate limit is exceeded. The fallback method must have the same return type as the original method and can optionally take no arguments or the same arguments as the original method.

## Configuration

### Redis Configuration

The library relies on Spring Boot's auto-configuration for Redis. Ensure you have Redis running and configured in your `application.properties` or `application.yml`.

Example `application.properties`:

```properties
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.password=
```

## Rate Limiting Algorithms

### Token Bucket

*   **Concept**: A bucket with a fixed `capacity` that refills at a constant `rate`. Each request consumes a token. If the bucket is empty, the request is rejected.
*   **Use Case**: Smooth out bursts of traffic, allowing short-term spikes while maintaining an average rate.

### Leaky Bucket

*   **Concept**: Requests are added to a bucket. If the bucket overflows, new requests are rejected. Requests "leak" out of the bucket at a constant `rate`.
*   **Use Case**: Enforce a steady output rate, useful for preventing downstream systems from being overwhelmed.

### Fixed Window

*   **Concept**: Divides time into fixed-size `windows`. Requests are counted within each window. Once the `capacity` is reached for the current window, further requests are rejected until the next window starts.
*   **Use Case**: Simple and easy to understand, but can suffer from a "bursty" problem at the window boundaries.

### Sliding Window

*   **Concept**: A more advanced version of the fixed window. It tracks requests over a moving time `window`, typically by using timestamps. This avoids the bursty problem of the fixed window.
*   **Use Case**: Provides a more accurate and smoother rate limiting than the fixed window.

### Concurrent Requests

*   **Concept**: Limits the number of requests that can be processed concurrently. New requests are blocked or rejected if the number of active requests exceeds the `capacity`.
*   **Use Case**: Protect resources that can only handle a limited number of simultaneous operations.

## Metrics

The `RateLimitService` collects basic metrics internally. These metrics include:

*   `key`: The rate limit key.
*   `type`: The `RateLimitType`.
*   `timestamp`: When the request was processed.
*   `allowed`: Whether the request was allowed or rejected.
*   `remaining`: Remaining tokens/capacity.
*   `processingTimeMs`: Time taken to process the rate limit check.

These metrics are stored in an in-memory `ConcurrentLinkedQueue` and also pushed to Redis lists for distributed collection (trimmed to the last 1000 entries). You can extend `RateLimitService` or integrate with a monitoring system to expose these metrics.

## Error Handling

When a rate limit is exceeded and no `fallbackMethod` is specified, a `RateLimitExceededException` is thrown. This exception contains:

*   `message`: A descriptive error message.
*   `retryAfterSeconds`: The recommended time in seconds before retrying the request.
*   `limit`: The configured capacity of the rate limit.
*   `remaining`: The remaining capacity at the time of rejection.

You can catch this exception in your Spring `@ControllerAdvice` to return appropriate HTTP responses (e.g., HTTP 429 Too Many Requests).

```kotlin
import limit.RateLimitExceededException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@ControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(RateLimitExceededException::class)
    fun handleRateLimitExceededException(ex: RateLimitExceededException): ResponseEntity<Map<String, Any>> {
        val body = mapOf(
            "status" to HttpStatus.TOO_MANY_REQUESTS.value(),
            "error" to "Too Many Requests",
            "message" to ex.message,
            "retryAfterSeconds" to ex.retryAfterSeconds,
            "limit" to ex.limit,
            "remaining" to ex.remaining
        )
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", ex.retryAfterSeconds.toString())
            .body(body)
    }
}
```

## Contributing

Contributions are welcome! Please feel free to submit pull requests or open issues.

## License

This project is licensed under the MIT License - see the LICENSE file for details.
