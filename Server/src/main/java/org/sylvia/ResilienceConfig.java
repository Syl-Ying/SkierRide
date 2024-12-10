package org.sylvia;

import io.github.resilience4j.ratelimiter.*;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;

import java.time.Duration;

public class ResilienceConfig {

    private static final RateLimiter rateLimiter = RateLimiter.of("throttleRequests",
            RateLimiterConfig.custom()
                    .limitForPeriod(1200) // Allow 1k2 requests per second according to rmq consumer rate
                    .limitRefreshPeriod(Duration.ofSeconds(1)) // Refresh every 1 second
                    .timeoutDuration(Duration.ofMillis(100)) // Timeout for acquiring permission
                    .build());

    public static RateLimiter getRateLimiter() {
        return rateLimiter;
    }
}
