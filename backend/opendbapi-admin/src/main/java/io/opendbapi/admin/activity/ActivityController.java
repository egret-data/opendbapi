package io.opendbapi.admin.activity;

import io.opendbapi.admin.security.WorkspaceAccess;
import io.opendbapi.core.activity.ActivityStore;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.workspace.WorkspaceRole;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Calls, errors and latency from the access log (spec §17.4 Activity). Members read. */
@RestController
@RequestMapping(path = "/admin/api/ws/{ws}/activity", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "activity")
public class ActivityController {

    private static final Duration MAX_RANGE = Duration.ofDays(93);

    private final WorkspaceAccess access;
    private final WorkspaceScope scope;
    private final ActivityStore store;

    public ActivityController(WorkspaceAccess access, WorkspaceScope scope, ActivityStore store) {
        this.access = access;
        this.scope = scope;
        this.store = store;
    }

    @GetMapping("/summary")
    @Operation(operationId = "activitySummary", summary = "Totals, calls per day, top endpoints and errors; Try-it calls hidden by default")
    public ActivityStore.Summary summary(@PathVariable String ws,
                                        @RequestParam(required = false) Instant from,
                                        @RequestParam(required = false) Instant to,
                                        @RequestParam(defaultValue = "false") boolean includeTryIt) {
        var granted = access.require(ws, WorkspaceRole.VIEWER);
        Instant[] range = range(from, to);
        return scope.call(granted.workspace().id(), () -> store.summary(range[0], range[1], includeTryIt));
    }

    @GetMapping("/log")
    @Operation(operationId = "activityLog", summary = "Newest calls first; page with 'before' = ts of the last row")
    public List<ActivityStore.LogEntry> log(@PathVariable String ws,
                                            @RequestParam(required = false) Instant from,
                                            @RequestParam(required = false) Instant to,
                                            @RequestParam(required = false) UUID endpointId,
                                            @RequestParam(required = false) String clientId,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String requestId,
                                            @RequestParam(defaultValue = "false") boolean includeTryIt,
                                            @RequestParam(required = false) Instant before,
                                            @RequestParam(defaultValue = "100") int limit) {
        var granted = access.require(ws, WorkspaceRole.VIEWER);
        Instant[] range = range(from, to);
        return scope.call(granted.workspace().id(), () -> store.log(new ActivityStore.LogFilter(range[0], range[1], endpointId,
                clientId, status, requestId, includeTryIt, before, limit)));
    }

    private static Instant[] range(Instant from, Instant to) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(Duration.ofDays(7)) : from;
        if (!start.isBefore(end)) {
            throw ApiProblem.badRequest("invalid_range", "'from' must be before 'to'.");
        }
        if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw ApiProblem.badRequest("invalid_range", "The range may span at most 93 days.");
        }
        return new Instant[] {start, end};
    }
}
