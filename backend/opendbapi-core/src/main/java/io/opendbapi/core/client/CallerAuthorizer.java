package io.opendbapi.core.client;

import io.opendbapi.core.workspace.WorkspaceScope;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Checks, per call, that the token's client is still usable and its service account holds a
 * grant on the endpoint (spec §8.1 steps 5–6). Answers are cached briefly; changes made through
 * this instance's admin API clear the cache at once, other instances catch up within the TTL.
 */
@Component
public class CallerAuthorizer {

    static final Duration TTL = Duration.ofSeconds(15);

    private record Key(UUID workspaceId, String clientId, UUID endpointId) {
    }

    private record Entry(ServiceAccountStore.CallerStatus status, long expiresAtNanos) {
    }

    private final ServiceAccountStore store;
    private final WorkspaceScope scope;
    private final Map<Key, Entry> cache = new ConcurrentHashMap<>();

    public CallerAuthorizer(ServiceAccountStore store, WorkspaceScope scope) {
        this.store = store;
        this.scope = scope;
    }

    public ServiceAccountStore.CallerStatus check(UUID workspaceId, String clientId, UUID endpointId) {
        Key key = new Key(workspaceId, clientId, endpointId);
        long now = System.nanoTime();
        Entry hit = cache.get(key);
        if (hit != null && hit.expiresAtNanos() - now > 0) {
            return hit.status();
        }
        var status = scope.call(workspaceId, () -> store.callerStatus(clientId, endpointId));
        if (cache.size() > 10_000) {
            cache.clear();
        }
        cache.put(key, new Entry(status, now + TTL.toNanos()));
        return status;
    }

    public void invalidate(UUID workspaceId) {
        cache.keySet().removeIf(k -> k.workspaceId().equals(workspaceId));
    }
}
