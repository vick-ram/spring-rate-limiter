# Spring Rate Limiter

**Spring Rate Limiter** is a Kotlin-first, Redis-backed rate limiting dependency for Spring Boot applications. It gives your controllers and services production-ready request control through simple annotations, multiple algorithms, distributed Redis state, fallback methods, metrics, and clear `429 Too Many Requests` handling.

It is built as a **library**, not a standalone server. Add it to your Spring Boot application, annotate the endpoints or service methods you want to protect, configure Redis, and let the library handle rate limiting across one or many application instances.

> Designed for Spring Boot teams that want a lightweight, annotation-driven alternative to heavier rate-limiting solutions such as Bucket4j, while still supporting Redis-backed distributed limits.

---

## Why Spring Rate Limiter?

Modern APIs need more than simple request counters. A good rate limiter should protect expensive endpoints, prevent abuse, support burst traffic, work across multiple app instances, and integrate naturally with your Spring codebase.

Spring Rate Limiter focuses on:

* **Annotation-first usage** with `@RateLimit` and `@RateLimitGroup`
* **Distributed rate limiting** using Redis
* **Multiple algorithms** for different traffic patterns
* **Kotlin-first implementation** with Java/Spring compatibility in mind
* **Fallback methods** for graceful degradation
* **Metrics support** for monitoring accepted and rejected requests
* **Clear exception data** for HTTP `429 Too Many Requests` responses
* **Simple integration** as a dependency, not a service you have to deploy separately

---

## Features

### Multiple Rate Limiting Algorithms

Spring Rate Limiter supports:

| Algorithm           | Best For                                                    |
| ------------------- | ----------------------------------------------------------- |
| Token Bucket        | Allowing controlled bursts while preserving an average rate |
| Leaky Bucket        | Smoothing traffic into a steady processing rate             |
| Fixed Window        | Simple limits such as `100 requests per minute`             |
| Sliding Window      | More accurate rolling-window limits                         |
| Concurrent Requests | Limiting active in-flight operations                        |

### Annotation-Driven API

Apply rate limits directly to controllers, services, or classes:

```kotlin
@RateLimit(
    key = "user:#id",
    capacity = 10,
    duration = 60,
    unit = TimeUnit.SECONDS,
    type = RateLimitType.TOKEN_BUCKET
)
fun getUser(id: String): UserDto
```

### Redis-Backed Distributed Limits

When your Spring Boot app runs on multiple instances, Redis keeps rate limit state shared across the cluster.

This means limits apply consistently across:

* Kubernetes replicas
* Docker containers
* Horizontally scaled Spring Boot services
* Multiple JVM application instances

### Fallback Methods

Instead of always throwing an exception, you can define a fallback method:

```kotlin
@RateLimit(capacity = 5, duration = 1, unit = TimeUnit.MINUTES, fallbackMethod = "limitedFallback")
fun expensiveOperation(): String = "Allowed"

fun limitedFallback(): String = "Please try again later"
```

### Grouped Limits

Apply multiple limits to the same method:

```kotlin
@RateLimitGroup(
    limits = [
        RateLimit(key = "user:#id", capacity = 10, duration = 1, unit = TimeUnit.MINUTES),
        RateLimit(key = "global", capacity = 1000, duration = 1, unit = TimeUnit.MINUTES)
    ]
)
fun search(id: String): SearchResult
```

This lets you combine limits such as:

* Per-user limit
* Per-IP limit
* Global endpoint limit
* Organization/team limit
* Concurrent request limit

---

## Installation

> Replace the version and group ID with your published artifact coordinates.

### Gradle Kotlin DSL

```kotlin
dependencies {
    implementation("com.vickram:spring-rate-limiter:1.0.0")
}
```

### Gradle Groovy DSL

```groovy
dependencies {
    implementation 'com.vickram:spring-rate-limiter:1.0.0'
}
```

### Maven

```xml
<dependency>
    <groupId>com.vickram</groupId>
    <artifactId>spring-rate-limiter</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## Requirements

* Java 17 or newer
* Spring Boot 3.x or newer
* Redis server for distributed rate limiting
* Kotlin projects are supported first-class
* Java Spring Boot projects can also use the dependency if annotations and configuration are exposed as JVM-compatible APIs

---

## Quick Start

### 1. Add the Dependency

```kotlin
dependencies {
    implementation("com.vickram:spring-rate-limiter:1.0.0")
}
```

### 2. Configure Redis

`application.yml`:

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password:
```

Or `application.properties`:

```properties
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.password=
```

### 3. Add a Rate Limit

