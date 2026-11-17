package io.opendbapi.admin.endpoint;

import io.opendbapi.admin.security.WorkspaceAccess;
import io.opendbapi.core.endpoint.EndpointStore;
import io.opendbapi.core.endpoint.test.EndpointTestCase;
import io.opendbapi.core.endpoint.test.EndpointTestStore;
import io.opendbapi.core.endpoint.test.EndpointTests;
import io.opendbapi.core.error.ApiProblem;
import io.opendbapi.core.workspace.WorkspaceRole;
import io.opendbapi.core.workspace.WorkspaceScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Test cases of an endpoint (spec §11.3). Members read; authors write and run them. */
@RestController
@RequestMapping(path = "/admin/api/ws/{ws}/endpoints/{endpointId}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "endpoint-tests")
public class EndpointTestController {

    public record SaveEndpointTest(String name,
                                   @Schema(nullable = true) UUID serviceAccountId,
                                   @Schema(nullable = true) Map<String, Object> params,
                                   @Schema(nullable = true) EndpointTestCase.TestExpectation expect) {

        EndpointTestCase toCase() {
            return new EndpointTestCase(null, name == null ? null : name.strip(), serviceAccountId, params == null ? Map.of() : params,
                    expect == null ? new EndpointTestCase.TestExpectation(200, null, null, null, null) : expect, null, null, null);
        }
    }

    private final WorkspaceAccess access;
    private final WorkspaceScope scope;
    private final EndpointStore endpoints;
    private final EndpointTestStore store;
    private final EndpointTests tests;

    public EndpointTestController(WorkspaceAccess access, WorkspaceScope scope, EndpointStore endpoints, EndpointTestStore store,
                                  EndpointTests tests) {
        this.access = access;
        this.scope = scope;
        this.endpoints = endpoints;
        this.store = store;
        this.tests = tests;
    }

    @GetMapping("/tests")
    @Operation(operationId = "listEndpointTests", summary = "Test cases of an endpoint, with their last results")
    public List<EndpointTestCase> list(@PathVariable String ws, @PathVariable UUID endpointId) {
        var granted = access.require(ws, WorkspaceRole.VIEWER);
        return scope.call(granted.workspace().id(), () -> {
            requireEndpoint(endpointId);
            return store.list(endpointId);
        });
    }

    @PostMapping("/tests")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createEndpointTest", summary = "Add a test case (author)")
    public EndpointTestCase create(@PathVariable String ws, @PathVariable UUID endpointId, @RequestBody SaveEndpointTest body) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        EndpointTestCase t = body.toCase();
        EndpointTests.validate(t);
        UUID wsId = granted.workspace().id();
        return scope.call(wsId, () -> {
            requireEndpoint(endpointId);
            UUID id = store.create(wsId, endpointId, t, granted.user().id());
            return store.find(endpointId, id).orElseThrow();
        });
    }

    @PatchMapping("/tests/{testId}")
    @Operation(operationId = "updateEndpointTest", summary = "Change a test case (author)")
    public EndpointTestCase update(@PathVariable String ws, @PathVariable UUID endpointId, @PathVariable UUID testId,
                                   @RequestBody SaveEndpointTest body) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        EndpointTestCase t = body.toCase();
        EndpointTests.validate(t);
        return scope.call(granted.workspace().id(), () -> {
            if (!store.update(endpointId, testId, t)) {
                throw notFound();
            }
            return store.find(endpointId, testId).orElseThrow();
        });
    }

    @DeleteMapping("/tests/{testId}")
    @Operation(operationId = "deleteEndpointTest", summary = "Remove a test case (author)")
    public ResponseEntity<Void> delete(@PathVariable String ws, @PathVariable UUID endpointId, @PathVariable UUID testId) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        boolean deleted = scope.call(granted.workspace().id(), () -> store.delete(endpointId, testId));
        if (!deleted) {
            throw notFound();
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tests:run")
    @Operation(operationId = "runEndpointTests", summary = "Run all test cases, or one, against the saved draft (author)")
    public EndpointTests.TestRunSummary run(@PathVariable String ws, @PathVariable UUID endpointId,
                                            @RequestParam(required = false) UUID testId) {
        var granted = access.require(ws, WorkspaceRole.AUTHOR);
        return tests.run(granted.workspace().id(), endpointId, testId);
    }

    private void requireEndpoint(UUID endpointId) {
        if (endpoints.find(endpointId).isEmpty()) {
            throw ApiProblem.notFound("endpoint_not_found", "That endpoint does not exist.");
        }
    }

    private static ApiProblem notFound() {
        return ApiProblem.notFound("test_not_found", "That test case does not exist.");
    }
}
