package io.opendbapi.core.error;

/**
 * A failure that reaches the client as an RFC 9457 problem: HTTP status, a stable
 * machine-readable {@code code}, and a {@code detail} written for the person reading it.
 */
public class ApiProblem extends RuntimeException {

    private final int status;
    private final String code;

    public ApiProblem(int status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiProblem badRequest(String code, String detail) {
        return new ApiProblem(400, code, detail);
    }

    public static ApiProblem forbidden(String detail) {
        return new ApiProblem(403, "forbidden", detail);
    }

    public static ApiProblem notFound(String code, String detail) {
        return new ApiProblem(404, code, detail);
    }

    public static ApiProblem conflict(String code, String detail) {
        return new ApiProblem(409, code, detail);
    }
}
