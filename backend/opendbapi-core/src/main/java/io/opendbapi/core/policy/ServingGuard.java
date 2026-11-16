package io.opendbapi.core.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendbapi.core.config.OpenDbApiProperties;
import io.opendbapi.core.error.ApiProblem;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Firewall and rate limits on the serving path (spec §9.1 steps 2 and 6, §14). Policies are
 * cached for a few seconds; saving one through this instance clears the cache.
 */
@Component
public class ServingGuard {

    private static final Duration POLICY_TTL = Duration.ofSeconds(5);

    private record Cached(ServingPolicy policy, List<IpRange> ranges, long expiresAtNanos) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final RateLimiter limiter;
    private final List<IpRange> trustedProxies;
    private final Map<UUID, Cached> policies = new ConcurrentHashMap<>();

    public ServingGuard(JdbcClient jdbc, ObjectMapper json, RateLimiter limiter, OpenDbApiProperties properties) {
        this.jdbc = jdbc;
        this.json = json;
        this.limiter = limiter;
        this.trustedProxies = properties.serving().trustedProxies().stream().map(IpRange::parse).toList();
    }

    public ServingPolicy policy(UUID workspaceId) {
        return cached(workspaceId).policy();
    }

    public void save(UUID workspaceId, ServingPolicy policy) {
        try {
            jdbc.sql("update workspace set serving_policy = ?::jsonb where id = ?").params(json.writeValueAsString(policy), workspaceId)
                    .update();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        policies.remove(workspaceId);
    }

    /**
     * The caller's address: the TCP peer, or, when the peer is a trusted proxy, the right-most
     * X-Forwarded-For entry that is not itself a trusted proxy.
     */
    public String clientIp(String remoteAddr, String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank() || !trusted(remoteAddr)) {
            return remoteAddr;
        }
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].strip();
            if (!trusted(hop)) {
                return IpRange.address(hop) == null ? remoteAddr : hop;
            }
        }
        return remoteAddr;
    }

    /** Step 2: 403 {@code ip_blocked} when the workspace firewall refuses the caller. */
    public void checkFirewall(UUID workspaceId, String ip) {
        Cached c = cached(workspaceId);
        ServingPolicy.FirewallMode mode = c.policy().firewall().mode();
        if (mode == ServingPolicy.FirewallMode.off) {
            return;
        }
        InetAddress address = IpRange.address(ip);
        boolean listed = address != null && c.ranges().stream().anyMatch(r -> r.contains(address));
        if (mode == ServingPolicy.FirewallMode.allow != listed) {
            throw new ApiProblem(403, "ip_blocked", "Calls from this address are not allowed.");
        }
    }

    /**
     * Step 6: the endpoint's own limit, then the caller's (client for private endpoints, IP for
     * public ones). Returns the tightest decision so the caller can send RateLimit headers.
     */
    public RateLimiter.Decision checkRate(UUID workspaceId, UUID endpointId, Integer endpointPerMinute, String clientId, String ip) {
        RateLimiter.Decision tightest = null;
        if (endpointPerMinute != null) {
            tightest = enforce(limiter.take("ep:" + endpointId, endpointPerMinute), tightest);
        }
        ServingPolicy.RateLimits limits = cached(workspaceId).policy().rateLimits();
        if (clientId != null && limits.perClientPerMinute() != null) {
            tightest = enforce(limiter.take("cl:" + workspaceId + ":" + clientId, limits.perClientPerMinute()), tightest);
        } else if (clientId == null && limits.perIpPerMinute() != null) {
            tightest = enforce(limiter.take("ip:" + workspaceId + ":" + ip, limits.perIpPerMinute()), tightest);
        }
        return tightest;
    }

    private static RateLimiter.Decision enforce(RateLimiter.Decision d, RateLimiter.Decision tightest) {
        if (!d.allowed()) {
            throw new RateLimited(d);
        }
        return tightest == null || d.remaining() < tightest.remaining() ? d : tightest;
    }

    /** 429 with the numbers for {@code Retry-After} and {@code RateLimit-*} headers. */
    public static final class RateLimited extends ApiProblem {
        private final transient RateLimiter.Decision decision;

        RateLimited(RateLimiter.Decision decision) {
            super(429, "rate_limited", "Too many calls. Retry after %d seconds.".formatted(decision.retryAfterSeconds()));
            this.decision = decision;
        }

        public RateLimiter.Decision decision() {
            return decision;
        }
    }

    private boolean trusted(String ip) {
        InetAddress a = IpRange.address(ip);
        return a != null && trustedProxies.stream().anyMatch(r -> r.contains(a));
    }

    private Cached cached(UUID workspaceId) {
        long now = System.nanoTime();
        Cached c = policies.get(workspaceId);
        if (c != null && c.expiresAtNanos() - now > 0) {
            return c;
        }
        ServingPolicy policy = jdbc.sql("select serving_policy::text from workspace where id = ?").param(workspaceId)
                .query(String.class).optional().map(this::read).orElse(ServingPolicy.NONE);
        List<IpRange> ranges = policy.firewall().entries().stream().map(IpRange::parse).toList();
        Cached fresh = new Cached(policy, ranges, now + POLICY_TTL.toNanos());
        policies.put(workspaceId, fresh);
        return fresh;
    }

    private ServingPolicy read(String text) {
        try {
            return json.readValue(text, ServingPolicy.class);
        } catch (Exception e) {
            return ServingPolicy.NONE;
        }
    }
}
