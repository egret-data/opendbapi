package io.opendbapi.serving;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entry point of the serving surface {@code /{context}/{ws}/**} (spec §3.1, §9.1).
 *
 * <p>Endpoint resolution against the registry arrives with the endpoint lifecycle
 * (roadmap step 5); until then every call resolves to nothing and gets the same
 * RFC 9457 {@code not_found} problem a missing endpoint will get later.
 */
@RestController
public class ServingDispatcher {

    private static final String WS_PREFIX_PATTERN = "/${opendbapi.serving.context:api}/{ws}/";

    @RequestMapping(WS_PREFIX_PATTERN + "**")
    public ResponseEntity<ProblemDetail> dispatch(@PathVariable("ws") String workspaceSlug, HttpServletRequest request) {
        String endpointPath = endpointPath(request.getRequestURI(), request.getContextPath(), workspaceSlug);
        return notFound(request.getMethod(), endpointPath, workspaceSlug);
    }

    /** Path after {@code /{context}/{ws}}, always starting with a slash. */
    static String endpointPath(String requestUri, String servletContextPath, String workspaceSlug) {
        String path = requestUri.substring(servletContextPath.length());
        int wsStart = path.indexOf('/', 1) + 1;
        return path.substring(wsStart + workspaceSlug.length());
    }

    private static ResponseEntity<ProblemDetail> notFound(String method, String path, String workspaceSlug) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Not found");
        problem.setType(URI.create("about:blank"));
        problem.setDetail("No published endpoint matches %s %s in workspace '%s'.".formatted(method, path, workspaceSlug));
        problem.setProperty("code", "not_found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
