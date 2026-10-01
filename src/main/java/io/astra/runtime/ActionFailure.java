package io.astra.runtime;

/**
 * Raised when an action cannot be completed.
 *
 * <p>A failure is normal control flow: it stops the current rule, records a diagnostic
 * and leaves the server running. Stack traces are suppressed because these are thrown on
 * hot paths and a rule can never be resumed from a Java stack.</p>
 */
public final class ActionFailure extends RuntimeException {

    private final boolean silent;

    public ActionFailure(String message) {
        this(message, false);
    }

    public ActionFailure(String message, boolean silent) {
        super(message, null, false, false);
        this.silent = silent;
    }

    /** A failure that should not be logged (for example "player is not online"). */
    public static ActionFailure silent(String message) {
        return new ActionFailure(message, true);
    }

    /** Wrap an unexpected error as a rule failure. */
    public static ActionFailure wrap(String what, Throwable cause) {
        ActionFailure failure = new ActionFailure(what + ": " + cause.getClass().getSimpleName()
            + (cause.getMessage() == null ? "" : " (" + cause.getMessage() + ")"));
        failure.initCause(cause);
        return failure;
    }

    public boolean isSilent() {
        return silent;
    }
}