```kotlin
import limit.RateLimit
import limit.RateLimitType
import limit.TimeUnit
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class ApiController {

    @RateLimit(
        capacity = 100,
        duration = 1,
        unit = TimeUnit.MINUTES,
        type = RateLimitType.SLIDING_WINDOW
    )
    @GetMapping("/api/products")
    fun products(): String {
        return "Products request allowed"
    }
}
```

When the limit is exceeded, the library throws `RateLimitExceededException` unless a fallback method is configured.

---

## Core Concepts

### `@RateLimit`

Use `@RateLimit` when a method or class needs one rate limit rule.

```kotlin
@RateLimit(
    key = "user:#id",
    capacity = 20,
    duration = 60,
    unit = TimeUnit.SECONDS,
    type = RateLimitType.TOKEN_BUCKET,
    fallbackMethod = "fallback"
)
fun someFunction() {
    // ... Logic
}
```

#### Parameters

| Parameter        | Description                                                                                    |
| ---------------- | ---------------------------------------------------------------------------------------------- |
| `key`            | Rate limit key. Can be static or dynamic.                                                      |
| `capacity`       | Maximum allowed requests, tokens, queue size, or concurrent operations depending on algorithm. |
| `duration`       | Time window or refill interval.                                                                |
| `unit`           | Time unit for the duration.                                                                    |
| `type`           | Rate limiting algorithm.                                                                       |
| `fallbackMethod` | Optional method called when the request is rejected.                                           |

---

## Rate Limiting Algorithms

### Token Bucket

Token Bucket allows short bursts while enforcing an average request rate.

```kotlin
@RateLimit(
    key = "user:#id",
    capacity = 10,
    duration = 60,
    unit = TimeUnit.SECONDS,
    type = RateLimitType.TOKEN_BUCKET
)
fun someTokenBucketFunction() {
    // ... Logic
}
```

Use it for:

* Login attempts
* User-based API limits
* Search endpoints
* Public APIs that should allow small bursts

Example behavior:

* Bucket capacity: `10`
* Refill interval: `60 seconds`
* User can make up to 10 quick requests
* After tokens are consumed, new requests are rejected until tokens refill

---

### Leaky Bucket

Leaky Bucket smooths traffic into a predictable processing rate.

```kotlin
@RateLimit(
    key = "payment:#userId",
    capacity = 5,
    duration = 10,
    unit = TimeUnit.SECONDS,
    type = RateLimitType.LEAKY_BUCKET
)
fun someLeakyFunction() {
    // ... Logic
}
```

Use it for:

* Payment processing
* Email sending
* Webhook delivery
* External API calls
* Downstream systems that need stable traffic

---

### Fixed Window

Fixed Window counts requests in fixed time periods.

```kotlin
@RateLimit(
    key = "ip:#ip",
    capacity = 100,
    duration = 1,
    unit = TimeUnit.MINUTES,
    type = RateLimitType.FIXED_WINDOW
)
fun fixedWindowFunction() {
    // ... Logic
}
```

Use it for:

* Simple endpoint limits
* Admin APIs
* Internal dashboards
* Basic abuse prevention

Fixed Window is easy to understand, but it may allow bursts around window boundaries.

---

### Sliding Window

Sliding Window tracks requests over a rolling time period.

```kotlin
@RateLimit(
    key = "user:#id",
    capacity = 100,
    duration = 1,
    unit = TimeUnit.MINUTES,
    type = RateLimitType.SLIDING_WINDOW
)
fun slidingWindowFunction() {
    // ... Logic
}
```

Use it for:

* Public APIs
* User-facing endpoints
* More accurate per-minute limits
* Production APIs where boundary bursts are not acceptable

Sliding Window is usually a better default than Fixed Window for user-facing APIs.

---

### Concurrent Requests

Concurrent Requests limits active in-flight operations.

```kotlin
@RateLimit(
    key = "report:#userId",
    capacity = 2,
    type = RateLimitType.CONCURRENT_REQUESTS
)
fun someConcurrentFunction() {
    // ... Logic
}
```

Use it for:

* Report generation
* File uploads
* Video processing
* AI/ML inference calls
* Expensive database operations
* Anything that should not run too many times at once

---

## Usage Examples

### Limit an Endpoint by User ID

```kotlin
@RestController
class UserController {

    @RateLimit(
        key = "user:#id",
        capacity = 10,
        duration = 1,
        unit = TimeUnit.MINUTES,
        type = RateLimitType.TOKEN_BUCKET
    )
    @GetMapping("/api/users/{id}")
    fun getUser(@PathVariable id: String): String {
        return "User $id"
    }
}
```

