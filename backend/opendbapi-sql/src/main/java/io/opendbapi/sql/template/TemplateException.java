package io.opendbapi.sql.template;

/**
 * A template or expression that cannot be compiled or rendered. The message is written for
 * the endpoint author and, for compile errors, says where (line/column) the problem is.
 */
public class TemplateException extends RuntimeException {

    public TemplateException(String message) {
        super(message);
    }

    public TemplateException(String message, Throwable cause) {
        super(message, cause);
    }
}
