package io.opendbapi.core.policy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * Token buckets keyed by caller or endpoint (spec §14), in memory or shared in PostgreSQL: a bucket of {@code perMinute}
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

    private org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final java.util.concurrent.atomic.AtomicLong lastPurge = new java.util.concurrent.atomic.AtomicLong();

    /**
     * {@code opendbapi.rate-limit.shared=true}: buckets live in PostgreSQL, shared by every
     * instance, so a limit holds across pods (spec §14). Off, each instance counts on its own.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setShared(org.springframework.jdbc.core.simple.JdbcClient jdbc,
                   @org.springframework.beans.factory.annotation.Value("${opendbapi.rate-limit.shared:false}") boolean shared) {
        this.jdbc = shared ? jdbc : null;
    }

    public boolean shared() {
        return jdbc != null;
    }

    public Decision take(String key, int perMinute) {
        if (jdbc != null) {
            try {
                return takeShared(key + "#" + perMinute, perMinute);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(RateLimiter.class)
                        .warn("Shared rate limit unavailable, counting on this instance: {}", e.getMessage());
            }
        }
        return takeLocal(key, perMinute);
    }

    /** One atomic statement refills the bucket, decides, and takes a token when there is one. */
    private Decision takeShared(String key, int perMinute) {
        long now = System.nanoTime();
        long last = lastPurge.get();
        if (now - last > 5 * MINUTE_NANOS && lastPurge.compareAndSet(last, now)) {
            jdbc.sql("delete from rate_bucket where updated_at < now() - interval '10 minutes'").update();
        }
        double cap = perMinute;
        record Row(double tokens, boolean allowed) {
        }
        Row row = jdbc.sql("""
                with refill as (
                    select least(:cap, b.tokens + extract(epoch from clock_timestamp() - b.updated_at) * :cap / 60) as t
                    from rate_bucket b where b.key = :key)
                insert into rate_bucket as b (key, tokens, updated_at) values (:key, :cap - 1, clock_timestamp())
                on conflict (key) do update set
                    tokens = case when (select t from refill) >= 1 then (select t from refill) - 1 else (select t from refill) end,
                    updated_at = clock_timestamp()
                returning tokens, (xmax = 0) or (select t from refill) >= 1 as allowed""")
                .param("cap", cap).param("key", key)
                .query((rs, i) -> new Row(rs.getDouble(1), rs.getBoolean(2)))
                .single();
        if (row.allowed()) {
            return new Decision(true, perMinute, (int) Math.floor(Math.max(0, row.tokens())), 0);
        }
        double secondsUntilOne = (1 - row.tokens()) * 60.0 / perMinute;
        return new Decision(false, perMinute, 0, Math.max(1, (long) Math.ceil(secondsUntilOne)));
    }

    private Decision takeLocal(String key, int perMinute) {
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
