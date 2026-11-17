package io.opendbapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendbapi.core.policy.RateLimiter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/** Rate limits shared by every instance through PostgreSQL (spec §14). */
@TestPropertySource(properties = "opendbapi.rate-limit.shared=true")
class SharedRateLimitIntegrationTest extends AppTestSupport {

    @Autowired
    JdbcClient jdbc;

    private RateLimiter instance() {
        RateLimiter limiter = new RateLimiter();
        ReflectionTestUtils.invokeMethod(limiter, "setShared", jdbc, true);
        return limiter;
    }

    @Test
    void twoInstancesDrawFromOneBucket() {
        // Arrange
        RateLimiter podA = instance();
        RateLimiter podB = instance();
        String key = "test:" + UUID.randomUUID();

        // Act
        var a1 = podA.take(key, 3);
        var a2 = podA.take(key, 3);
        var b1 = podB.take(key, 3);
        var b2 = podB.take(key, 3);

        // Assert
        assertThat(podA.shared()).isTrue();
        assertThat(List.of(a1.allowed(), a2.allowed(), b1.allowed())).containsOnly(true);
        assertThat(b1.remaining()).isZero();
        assertThat(b2.allowed()).isFalse();
        assertThat(b2.retryAfterSeconds()).isBetween(1L, 20L);
    }

    @Test
    void anEndpointLimitHoldsAcrossTheSharedBucket() {
        // Arrange
        ApiClient admin = signedInAdmin();
        String ws = "rl-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post("/admin/api/workspaces", Map.of("slug", ws, "name", "Rate " + ws)).expect(201);
        Map<String, Object> ds = new HashMap<>(Map.of("name", "meta", "dialect", "POSTGRESQL", "host", POSTGRES.getHost(),
                "port", POSTGRES.getMappedPort(5432), "database", "opendbapi", "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword(), "properties", Map.of(), "readOnly", true, "pool", Map.of()));
        String dsId = admin.post("/admin/api/ws/" + ws + "/datasources", ds).expect(201).json().path("id").asText();
        String id = admin.post("/admin/api/ws/" + ws + "/endpoints", Map.of("name", "r", "method", "GET", "path", "r", "access", "public",
                "datasourceId", dsId)).expect(201).json().path("summary").path("id").asText();
        admin.patch("/admin/api/ws/" + ws + "/endpoints/" + id, Map.of("name", "r", "method", "GET", "path", "r", "access", "public",
                "definition", Map.of("params", List.of(), "limits", Map.of("timeoutSeconds", 30, "maxRows", 100, "ratePerMinute", 2),
                        "task", Map.of("type", "sql", "datasourceId", dsId, "transaction", false,
                                "statements", List.of(Map.of("id", "main", "template", "select 1")))))).expect(200);
        admin.post("/admin/api/ws/" + ws + "/endpoints/" + id + ":publish", Map.of()).expect(200);

        // Act
        client().get("/api/" + ws + "/r").expect(200);
        client().get("/api/" + ws + "/r").expect(200);
        var third = client().get("/api/" + ws + "/r");
        int rows = jdbc.sql("select count(*) from rate_bucket where key like ?").param("ep:" + id + "%").query(Integer.class).single();

        // Assert
        assertThat(third.status()).isEqualTo(429);
        assertThat(rows).isEqualTo(1);
    }
}
