package io.opendbapi.core.cluster;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Tells the other instances that something they cache has changed (spec §3.2), through
 * PostgreSQL LISTEN/NOTIFY, so several pods behind a load balancer need no Redis. A change
 * is a topic and an id; every instance, this one included, runs the topic's handlers.
 * Notifications sent while an instance was disconnected are lost, so after reconnecting
 * it runs every handler with a null id, which means "forget everything".
 */
@Component
public class ChangeBus implements SmartLifecycle, DisposableBean {

    public static final String CHANNEL = "opendbapi_changes";
    private static final Logger log = LoggerFactory.getLogger(ChangeBus.class);

    private final String instance = UUID.randomUUID().toString();
    private final Map<String, List<Consumer<UUID>>> handlers = new ConcurrentHashMap<>();
    private final JdbcClient jdbc;
    private final String url;
    private final String user;
    private final String password;
    private volatile boolean running;
    private volatile Thread listener;
    private volatile Connection connection;

    public ChangeBus(JdbcClient jdbc, @Value("${spring.datasource.url}") String url,
                     @Value("${spring.datasource.username}") String user, @Value("${spring.datasource.password:}") String password) {
        this.jdbc = jdbc;
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** Runs {@code handler} whenever {@code topic} changes; a null id means "everything". */
    public void on(String topic, Consumer<UUID> handler) {
        handlers.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /** Applies the change here at once and tells the other instances when the transaction commits. */
    public void publish(String topic, UUID id) {
        dispatch(topic, id);
        try {
            jdbc.sql("select pg_notify(?, ?)").params(CHANNEL, instance + " " + topic + " " + id).query().listOfRows();
        } catch (RuntimeException e) {
            log.warn("Could not tell other instances about {} {}: {}", topic, id, e.getMessage());
        }
    }

    private void dispatch(String topic, UUID id) {
        for (Consumer<UUID> h : handlers.getOrDefault(topic, List.of())) {
            try {
                h.accept(id);
            } catch (RuntimeException e) {
                log.warn("Handling change {} {} failed", topic, id, e);
            }
        }
    }

    void received(String payload) {
        String[] parts = payload.split(" ", 3);
        if (parts.length != 3 || parts[0].equals(instance)) {
            return;
        }
        UUID id;
        try {
            id = "null".equals(parts[2]) ? null : UUID.fromString(parts[2]);
        } catch (IllegalArgumentException e) {
            return;
        }
        dispatch(parts[1], id);
    }

    private void forgetEverything() {
        handlers.keySet().forEach(topic -> dispatch(topic, null));
    }

    private void listen() {
        boolean first = true;
        while (running) {
            try (Connection c = DriverManager.getConnection(url, user, password)) {
                connection = c;
                try (Statement s = c.createStatement()) {
                    s.execute("LISTEN " + CHANNEL);
                }
                if (!first) {
                    forgetEverything();
                }
                first = false;
                PGConnection pg = c.unwrap(PGConnection.class);
                while (running) {
                    PGNotification[] batch = pg.getNotifications(10_000);
                    if (batch != null) {
                        for (PGNotification n : batch) {
                            received(n.getParameter());
                        }
                    }
                }
            } catch (SQLException e) {
                if (running) {
                    log.warn("Change listener lost its connection, reconnecting: {}", e.getMessage());
                    first = false;
                    sleep();
                }
            } finally {
                connection = null;
            }
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void start() {
        running = true;
        listener = Thread.ofPlatform().daemon().name("opendbapi-change-listener").start(this::listen);
    }

    @Override
    public void stop() {
        running = false;
        Connection c = connection;
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // closing anyway
            }
        }
        Thread t = listener;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void destroy() {
        stop();
    }
}
