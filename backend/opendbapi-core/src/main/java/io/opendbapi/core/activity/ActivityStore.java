package io.opendbapi.core.activity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the access log for the Activity page (spec §17.4). Row-level secured. */
@Repository
public class ActivityStore {

    public record Summary(long requests, long errors, @Schema(nullable = true) Double p95Ms, long activeClients,
                          List<Day> byDay, List<TopEndpoint> topEndpoints, List<TopError> topErrors,
                          @Schema(description = "Endpoints with the highest p95 latency, at least 5 calls each") List<TopEndpoint> slowestEndpoints,
                          List<TopClient> topClients, List<TopIp> topIps) {
    }

    /** @param serviceAccount name of the client's service account, when it still exists */
    public record TopClient(String clientId, @Schema(nullable = true) String serviceAccount, long requests, long errors,
                            @Schema(nullable = true) Double p95Ms) {
    }

    public record TopIp(String ip, long requests, long errors) {
    }

    public record Day(LocalDate day, long requests, long errors) {
    }

    public record TopEndpoint(@Schema(nullable = true) UUID endpointId, @Schema(nullable = true) String name, String method,
                              String path, long requests, long errors, @Schema(nullable = true) Double p95Ms) {
    }

    public record TopError(String code, long count) {
    }

    public record LogEntry(UUID id, Instant ts, @Schema(nullable = true) UUID endpointId, @Schema(nullable = true) String endpointName,
                           @Schema(nullable = true) Integer version, String method, String path, int status, int durationMs,
                           @Schema(nullable = true) Integer rows, @Schema(nullable = true) String clientId,
                           @Schema(nullable = true) String ip, @Schema(nullable = true) String errorCode,
                           @Schema(nullable = true) String error, @Schema(nullable = true) String traceId,
                           @Schema(nullable = true) String requestId, boolean tryIt) {
    }

    /** @param status "2xx", "4xx", "5xx" or an exact code; null = any */
    public record LogFilter(Instant from, Instant to, UUID endpointId, String clientId, String status, String requestId,
                            boolean includeTryIt, Instant before, int limit) {
    }

    private final JdbcClient jdbc;

    public ActivityStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Summary summary(Instant from, Instant to, boolean includeTryIt) {
        return summary(from, to, includeTryIt, null);
    }

    /** @param endpointId only this endpoint's calls; null for the whole workspace */
    public Summary summary(Instant from, Instant to, boolean includeTryIt, UUID endpointId) {
        String where = " where l.ts >= ? and l.ts < ?" + (includeTryIt ? "" : " and l.actor_user_id is null")
                + (endpointId == null ? "" : " and l.endpoint_id = ?");
        Object[] range = endpointId == null ? new Object[] {Timestamp.from(from), Timestamp.from(to)}
                : new Object[] {Timestamp.from(from), Timestamp.from(to), endpointId};
        var totals = jdbc.sql("""
                select count(*) as requests, count(*) filter (where l.status >= 400) as errors,
                       percentile_cont(0.95) within group (order by l.duration_ms) as p95,
                       count(distinct l.client_id) as clients
                from access_log l""" + where)
                .params(range).query((rs, i) -> new Object[] {rs.getLong(1), rs.getLong(2), nullableDouble(rs, 3), rs.getLong(4)})
                .single();
        List<Day> days = jdbc.sql("""
                select (l.ts at time zone 'UTC')::date as day, count(*) as requests, count(*) filter (where l.status >= 400) as errors
                from access_log l""" + where + " group by 1 order by 1")
                .params(range).query((rs, i) -> new Day(rs.getObject(1, LocalDate.class), rs.getLong(2), rs.getLong(3))).list();
        List<TopEndpoint> top = jdbc.sql("""
                select l.endpoint_id, e.name, coalesce(min(e.method), min(l.method)) as method,
                       coalesce('/' || min(e.path), min(l.path)) as path, count(*) as requests,
                       count(*) filter (where l.status >= 400) as errors,
                       percentile_cont(0.95) within group (order by l.duration_ms) as p95
                from access_log l left join endpoint e on e.id = l.endpoint_id""" + where
                        + " group by l.endpoint_id, e.name order by requests desc limit 10")
                .params(range).query((rs, i) -> new TopEndpoint(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.getLong(6), nullableDouble(rs, 7))).list();
        List<TopError> errors = jdbc.sql("select l.error_code, count(*) from access_log l" + where
                        + " and l.error_code is not null group by 1 order by 2 desc limit 10")
                .params(range).query((rs, i) -> new TopError(rs.getString(1), rs.getLong(2))).list();
        List<TopEndpoint> slowest = jdbc.sql("""
                select l.endpoint_id, e.name, coalesce(min(e.method), min(l.method)) as method,
                       coalesce('/' || min(e.path), min(l.path)) as path, count(*) as requests,
                       count(*) filter (where l.status >= 400) as errors,
                       percentile_cont(0.95) within group (order by l.duration_ms) as p95
                from access_log l left join endpoint e on e.id = l.endpoint_id""" + where
                        + " and l.endpoint_id is not null group by l.endpoint_id, e.name having count(*) >= 5 order by p95 desc limit 10")
                .params(range).query((rs, i) -> new TopEndpoint(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.getLong(6), nullableDouble(rs, 7))).list();
        List<TopClient> clients = jdbc.sql("""
                select l.client_id, min(sa.name), count(*), count(*) filter (where l.status >= 400),
                       percentile_cont(0.95) within group (order by l.duration_ms)
                from access_log l left join service_account sa on sa.id = l.service_account_id""" + where
                        + " and l.client_id is not null group by l.client_id order by 3 desc limit 10")
                .params(range).query((rs, i) -> new TopClient(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                        nullableDouble(rs, 5))).list();
        List<TopIp> ips = jdbc.sql("select host(l.ip), count(*), count(*) filter (where l.status >= 400) from access_log l" + where
                        + " and l.ip is not null group by l.ip order by 2 desc limit 10")
                .params(range).query((rs, i) -> new TopIp(rs.getString(1), rs.getLong(2), rs.getLong(3))).list();
        return new Summary((Long) totals[0], (Long) totals[1], (Double) totals[2], (Long) totals[3], days, top, errors, slowest, clients, ips);
    }