---

### Limit Login Attempts

```kotlin
@RestController
class AuthController {

    @RateLimit(
        key = "login:#email",
        capacity = 5,
        duration = 15,
        unit = TimeUnit.MINUTES,
        type = RateLimitType.SLIDING_WINDOW
    )
    @PostMapping("/api/auth/login")
    fun login(@RequestBody request: LoginRequest): String {
        return "Login attempt accepted"
    }
}

data class LoginRequest(
    val email: String,
    val password: String
)
```

---

### Limit Expensive Reports

```kotlin
@RestController
class ReportController {

    @RateLimit(
        key = "report:#userId",
        capacity = 2,
        type = RateLimitType.CONCURRENT_REQUESTS,
        fallbackMethod = "reportFallback"
    )
    @GetMapping("/api/reports/{userId}")
    fun generateReport(@PathVariable userId: String): String {
        Thread.sleep(3000)
        return "Report generated for $userId"
    }

    fun reportFallback(userId: String): String {
        return "A report is already being generated for $userId. Please try again shortly."
    }
}
```

---

### Apply Multiple Limits to One Endpoint

```kotlin
@RestController
class SearchController {

    @RateLimitGroup(
        limits = [
            RateLimit(
                key = "user:#userId",
                capacity = 30,
                duration = 1,
                unit = TimeUnit.MINUTES,
                type = RateLimitType.SLIDING_WINDOW
            ),
            RateLimit(
                key = "global:search",
                capacity = 1000,
                duration = 1,
                unit = TimeUnit.MINUTES,
                type = RateLimitType.TOKEN_BUCKET
            )
        ]
    )
    @GetMapping("/api/search/{userId}")
    fun search(@PathVariable userId: String): String {
        return "Search request allowed"
    }
}
```

---

## Key Resolution

The `key` parameter controls how requests are grouped.

Good keys are important because they decide who or what gets rate limited.

### Static Key

```kotlin
@RateLimit(key = "global:checkout", capacity = 100, duration = 1, unit = TimeUnit.MINUTES)
fun globalFunction() {
    // ... Logic
}
```

All callers share the same limit.

Use for:

* Global endpoint protection
* Third-party API quotas
* Shared resource protection

---

### Dynamic Key

```kotlin
@RateLimit(key = "user:#id", capacity = 20, duration = 1, unit = TimeUnit.MINUTES)
fun someFunction() {
    // ... Logic
}
```

Each user gets a separate limit.

Use for:

* User limits
* Organization limits
* IP limits
* API key limits

---

### Default Key

If `key` is empty, a default key is generated from the target class, method, and method arguments.

```kotlin
@RateLimit(capacity = 50, duration = 1, unit = TimeUnit.MINUTES)
fun someFunction() {
    // ... Logic
}
```

Use this for quick protection where custom grouping is not needed.

---

## Fallback Methods

A fallback method is called when a limit is exceeded.

```kotlin
@RateLimit(
    capacity = 1,
    duration = 5,
    unit = TimeUnit.SECONDS,
    fallbackMethod = "fallbackMethod"
)
fun limitedEndpoint(): String {
    return "Request allowed"
}

fun fallbackMethod(): String {
    return "Rate limit exceeded. Please try again soon."
}
```

Fallback method rules:

* It should be in the same class
* It should return the same type as the original method
* It may accept no arguments
* It may accept the same arguments as the original method

Use fallback methods when you want graceful degradation instead of exceptions.

Good fallback examples:

* Return cached data
* Return a friendly message
* Return a reduced response
* Skip non-critical work
* Queue work for later

---

## Error Handling

If no fallback method is configured and the request exceeds the limit, the library throws `RateLimitExceededException`.

You can convert it into a proper HTTP `429 Too Many Requests` response with `@ControllerAdvice`.

```kotlin
import limit.RateLimitExceededException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@ControllerAdvice
class RateLimitExceptionHandler {

    @ExceptionHandler(RateLimitExceededException::class)
    fun handleRateLimitExceeded(ex: RateLimitExceededException): ResponseEntity<Map<String, Any?>> {
        val body = mapOf(
            "status" to HttpStatus.TOO_MANY_REQUESTS.value(),
            "error" to "Too Many Requests",
            "message" to ex.message,
            "retryAfterSeconds" to ex.retryAfterSeconds,
            "limit" to ex.limit,
            "remaining" to ex.remaining
        )

        return ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", ex.retryAfterSeconds.toString())
            .body(body)
    }
}
```

Example response:

