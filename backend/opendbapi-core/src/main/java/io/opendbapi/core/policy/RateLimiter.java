package io.opendbapi.core.policy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * In-memory token buckets keyed by caller or endpoint (spec §14): a bucket of {@code perMinute}
 * tokens refilled continuously. Idle buckets are dropped.
 */
@Component
public class RateLimiter {

    /** Outcome of one attempt; {@code retryAfterSeconds} is 0 when allowed. */
    public record Decision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {
    }

    private static final long MINUTE_NANOS = 60_000_000_000L;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public RateLimiter() {
        this(System::nanoTime);
    }

    RateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    public Decision take(String key, int perMinute) {
        long now = clock.getAsLong();
        if (buckets.size() > 100_000) {
            buckets.values().removeIf(b -> now - b.updated > MINUTE_NANOS);
        }
        Bucket bucket = buckets.computeIfAbsent(key + "#" + perMinute, k -> new Bucket(perMinute, now));
        synchronized (bucket) {
            double refill = (now - bucket.updated) * (double) perMinute / MINUTE_NANOS;
            bucket.tokens = Math.min(perMinute, bucket.tokens + refill);
            bucket.updated = now;
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return new Decision(true, perMinute, (int) Math.floor(bucket.tokens), 0);
            }
            double secondsUntilOne = (1 - bucket.tokens) * 60.0 / perMinute;
            return new Decision(false, perMinute, 0, Math.max(1, (long) Math.ceil(secondsUntilOne)));
        }
    }

    private static final class Bucket {
        double tokens;
        long updated;

        Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.updated = now;
        }
    }
}
