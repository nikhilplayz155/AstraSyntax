package io.astra.platform;

/**
 * A cancellable unit of scheduled work.
 *
 * <p>AstraSyntax tracks the handle of every task a script owns so the task can be
 * cancelled when the script is disabled or reloaded. Handles are also exposed to
 * scripts through cooldowns and timed actions, which is why cancellation is
 * idempotent and never throws.</p>
 */
public interface TaskHandle {

    /** Cancel the task. Safe to call multiple times and from any thread. */
    void cancel();

    /** True when the task was cancelled or has already finished. */
    boolean isCancelled();

    /** True for repeating tasks. */
    boolean isRepeating();

    /** Short description used by {@code /astra performance} and diagnostics. */
    String describe();

    /** A handle that does nothing; used when a task could not be scheduled. */
    static TaskHandle noop(String reason) {
        return new TaskHandle() {
            @Override public void cancel() { }
            @Override public boolean isCancelled() { return true; }
            @Override public boolean isRepeating() { return false; }
            @Override public String describe() { return "unscheduled (" + reason + ")"; }
        };
    }
}