```json
{
  "status": 429,
  "error": "Too Many Requests",
  "message": "Rate limit exceeded",
  "retryAfterSeconds": 30,
  "limit": 100,
  "remaining": 0
}
```

---

## Redis Configuration

Spring Rate Limiter uses Spring Boot Redis configuration.

### Local Redis

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

### Redis with Password

```yaml
spring:
  data:
    redis:
      host: redis.example.com
      port: 6379
      password: ${REDIS_PASSWORD}
```

### Redis URL

```yaml
spring:
  data:
    redis:
      url: redis://localhost:6379
```

### Docker Compose for Local Development

```yaml
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
```

Start Redis:

```bash
docker compose up -d redis
```

---

## Metrics

The library records rate limit decisions so you can understand how your limits behave.

Metric fields include:

| Field              | Description                          |
| ------------------ | ------------------------------------ |
| `key`              | Rate limit key                       |
| `type`             | Algorithm used                       |
| `timestamp`        | Time of the decision                 |
| `allowed`          | Whether the request was allowed      |
| `remaining`        | Remaining capacity, tokens, or slots |
| `processingTimeMs` | Time spent checking the limit        |

Metrics can be useful for:

* Detecting abusive clients
* Tuning rate limits
* Finding overloaded endpoints
* Measuring rejected requests
* Monitoring Redis-backed limit behavior

---

## Recommended Rate Limit Patterns

### Public API

```kotlin
@RateLimit(
    key = "api-key:#apiKey",
    capacity = 1000,
    duration = 1,
    unit = TimeUnit.HOURS,
    type = RateLimitType.SLIDING_WINDOW
)
fun publicApiFunction() {
    // ... Logic
}
```

### Login Endpoint

```kotlin
@RateLimit(
    key = "login:#email",
    capacity = 5,
    duration = 15,
    unit = TimeUnit.MINUTES,
    type = RateLimitType.SLIDING_WINDOW
)
fun loginFunction() {
    // ... Logic
}
```

### Global Endpoint Protection

```kotlin
@RateLimit(
    key = "global:checkout",
    capacity = 500,
    duration = 1,
    unit = TimeUnit.MINUTES,
    type = RateLimitType.TOKEN_BUCKET
)
fun checkoutFunction() {
    // ... Logic
}
```

### Expensive Job Protection

```kotlin
@RateLimit(
    key = "job:#userId",
    capacity = 1,
    type = RateLimitType.CONCURRENT_REQUESTS
)
fun someJobFunction() {
    // ... Logic
}
```

---

## Choosing the Right Algorithm

| Use Case                          | Recommended Algorithm          |
| --------------------------------- | ------------------------------ |
| Public API rate limits            | Sliding Window or Token Bucket |
| Login attempts                    | Sliding Window                 |
| Allowing short bursts             | Token Bucket                   |
| Smoothing downstream calls        | Leaky Bucket                   |
| Simple internal limits            | Fixed Window                   |
| Expensive long-running operations | Concurrent Requests            |
| Global endpoint protection        | Token Bucket or Sliding Window |

Recommended default for most production APIs:

```text
Sliding Window for fairness
Token Bucket for burst-friendly APIs
Concurrent Requests for expensive operations
```

---

## Spring Boot Integration Notes

Spring Rate Limiter is designed to fit into normal Spring Boot applications.

You do not need to run a separate rate-limiter server.

Your application owns:

* Controllers
* Services
* Redis configuration
* Exception handling
* Observability setup

The library provides:

* Annotations
* AOP/interception
* Algorithm execution
* Redis state handling
* Rate limit exceptions
* Metrics collection

---

## Comparison with Bucket4j

Bucket4j is a mature and popular Java rate-limiting library. Spring Rate Limiter takes a different approach by focusing on Spring Boot developer experience.

| Feature                                 | Spring Rate Limiter | Bucket4j                                  |
| --------------------------------------- | ------------------- | ----------------------------------------- |
| Spring Boot annotation-first API        | Yes                 | Requires integration setup                |
| Kotlin-first implementation             | Yes                 | Java-first                                |
| Multiple algorithms beyond token bucket | Yes                 | Primarily token bucket focused            |
| Redis-backed distributed limits         | Yes                 | Supported through integrations/extensions |
| Fallback method support                 | Yes                 | Application-defined                       |
| Simple controller/service annotations   | Yes                 | Requires more manual wiring               |
| Built for Spring Boot dependency usage  | Yes                 | General-purpose library                   |

Use Spring Rate Limiter if you want a Spring-native, annotation-driven dependency that is quick to apply across controllers and services.