    public List<LogEntry> log(LogFilter f) {
        StringBuilder sql = new StringBuilder("""
                select l.id, l.ts, l.endpoint_id, e.name, l.version, l.method, l.path, l.status, l.duration_ms, l.rows,
                       l.client_id, host(l.ip) as ip, l.error_code, l.error, l.trace_id, l.actor_user_id, l.request_id
                from access_log l left join endpoint e on e.id = l.endpoint_id where l.ts >= ? and l.ts < ?""");
        List<Object> args = new java.util.ArrayList<>(List.of(Timestamp.from(f.from()), Timestamp.from(f.to())));
        if (!f.includeTryIt()) {
            sql.append(" and l.actor_user_id is null");
        }
        if (f.requestId() != null && !f.requestId().isBlank()) {
            sql.append(" and l.request_id = ?");
            args.add(f.requestId().strip());
        }
        if (f.endpointId() != null) {
            sql.append(" and l.endpoint_id = ?");
            args.add(f.endpointId());
        }
        if (f.clientId() != null && !f.clientId().isBlank()) {
            sql.append(" and l.client_id = ?");
            args.add(f.clientId().strip());
        }
        if (f.status() != null && !f.status().isBlank()) {
            String s = f.status().strip().toLowerCase();
            if (s.matches("[1-5]xx")) {
                int base = (s.charAt(0) - '0') * 100;
                sql.append(" and l.status >= ? and l.status < ?");
                args.add(base);
                args.add(base + 100);
            } else if (s.matches("\\d{3}")) {
                sql.append(" and l.status = ?");
                args.add(Integer.parseInt(s));
            }
        }
        if (f.before() != null) {
            sql.append(" and l.ts < ?");
            args.add(Timestamp.from(f.before()));
        }
        sql.append(" order by l.ts desc limit ?");
        args.add(Math.min(Math.max(f.limit(), 1), 500));
        return jdbc.sql(sql.toString()).params(args).query((rs, i) -> new LogEntry(rs.getObject(1, UUID.class),
                rs.getTimestamp(2).toInstant(), rs.getObject(3, UUID.class), rs.getString(4), (Integer) rs.getObject(5),
                rs.getString(6), rs.getString(7), rs.getInt(8), rs.getInt(9), (Integer) rs.getObject(10), rs.getString(11),
                rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(17),
                rs.getObject(16) != null)).list();
    }

    private static Double nullableDouble(ResultSet rs, int i) throws SQLException {
        double v = rs.getDouble(i);
        return rs.wasNull() ? null : v;
    }
}
