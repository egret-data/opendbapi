package io.opendbapi.core.activity;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opendbapi.core.policy.IpRange;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.opendbapi.core.workspace.WorkspaceStore;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes access records to the {@code access_log} table in batches from a bounded queue
 * (spec §19). Serving never waits for it: a full queue drops the record and counts it in
 * {@code opendbapi_access_log_dropped_total}. Old rows are deleted after the retention period.
 */
@Component
public class AccessLogWriter {

    private static final Logger log = LoggerFactory.getLogger(AccessLogWriter.class);
    private static final int BATCH = 1000;
    private static final String INSERT = """
            insert into access_log (ts, workspace_id, endpoint_id, version, method, path, status, duration_ms, rows,
                                    client_id, service_account_id, ip, error_code, error, trace_id, actor_user_id, request_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::inet, ?, ?, ?, ?, ?)""";

    private final BlockingQueue<AccessRecord> queue = new ArrayBlockingQueue<>(10_000);
    private final JdbcTemplate jdbc;
    private final WorkspaceScope scope;
    private final WorkspaceStore workspaces;
    private final Counter dropped;
    private final int retentionDays;
    private final Thread worker;
    private volatile boolean running = true;
    private volatile Instant lastPurge = Instant.EPOCH;

    public AccessLogWriter(JdbcTemplate jdbc, WorkspaceScope scope, WorkspaceStore workspaces,
                           org.springframework.beans.factory.ObjectProvider<MeterRegistry> meters,
                           @Value("${opendbapi.access-log.retention-days:30}") int retentionDays) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.workspaces = workspaces;
        this.dropped = Counter.builder("opendbapi.access_log.dropped").description("Access records lost to a full queue")
                .register(meters.getIfAvailable(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new));
        this.retentionDays = Math.max(1, retentionDays);
        this.worker = Thread.ofVirtual().name("opendbapi-access-log").start(this::loop);
    }

    public void offer(AccessRecord record) {
        if (!queue.offer(record)) {
            dropped.increment();
        }
    }

    /** Writes everything queued so far; for tests and shutdown. */
    public synchronized void flush() {
        List<AccessRecord> batch = new ArrayList<>();
        while (queue.drainTo(batch, BATCH) > 0) {
            write(batch);
            batch.clear();
        }
    }

    private void loop() {
        while (running) {
            try {
                AccessRecord first = queue.poll(1, TimeUnit.SECONDS);
                if (first != null) {
                    List<AccessRecord> batch = new ArrayList<>(List.of(first));
                    queue.drainTo(batch, BATCH - 1);
                    synchronized (this) {
                        write(batch);
                    }
                }
                if (Duration.between(lastPurge, Instant.now()).toHours() >= 6) {
                    purge();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("Access log write failed: {}", e.getMessage());
            }
        }
    }

    private void write(List<AccessRecord> batch) {
        Map<UUID, List<AccessRecord>> byWorkspace = batch.stream().collect(Collectors.groupingBy(AccessRecord::workspaceId));
        byWorkspace.forEach((wsId, records) -> scope.run(wsId, () -> jdbc.batchUpdate(INSERT, records, records.size(), (ps, r) -> {
            ps.setTimestamp(1, Timestamp.from(r.ts()));
            ps.setObject(2, r.workspaceId());
            ps.setObject(3, r.endpointId());
            ps.setObject(4, r.version());
            ps.setString(5, r.method());
            ps.setString(6, truncate(r.path(), 2000));
            ps.setInt(7, r.status());
            ps.setInt(8, (int) Math.min(Integer.MAX_VALUE, r.durationMs()));
            ps.setObject(9, r.rows() == null ? null : (int) Math.min(Integer.MAX_VALUE, r.rows()));
            ps.setString(10, r.clientId());
            ps.setObject(11, r.serviceAccountId());
            ps.setString(12, IpRange.address(r.ip()) == null ? null : r.ip());
            ps.setString(13, r.errorCode());
            ps.setString(14, truncate(r.error(), 2000));
            ps.setString(15, r.traceId());
            ps.setObject(16, r.actorUserId());
            ps.setString(17, r.requestId());
        })));
    }

    private void purge() {
        lastPurge = Instant.now();
        for (var ws : workspaces.list()) {
            int deleted = scope.call(ws.id(), () -> jdbc.update(
                    "delete from access_log where ts < now() - make_interval(days => ?)", retentionDays));
            if (deleted > 0) {
                log.info("Deleted {} access log rows older than {} days in workspace {}", deleted, retentionDays, ws.slug());
            }
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        worker.interrupt();
        try {
            flush();
        } catch (RuntimeException e) {
            log.warn("Access log not flushed at shutdown: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
