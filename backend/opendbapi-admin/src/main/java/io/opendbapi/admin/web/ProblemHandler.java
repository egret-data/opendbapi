package io.opendbapi.admin.web;

import io.opendbapi.core.error.ApiProblem;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every management API error is an RFC 9457 problem with a stable {@code code} (spec §9.4):
 * our own {@link ApiProblem}s, validation and malformed bodies, and every other Spring MVC
 * error (wrong method, unsupported media type, …).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProblemHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<ProblemDetail> apiProblem(ApiProblem e) {
        return problem(HttpStatus.valueOf(e.status()), e.code(), e.getMessage(), null);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        String detail = fields.entrySet().stream()
                .map(f -> f.getKey() + " " + f.getValue())
                .reduce((a, b) -> a + "; " + b)
                .orElse("The request is invalid.");
        return widen(problem(HttpStatus.BAD_REQUEST, "invalid_request", detail, Map.of("fields", fields)));
    }

    /** Includes unknown JSON fields, refused rather than silently dropped (spec §5.2). */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        String reason = e.getMostSpecificCause().getMessage();
        int cut = reason == null ? -1 : reason.indexOf(" (");
        String detail = reason == null ? "The request body is not valid JSON." : (cut > 0 ? reason.substring(0, cut) : reason);
        return widen(problem(HttpStatus.BAD_REQUEST, "invalid_body", detail, null));
    }

    /** Gives Spring's own problems (405, 415, …) a {@code code} too. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode status,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail problem && (problem.getProperties() == null
                || !problem.getProperties().containsKey("code"))) {
            HttpStatus resolved = HttpStatus.resolve(status.value());
            problem.setProperty("code", resolved == null ? "error"
                    : resolved.name().toLowerCase(Locale.ROOT));
        }
        return super.createResponseEntity(body, headers, status, request);
    }

    public static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail, Map<String, ?> extra) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create("about:blank"));
        body.setProperty("code", code);
        if (extra != null) {
            extra.forEach(body::setProperty);
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResponseEntity<Object> widen(ResponseEntity<ProblemDetail> entity) {
        return (ResponseEntity) entity;
    }
}
