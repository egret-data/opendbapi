package io.opendbapi.core.endpoint;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory result cache per instance (spec §9.5). Entries are the response's mapped
 * {@code data} and page {@code meta}, after data policies, so a {@code client}-scoped entry is
 * only ever served to the service account it was computed for. Keys carry the live version and
 * a generation that changes when the workspace's policies or an endpoint's cache is cleared.
 */
@Component
public class ResultCache {

    /** A cached answer and how long it lives. */
    public record CachedAnswer(Object data, Map<String, Object> pageMeta, long rows, int ttlSeconds, int weight) {
    }

    private final Cache<String, CachedAnswer> cache;
    private final Map<UUID, AtomicLong> generations = new ConcurrentHashMap<>();
    private MeterRegistry meters = new SimpleMeterRegistry();

    public ResultCache(@Value("${opendbapi.cache.max-bytes:134217728}") long maxBytes) {
        this.cache = Caffeine.newBuilder()
                .maximumWeight(maxBytes)
                .weigher((String k, CachedAnswer e) -> e.weight())
                .expireAfter(new Expiry<String, CachedAnswer>() {
                    @Override
                    public long expireAfterCreate(String key, CachedAnswer e, long now) {
                        return TimeUnit.SECONDS.toNanos(e.ttlSeconds());
                    }

                    @Override
                    public long expireAfterUpdate(String key, CachedAnswer e, long now, long current) {
                        return TimeUnit.SECONDS.toNanos(e.ttlSeconds());
                    }

                    @Override
                    public long expireAfterRead(String key, CachedAnswer e, long now, long current) {
                        return current;
                    }
                })
                .build();
    }

    @Autowired(required = false)
    void setMeters(MeterRegistry meters) {
        this.meters = meters;
    }

    /**
     * @param caller the service account for {@code client} scope, null for anonymous or {@code shared}
     * @param params bound parameter values
     * @param page   page, size and cursor, as the caller asked
     */
    public String key(UUID workspaceId, UUID endpointId, int version, UUID caller, Map<String, Object> params, String page) {
        String canonical = new TreeMap<>(params).toString() + "|" + page;
        return endpointId + ":" + version + ":" + generation(workspaceId) + ":" + generation(endpointId) + ":"
                + (caller == null ? "-" : caller) + ":" + sha256(canonical);
    }

    public CachedAnswer get(String key, UUID endpointId) {
        CachedAnswer e = cache.getIfPresent(key);
        Counter.builder(e == null ? "opendbapi.cache.misses" : "opendbapi.cache.hits").description("Result cache lookups")
                .tag("endpoint", endpointId.toString()).register(meters).increment();
        return e;
    }

    public void put(String key, Object data, Map<String, Object> pageMeta, long rows, int ttlSeconds) {
        int weight;
        try {
            weight = RequestJson.MAPPER.writeValueAsBytes(List.of(data, pageMeta)).length + key.length();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return; // not cacheable
        }
        cache.put(key, new CachedAnswer(data, java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(pageMeta)), rows, ttlSeconds, weight));
    }

    /** Forgets every entry of a workspace, e.g. when its data policies or caller attributes change. */
    public void clearWorkspace(UUID workspaceId) {
        if (changes != null) {
            changes.publish("results", workspaceId);
        } else {
            generation0(workspaceId).incrementAndGet();
        }
    }

    public void clearEndpoint(UUID endpointId) {
        clearWorkspace(endpointId);
    }

    private io.opendbapi.core.cluster.ChangeBus changes;

    /** Clearing on one instance clears every instance (several pods). Ids of workspaces and endpoints never collide. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setChanges(io.opendbapi.core.cluster.ChangeBus changes) {
        this.changes = changes;
        changes.on("results", id -> {
            if (id == null) {
                cache.invalidateAll();
            } else {
                generation0(id).incrementAndGet();
            }
        });
    }

    private long generation(UUID id) {
        AtomicLong g = generations.get(id);
        return g == null ? 0 : g.get();
    }

    private AtomicLong generation0(UUID id) {
        return generations.computeIfAbsent(id, k -> new AtomicLong());
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
