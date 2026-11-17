package io.opendbapi.admin.endpoint;

import io.opendbapi.admin.security.WorkspaceAccess;
import io.opendbapi.core.endpoint.EndpointStore;
import io.opendbapi.core.endpoint.ResultCache;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.workspace.WorkspaceRole;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Drops an endpoint's cached answers (spec §9.5), e.g. after fixing data behind it. */
@RestController
@RequestMapping(path = "/admin/api/ws/{ws}/endpoints/{endpointId}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "endpoints")
public class EndpointCacheController {

    private final WorkspaceAccess access;
    private final WorkspaceScope scope;
    private final EndpointStore endpoints;
    private final ResultCache results;

    public EndpointCacheController(WorkspaceAccess access, WorkspaceScope scope, EndpointStore endpoints, ResultCache results) {
        this.access = access;
        this.scope = scope;
        this.endpoints = endpoints;
        this.results = results;
    }

    @PostMapping("/cache:clear")
    @Operation(operationId = "clearEndpointCache", summary = "Forget the endpoint's cached answers on this server (author)")
    public ResponseEntity<Void> clear(@PathVariable String ws, @PathVariable UUID endpointId) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        boolean exists = scope.call(granted.workspace().id(), () -> endpoints.find(endpointId).isPresent());
        if (!exists) {
            throw ApiProblem.notFound("endpoint_not_found", "That endpoint does not exist.");
        }
        results.clearEndpoint(endpointId);
        return ResponseEntity.noContent().build();
    }
}
