package io.opendbapi.core.policy;

import io.opendbapi.core.error.ApiProblem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Who may call a workspace's endpoints and how often (spec §14). Rate limits are per
 * instance: with N instances a caller can reach N times the limit.
 */
public record ServingPolicy(Firewall firewall, RateLimits rateLimits) {

    public static final ServingPolicy NONE = new ServingPolicy(null, null);

    public ServingPolicy {
        firewall = firewall == null ? new Firewall(FirewallMode.off, List.of()) : firewall;
        rateLimits = rateLimits == null ? new RateLimits(null, null) : rateLimits;
    }

    public enum FirewallMode { off, allow, deny }

    /**
     * @param mode {@code allow}: only listed addresses may call; {@code deny}: listed addresses may not
     * @param entries IP addresses or CIDR ranges
     */
    public record Firewall(FirewallMode mode, List<String> entries) {
        public Firewall {
            mode = mode == null ? FirewallMode.off : mode;
            entries = entries == null ? List.of() : entries.stream().map(String::strip).filter(e -> !e.isEmpty()).distinct().toList();
            if (entries.size() > 500) {
                throw ApiProblem.badRequest("too_many_entries", "A firewall holds at most 500 entries.");
            }
            entries.forEach(IpRange::parse); // validates
            if (mode == FirewallMode.allow && entries.isEmpty()) {
                throw ApiProblem.badRequest("empty_allow_list", "An allow list with no entries would block every caller.");
            }
        }
    }

    /**
     * @param perClientPerMinute calls per minute per OAuth2 client (private endpoints)
     * @param perIpPerMinute calls per minute per caller IP (public endpoints)
     */
    public record RateLimits(@Schema(nullable = true) Integer perClientPerMinute, @Schema(nullable = true) Integer perIpPerMinute) {
        public RateLimits {
            perClientPerMinute = positive(perClientPerMinute);
            perIpPerMinute = positive(perIpPerMinute);
        }

        private static Integer positive(Integer v) {
            return v == null || v <= 0 ? null : Math.min(v, 1_000_000);
        }
    }
}
