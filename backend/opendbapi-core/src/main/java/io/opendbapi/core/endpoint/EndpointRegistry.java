package io.opendbapi.core.endpoint;

import io.opendbapi.core.workspace.WorkspaceScope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Live endpoints per workspace, compiled once and matched per request (spec §3.3). A
 * workspace's routes are loaded on first use and dropped whenever one of its endpoints is
 * published, rolled back, disabled, enabled or trashed.
 */
@Component
public class EndpointRegistry {

    private static final Logger log = LoggerFactory.getLogger(EndpointRegistry.class);

    public record Route(UUID endpointId, UUID versionId, int version, String method, PathPattern pattern,
                        String access, EndpointDefinition definition, CompiledTask task) {
    }

    public record Match(Route route, Map<String, String> pathVariables) {
    }

    private final Map<UUID, List<Route>> routes = new ConcurrentHashMap<>();
    private final EndpointStore store;
    private final WorkspaceScope scope;
    private final PathPatternParser parser = new PathPatternParser();

    public EndpointRegistry(EndpointStore store, WorkspaceScope scope) {
        this.store = store;
        this.scope = scope;
    }

    public Optional<Match> resolve(UUID workspaceId, String method, String path) {
        PathContainer container = PathContainer.parsePath(path.startsWith("/") ? path : "/" + path);
        for (Route route : routes.computeIfAbsent(workspaceId, this::load)) {
            if (!route.method().equals(method)) {
                continue;
            }
            PathPattern.PathMatchInfo info = route.pattern().matchAndExtract(container);
            if (info != null) {
                return Optional.of(new Match(route, info.getUriVariables()));
            }
        }
        return Optional.empty();
    }

    private io.opendbapi.core.cluster.ChangeBus changes;

    /** Endpoint changes on any instance reach this one (several pods). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setChanges(io.opendbapi.core.cluster.ChangeBus changes) {
        this.changes = changes;
        changes.on("endpoints", ws -> {
            if (ws == null) {
                routes.clear();
            } else {
                routes.remove(ws);
            }
        });
    }

    /** The workspace's live endpoints changed: every instance reloads its routes. */
    public void invalidate(UUID workspaceId) {
        if (changes != null) {
            changes.publish("endpoints", workspaceId);
        } else {
            routes.remove(workspaceId);
        }
    }

    private List<Route> load(UUID workspaceId) {
        List<Route> out = new ArrayList<>();
        for (EndpointStore.LiveRow row : scope.call(workspaceId, store::live)) {
            EndpointVersion.Snapshot s = row.snapshot();
            try {
                out.add(new Route(row.endpointId(), row.versionId(), row.version(), s.method(),
                        parser.parse("/" + s.path()), s.access(), s.definition(), CompiledTask.compile(s.definition().task())));
            } catch (RuntimeException e) {
                log.error("Live endpoint {} v{} cannot be compiled and is not served: {}", row.endpointId(), row.version(), e.getMessage());
            }
        }
        out.sort(Comparator.comparing(Route::pattern, PathPattern.SPECIFICITY_COMPARATOR));
        return List.copyOf(out);
    }
}
