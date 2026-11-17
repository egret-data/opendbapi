package io.opendbapi.core.script;

/** A full-mode script ended the request with an error answer, e.g. {@code notFound("…")}. */
public final class ScriptAnswer extends RuntimeException {

    private final int status;
    private final String code;

    public ScriptAnswer(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
