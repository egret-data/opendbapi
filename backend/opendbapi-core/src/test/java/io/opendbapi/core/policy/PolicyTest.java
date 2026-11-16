package io.opendbapi.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opendbapi.core.error.ApiProblem;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PolicyTest {

    @Test
    void ipRangesMatchV4AndV6AndRefuseHostNames() {
        // Arrange
        IpRange office = IpRange.parse("10.1.0.0/16");
        IpRange single = IpRange.parse("203.0.113.7");
        IpRange v6 = IpRange.parse("2001:db8::/32");

        // Act + Assert
        assertThat(office.contains(IpRange.address("10.1.200.3"))).isTrue();
        assertThat(office.contains(IpRange.address("10.2.0.1"))).isFalse();
        assertThat(single.contains(IpRange.address("203.0.113.7"))).isTrue();
        assertThat(single.contains(IpRange.address("203.0.113.8"))).isFalse();
        assertThat(v6.contains(IpRange.address("2001:db8:1::5"))).isTrue();
        assertThat(v6.contains(IpRange.address("10.1.0.1"))).isFalse();
        assertThatThrownBy(() -> IpRange.parse("example.com")).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> IpRange.parse("10.0.0.0/33")).isInstanceOf(ApiProblem.class);
        assertThat(IpRange.address("localhost")).isNull();
    }

    @Test
    void emptyAllowListIsRefused() {
        // Act + Assert
        assertThatThrownBy(() -> new ServingPolicy.Firewall(ServingPolicy.FirewallMode.allow, List.of()))
                .isInstanceOfSatisfying(ApiProblem.class, p -> assertThat(p.code()).isEqualTo("empty_allow_list"));
    }

    @Test
    void bucketAllowsBurstThenRefillsOverTime() {
        // Arrange
        AtomicLong now = new AtomicLong();
        RateLimiter limiter = new RateLimiter(now::get);

        // Act
        limiter.take("k", 2);
        limiter.take("k", 2);
        RateLimiter.Decision refused = limiter.take("k", 2);
        now.addAndGet(30_000_000_000L); // half a minute refills one token at 2/min
        RateLimiter.Decision afterRefill = limiter.take("k", 2);

        // Assert
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(30);
        assertThat(afterRefill.allowed()).isTrue();
        assertThat(afterRefill.remaining()).isZero();
    }

    @Test
    void forwardedForIsBelievedOnlyFromTrustedProxies() {
        // Arrange
        var props = new io.opendbapi.core.config.OpenDbApiProperties(io.opendbapi.core.config.OpenDbApiProperties.Mode.STANDALONE, "",
                new io.opendbapi.core.config.OpenDbApiProperties.Serving("api", "", List.of("10.0.0.0/8")),
                new io.opendbapi.core.config.OpenDbApiProperties.Auth(true, 5, java.time.Duration.ofMinutes(15), "", "/"),
                new io.opendbapi.core.config.OpenDbApiProperties.Datasources(null, null));
        ServingGuard guard = new ServingGuard(null, null, new RateLimiter(), props);

        // Act + Assert
        assertThat(guard.clientIp("10.0.0.5", "198.51.100.9, 10.0.0.7")).isEqualTo("198.51.100.9");
        assertThat(guard.clientIp("203.0.113.1", "198.51.100.9")).isEqualTo("203.0.113.1");
        assertThat(guard.clientIp("10.0.0.5", "not-an-ip")).isEqualTo("10.0.0.5");
        assertThat(guard.clientIp("10.0.0.5", null)).isEqualTo("10.0.0.5");
    }
}
