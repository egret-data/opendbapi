package io.opendbapi.core.script;

/** A script broke a sandbox rule or produced something other than a fragment. Message is for authors. */
public class ScriptRejected extends RuntimeException {
    public ScriptRejected(String message) {
        super(message);
    }
}
