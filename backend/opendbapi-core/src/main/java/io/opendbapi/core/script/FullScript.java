package io.opendbapi.core.script;

/**
 * Base class of full-mode scripts (spec §7.4): fragment helpers plus {@code notFound} and
 * {@code badRequest}; {@code db} and {@code params} come from the binding.
 */
public abstract class FullScript extends FragmentScript {

    /** Answers 404 with this message. */
    public Object notFound(Object message) {
        throw new ScriptAnswer(404, "not_found", message == null ? "Not found." : message.toString());
    }

    /** Answers 400 with this message. */
    public Object badRequest(Object message) {
        throw new ScriptAnswer(400, "bad_request", message == null ? "Bad request." : message.toString());
    }
}