Use Bucket4j if you specifically need Bucket4j's mature ecosystem, advanced token bucket model, or existing infrastructure integrations.

---

## SEO Keywords

Spring Rate Limiter is useful for developers searching for:

* Spring Boot rate limiter
* Kotlin Spring Boot rate limiter
* Redis rate limiter for Spring Boot
* Distributed rate limiting Spring Boot
* Spring Boot annotation rate limiting
* Bucket4j alternative
* Token bucket Spring Boot
* Sliding window rate limiter Spring Boot
* Redis token bucket rate limiter
* API rate limiting in Spring Boot
* Spring Boot `429 Too Many Requests`

---

## Project Status

This project is intended to be used as a dependency inside Spring Boot applications.

It is not a standalone web server and does not require `bootRun` for consumers.

For local development of the library itself, use:

```bash
./gradlew test
./gradlew build
```

If the repository contains sample applications, they should be treated as examples only, not as the main artifact.

---

## Development

Clone the repository:

```bash
git clone https://github.com/your-username/spring-rate-limiter.git
cd spring-rate-limiter
```

Run tests:

```bash
./gradlew test
```

Build the library:

```bash
./gradlew build
```

Publish to local Maven repository:

```bash
./gradlew publishToMavenLocal
```

Use the local version in another Spring Boot project:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("com.vickram:spring-rate-limiter:1.0.0")
}
```

---

## Testing Rate Limits

Example curl test:

```bash
for i in {1..10}; do
  curl -i http://localhost:8080/api/products
  echo
done
```

Expected behavior:

* Requests within the limit return success
* Requests above the limit return fallback response or HTTP `429`
* `Retry-After` header is included when using exception handling

---

## Best Practices

### Use Explicit Keys

Prefer this:

```kotlin
@RateLimit(key = "user:#id", capacity = 100, duration = 1, unit = TimeUnit.MINUTES)
fun someFunction() {
    // ... Logic
}
```

Instead of relying on default keys for important production limits.

### Protect Login and Password Reset Endpoints

Authentication endpoints should almost always have rate limits.

```kotlin
@RateLimit(key = "password-reset:#email", capacity = 3, duration = 15, unit = TimeUnit.MINUTES)
fun someFunction() {
    // ... Logic
}
```

### Combine User and Global Limits

Use `@RateLimitGroup` when both per-user and global protection are needed.

### Use Sliding Window for Fairness

Sliding Window avoids many boundary issues found in Fixed Window rate limiting.

### Use Concurrent Limits for Expensive Work

Request rate and concurrent work are different problems. Use `CONCURRENT_REQUESTS` for slow or expensive tasks.

---

## Frequently Asked Questions

### Is this a server I need to run?

No. Spring Rate Limiter is a dependency. You add it to your Spring Boot application.

### Does it work with multiple Spring Boot instances?

Yes. Redis stores rate limit state so limits can be shared across application instances.

### Does it replace Redis?

No. Redis is used as the distributed state backend.

### Can I use it without Redis?

The library is designed for Redis-backed distributed rate limiting. If an in-memory mode is added, it should only be used for local development or single-instance applications.

### Can I use it in Java projects?

Yes, if the public annotations and APIs are exposed in a Java-friendly way. The implementation is Kotlin-first, but it runs on the JVM.

### What happens when a user exceeds the limit?

Either:

* The configured fallback method is called
* Or `RateLimitExceededException` is thrown

### Which algorithm should I start with?

Use Sliding Window for most user-facing API limits. Use Token Bucket when you want controlled bursts. Use Concurrent Requests for expensive operations.

---

## Roadmap

Possible future improvements:

* Spring Boot auto-configuration starter
* Micrometer metrics integration
* Prometheus-friendly metrics
* Custom key resolver SPI
* IP-based key resolver
* API key-based resolver
* In-memory local development backend
* Redis Cluster support documentation
* Request headers such as `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `X-RateLimit-Reset`
* Java examples
* Spring WebFlux examples

---

## Contributing

Contributions are welcome.

You can help by:

* Reporting bugs
* Improving documentation
* Adding examples
* Adding tests
* Suggesting new algorithms
* Improving Spring Boot auto-configuration
* Adding observability integrations

Before submitting a pull request:

```bash
./gradlew test
./gradlew build
```

---

## License

This project is licensed under the MIT License.

See the `LICENSE` file for details.

---

## Short Description

A Kotlin-first, Redis-backed, annotation-driven Spring Boot rate limiter with Token Bucket, Sliding Window, Fixed Window, Leaky Bucket, Concurrent Requests, fallback methods, metrics, and distributed rate limiting support.
